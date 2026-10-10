use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering::Relaxed};
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use ha_iroh_proto::{
    read_frame, write_frame, PairRequest, PairResponse, PairTicket, RelayConfig, ALPN_PAIR,
    ALPN_TCP,
};
use iroh::endpoint::Incoming;
use iroh::{Endpoint, EndpointId};
use serde::{Deserialize, Serialize};
use tokio::io::{copy_bidirectional, join};
use tokio::net::TcpStream;
use tokio::sync::mpsc;
use tracing::{info, warn};

#[derive(Debug, Deserialize)]
#[serde(default)]
pub struct Config {
    pub ha_addr: String,
    pub secret_key_path: PathBuf,
    pub allowlist_path: PathBuf,
    pub relay: RelayConfig,
    pub name: String,
    /// Pairing code lifetime in seconds.
    pub pair_ttl: u64,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            ha_addr: "127.0.0.1:8123".into(),
            secret_key_path: "ha-iroh-bridge.key".into(),
            allowlist_path: "allowlist.toml".into(),
            relay: RelayConfig::default(),
            name: "Home Assistant".into(),
            pair_ttl: 600,
        }
    }
}

#[derive(Serialize, Deserialize, Default)]
struct Allowlist {
    #[serde(default)]
    devices: Vec<Device>,
}

#[derive(Serialize, Deserialize, Clone)]
pub struct Device {
    pub endpoint_id: String,
    pub device_name: String,
    pub added_at: String,
}

/// Unsolicited stdout line for the parent process.
#[derive(Debug, Serialize)]
#[serde(tag = "event", rename_all = "snake_case")]
pub enum Event {
    Paired {
        endpoint_id: String,
        device_name: String,
    },
}

const MAX_FAILS: u32 = 5;
const FAIL_WINDOW: Duration = Duration::from_secs(60);

struct Inner {
    cfg: Config,
    ep: Endpoint,
    allowlist: Mutex<Allowlist>,
    codes: Mutex<HashMap<String, Instant>>,
    // ponytail: in-memory, resets on restart
    fails: Mutex<HashMap<EndpointId, (u32, Instant)>>,
    events: mpsc::Sender<Event>,
}

#[derive(Clone)]
pub struct Bridge(Arc<Inner>);

impl Bridge {
    /// Loads the allowlist once; later changes go through this struct and are written back atomically.
    pub fn new(cfg: Config, ep: Endpoint) -> anyhow::Result<(Bridge, mpsc::Receiver<Event>)> {
        let allowlist = match std::fs::read_to_string(&cfg.allowlist_path) {
            Ok(s) => toml::from_str(&s)?,
            Err(e) if e.kind() == std::io::ErrorKind::NotFound => Allowlist::default(),
            Err(e) => return Err(e.into()),
        };
        let (events, rx) = mpsc::channel(16);
        let inner = Inner {
            cfg,
            ep,
            allowlist: allowlist.into(),
            codes: Default::default(),
            fails: Default::default(),
            events,
        };
        Ok((Bridge(Arc::new(inner)), rx))
    }

    /// Mint a single-use pairing code and the ticket that carries it.
    pub fn mint_code(&self) -> PairTicket {
        let i = &self.0;
        let code = data_encoding::BASE32_NOPAD
            .encode(&iroh::SecretKey::generate().to_bytes()[..16])
            .to_lowercase();
        let now = Instant::now();
        let mut codes = i.codes.lock().unwrap();
        codes.retain(|_, exp| *exp > now);
        codes.insert(code.clone(), now + Duration::from_secs(i.cfg.pair_ttl));
        PairTicket {
            v: 1,
            addr: i.ep.addr(),
            relay: i.cfg.relay.clone(),
            code,
            name: i.cfg.name.clone(),
        }
    }

    /// Accept loop. Returns when the endpoint is closed.
    pub async fn serve(self) {
        while let Some(incoming) = self.0.ep.accept().await {
            let this = self.clone();
            tokio::spawn(async move {
                if let Err(e) = this.handle(incoming).await {
                    warn!("connection failed: {e:#}");
                }
            });
        }
    }

