use std::collections::HashMap;
use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering::Relaxed};
use std::sync::{Arc, Mutex, OnceLock};
use std::time::{Duration, Instant};

use ha_iroh_proto::{
    read_frame, write_frame, PairRequest, PairResponse, PairTicket, RelayConfig, ALPN_PAIR,
    ALPN_TCP,
};
use iroh::endpoint::{Connection, Incoming};
use iroh::{Endpoint, EndpointId};
use rustls::client::danger::{HandshakeSignatureValid, ServerCertVerified, ServerCertVerifier};
use rustls::crypto::{verify_tls12_signature, verify_tls13_signature, CryptoProvider};
use rustls::pki_types::{CertificateDer, ServerName, UnixTime};
use rustls::{ClientConfig, DigitallySignedStruct, SignatureScheme};
use serde::{Deserialize, Serialize};
use tokio::io::{copy_bidirectional, join};
use tokio::net::TcpStream;
use tokio::sync::mpsc;
use tokio_rustls::TlsConnector;
use tracing::{info, warn};

/// Accepts any upstream cert (`ha_tls` hop is loopback and HA's cert names a domain, not 127.0.0.1);
/// handshake signatures are still checked.
#[derive(Debug)]
struct NoVerify(Arc<CryptoProvider>);

impl ServerCertVerifier for NoVerify {
    fn verify_server_cert(
        &self,
        _: &CertificateDer<'_>,
        _: &[CertificateDer<'_>],
        _: &ServerName<'_>,
        _: &[u8],
        _: UnixTime,
    ) -> Result<ServerCertVerified, rustls::Error> {
        Ok(ServerCertVerified::assertion())
    }
    fn verify_tls12_signature(&self, m: &[u8], c: &CertificateDer<'_>, d: &DigitallySignedStruct) -> Result<HandshakeSignatureValid, rustls::Error> {
        verify_tls12_signature(m, c, d, &self.0.signature_verification_algorithms)
    }
    fn verify_tls13_signature(&self, m: &[u8], c: &CertificateDer<'_>, d: &DigitallySignedStruct) -> Result<HandshakeSignatureValid, rustls::Error> {
        verify_tls13_signature(m, c, d, &self.0.signature_verification_algorithms)
    }
    fn supported_verify_schemes(&self) -> Vec<SignatureScheme> {
        self.0.signature_verification_algorithms.supported_schemes()
    }
}

fn tls_connector() -> &'static TlsConnector {
    static C: OnceLock<TlsConnector> = OnceLock::new();
    C.get_or_init(|| {
        let p = Arc::new(rustls::crypto::ring::default_provider());
        let cfg = ClientConfig::builder_with_provider(p.clone())
            .with_safe_default_protocol_versions()
            .unwrap()
            .dangerous()
            .with_custom_certificate_verifier(Arc::new(NoVerify(p)))
            .with_no_client_auth();
        TlsConnector::from(Arc::new(cfg))
    })
}