    async fn handle(&self, incoming: Incoming) -> anyhow::Result<()> {
        let conn = incoming.await?;
        let peer = conn.remote_id();
        if conn.alpn() == ALPN_PAIR {
            return self.handle_pair(conn).await;
        }
        if conn.alpn() != ALPN_TCP {
            conn.close(2u32.into(), b"unknown protocol");
            return Ok(());
        }
        let cfg = &self.0.cfg;
        let id = peer.to_string();
        let device = self
            .0
            .allowlist
            .lock()
            .unwrap()
            .devices
            .iter()
            .find(|d| d.endpoint_id == id)
            .map(|d| d.device_name.clone());
        let Some(device) = device else {
            warn!(%peer, "rejected: not in allowlist");
            conn.close(1u32.into(), b"not allowed");
            return Ok(());
        };
        info!(%peer, device, "connected");
        let (opened, closed) = (Arc::new(AtomicU64::new(0)), Arc::new(AtomicU64::new(0)));
        while let Ok((send, recv)) = conn.accept_bi().await {
            let n = opened.fetch_add(1, Relaxed) + 1;
            info!(%peer, device, opened = n, closed = closed.load(Relaxed), "stream open");
            let (ha, device, closed) = (cfg.ha_addr.clone(), device.clone(), closed.clone());
            tokio::spawn(async move {
                // copy_bidirectional shuts down each writer at the other side's EOF
                // (SendStream shutdown == finish()), so half-close propagates both ways.
                let r = async {
                    let mut tcp = TcpStream::connect(&ha).await?;
                    copy_bidirectional(&mut join(recv, send), &mut tcp).await
                }
                .await;
                let c = closed.fetch_add(1, Relaxed) + 1;
                match r {
                    Ok((up, down)) => info!(%peer, device, closed = c, up, down, "stream closed"),
                    Err(e) => warn!(%peer, device, closed = c, "stream error: {e}"),
                }
            });
        }
        info!(%peer, device, opened = opened.load(Relaxed), closed = closed.load(Relaxed), "disconnected");
        Ok(())
    }

    async fn handle_pair(&self, conn: iroh::endpoint::Connection) -> anyhow::Result<()> {
        let i = &self.0;
        let peer = conn.remote_id();
        let limited = matches!(i.fails.lock().unwrap().get(&peer), Some((n, t)) if *n >= MAX_FAILS && t.elapsed() < FAIL_WINDOW);
        if limited {
            warn!(%peer, "pairing rate limited");
            conn.close(3u32.into(), b"rate_limited");
            return Ok(());
        }
        // an unauthenticated peer must not hold this task open
        tokio::time::timeout(Duration::from_secs(10), self.pair_exchange(&conn, peer)).await??;
        conn.close(0u32.into(), b"ok");
        Ok(())
    }

    async fn pair_exchange(
        &self,
        conn: &iroh::endpoint::Connection,
        peer: EndpointId,
    ) -> anyhow::Result<()> {
        let i = &self.0;
        let (mut send, mut recv) = conn.accept_bi().await?;
        let req: PairRequest = read_frame(&mut recv).await?;
        let name = req.device_name.trim();
        let verdict = if name.is_empty() || name.chars().count() > 64 {
            Err("bad_request")
        } else {
            self.redeem(&req.code)
        };
        let resp = match verdict {
            Err(e) => {
                let mut f = i.fails.lock().unwrap();
                f.retain(|_, (_, t)| t.elapsed() < FAIL_WINDOW);
                let entry = f.entry(peer).or_insert((0, Instant::now()));
                *entry = (entry.0 + 1, Instant::now());
                PairResponse {
                    ok: false,
                    bridge_name: None,
                    error: Some(e.into()),
                }
            }
            Ok(()) => {
                self.add_device(Device {
                    endpoint_id: peer.to_string(),
                    device_name: name.to_string(),
                    added_at: time::OffsetDateTime::now_utc()
                        .format(&time::format_description::well_known::Rfc3339)?,
                })?;
                info!(%peer, device = name, "paired");
                let _ = i
                    .events
                    .send(Event::Paired {
                        endpoint_id: peer.to_string(),
                        device_name: name.to_string(),
                    })
                    .await;
                PairResponse {
                    ok: true,
                    bridge_name: Some(i.cfg.name.clone()),
                    error: None,
                }
            }
        };
        write_frame(&mut send, &resp).await?;
        send.finish()?;
        // let the peer receive the response before the caller closes the connection
        let _ = tokio::time::timeout(Duration::from_secs(2), send.stopped()).await;
        Ok(())
    }

    /// `Ok` = code was valid and is now burned.
    fn redeem(&self, code: &str) -> Result<(), &'static str> {
        match self.0.codes.lock().unwrap().remove(code) {
            None => Err("invalid_code"),
            Some(exp) if Instant::now() >= exp => Err("expired"),
            Some(_) => Ok(()),
        }
    }

    /// Persist first; memory only changes once the file is safely in place.
    /// Re-pairing the same endpoint replaces its entry.
    fn add_device(&self, d: Device) -> anyhow::Result<()> {
        let mut list = self.0.allowlist.lock().unwrap();
        let mut next = Allowlist {
            devices: list
                .devices
                .iter()
                .filter(|x| x.endpoint_id != d.endpoint_id)
                .cloned()
                .collect(),
        };
        next.devices.push(d);
        let path = &self.0.cfg.allowlist_path;
        let tmp = path.with_extension("tmp");
        std::fs::write(&tmp, toml::to_string(&next)?)?;
        std::fs::rename(&tmp, path)?;
        *list = next;
        Ok(())
    }
}