#[derive(Debug, Deserialize)]
#[serde(default)]
pub struct Config {
    pub ha_addr: String,
    /// HA serves TLS itself (`http: ssl_certificate`): wrap the upstream connection in TLS (cert unverified).
    pub ha_tls: bool,
    pub secret_key_path: PathBuf,
    pub allowlist_path: PathBuf,
    pub relay: RelayConfig,
    pub name: String,
    /// Pairing code lifetime in seconds.
    pub pair_ttl: u64,
    /// Fixed UDP port for the iroh socket (0 = random). Set it and open it in the firewall so phones can
    /// holepunch a direct path; with a random port behind a stateful firewall direct paths are flaky.
    pub bind_port: u16,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            ha_addr: "127.0.0.1:8123".into(),
            ha_tls: false,
            secret_key_path: "ha-iroh-bridge.key".into(),
            allowlist_path: "allowlist.toml".into(),
            relay: RelayConfig::default(),
            name: "Home Assistant".into(),
            pair_ttl: 600,
            bind_port: 0,
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
    /// Open tunnel connections per peer, so `revoke` can cut them.
    live: Mutex<HashMap<EndpointId, Vec<Connection>>>,
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
            live: Default::default(),
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
        // track before the allowlist check: a concurrent revoke then either rejects or kills us
        self.0
            .live
            .lock()
            .unwrap()
            .entry(peer)
            .or_default()
            .push(conn.clone());
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
            self.untrack(&conn);
            return Ok(());
        };
        info!(%peer, device, "connected");
        let (opened, closed) = (Arc::new(AtomicU64::new(0)), Arc::new(AtomicU64::new(0)));
        while let Ok((send, recv)) = conn.accept_bi().await {
            let n = opened.fetch_add(1, Relaxed) + 1;
            info!(%peer, device, opened = n, closed = closed.load(Relaxed), "stream open");
            let (ha, tls, device, closed) = (cfg.ha_addr.clone(), cfg.ha_tls, device.clone(), closed.clone());
            tokio::spawn(async move {
                // copy_bidirectional shuts down each writer at the other side's EOF
                // (SendStream shutdown == finish()), so half-close propagates both ways.
                // With ha_tls the shutdown sends close_notify, which asyncio treats as a full
                // close: a client that half-closes before reading the reply loses it (browsers don't).
                let r = async {
                    let mut tcp = TcpStream::connect(&ha).await?;
                    let mut io = join(recv, send);
                    if tls {
                        let host = ha.rsplit_once(':').map_or(&*ha, |h| h.0).trim_matches(['[', ']']).to_owned();
                        let name = ServerName::try_from(host).map_err(std::io::Error::other)?;
                        copy_bidirectional(&mut io, &mut tls_connector().connect(name, tcp).await?).await
                    } else {
                        copy_bidirectional(&mut io, &mut tcp).await
                    }
                }
                .await;
                let c = closed.fetch_add(1, Relaxed) + 1;
                match r {
                    Ok((up, down)) => info!(%peer, device, closed = c, up, down, "stream closed"),
                    Err(e) => warn!(%peer, device, closed = c, "stream error: {e}"),
                }
            });
        }
        self.untrack(&conn);
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

    fn untrack(&self, conn: &Connection) {
        let mut live = self.0.live.lock().unwrap();
        if let Some(v) = live.get_mut(&conn.remote_id()) {
            v.retain(|c| c.stable_id() != conn.stable_id());
            if v.is_empty() {
                live.remove(&conn.remote_id());
            }
        }
    }

    pub fn list(&self) -> Vec<Device> {
        self.0.allowlist.lock().unwrap().devices.clone()
    }

    /// `false` = no such device. Otherwise removes it, persists, and closes its live connections.
    pub fn revoke(&self, id: &str) -> anyhow::Result<bool> {
        let Ok(peer) = id.parse::<EndpointId>() else {
            return Ok(false);
        };
        if !self.list().iter().any(|d| d.endpoint_id == id) {
            return Ok(false);
        }
        self.update(|d| d.retain(|x| x.endpoint_id != id))?;
        let conns = self
            .0
            .live
            .lock()
            .unwrap()
            .remove(&peer)
            .unwrap_or_default();
        for c in conns {
            c.close(4u32.into(), b"revoked");
        }
        Ok(true)
    }

    /// Persist first; memory only changes once the file is safely in place.
    fn update<R>(&self, f: impl FnOnce(&mut Vec<Device>) -> R) -> anyhow::Result<R> {
        let mut list = self.0.allowlist.lock().unwrap();
        let mut next = Allowlist {
            devices: list.devices.clone(),
        };
        let r = f(&mut next.devices);
        let path = &self.0.cfg.allowlist_path;
        let tmp = path.with_extension("tmp");
        std::fs::write(&tmp, toml::to_string(&next)?)?;
        std::fs::rename(&tmp, path)?;
        *list = next;
        Ok(r)
    }

    /// Re-pairing the same endpoint replaces its entry.
    fn add_device(&self, d: Device) -> anyhow::Result<()> {
        self.update(|v| {
            v.retain(|x| x.endpoint_id != d.endpoint_id);
            v.push(d);
        })
    }
}
