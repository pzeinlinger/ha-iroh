use std::path::PathBuf;
use std::sync::atomic::{AtomicU64, Ordering::Relaxed};
use std::sync::Arc;

use ha_iroh_proto::{RelayConfig, ALPN_TCP};
use iroh::endpoint::Incoming;
use iroh::Endpoint;
use serde::Deserialize;
use tokio::io::{join, copy_bidirectional};
use tokio::net::TcpStream;
use tracing::{error, info, warn};

#[derive(Debug, Deserialize)]
#[serde(default)]
pub struct Config {
    pub ha_addr: String,
    pub secret_key_path: PathBuf,
    pub allowlist_path: PathBuf,
    pub relay: RelayConfig,
    pub name: String,
}

impl Default for Config {
    fn default() -> Self {
        Self {
            ha_addr: "127.0.0.1:8123".into(),
            secret_key_path: "ha-iroh-bridge.key".into(),
            allowlist_path: "allowlist.toml".into(),
            relay: RelayConfig::default(),
            name: "Home Assistant".into(),
        }
    }
}

#[derive(Deserialize, Default)]
struct Allowlist {
    #[serde(default)]
    devices: Vec<Device>,
}

#[derive(Deserialize)]
struct Device {
    endpoint_id: String,
    device_name: String,
    #[allow(dead_code)]
    added_at: String,
}

/// Accept loop. Returns when the endpoint is closed.
pub async fn serve(cfg: Config, ep: Endpoint) {
    let cfg = Arc::new(cfg);
    while let Some(incoming) = ep.accept().await {
        let cfg = cfg.clone();
        tokio::spawn(async move {
            if let Err(e) = handle(incoming, &cfg).await {
                warn!("connection failed: {e:#}");
            }
        });
    }
}

async fn handle(incoming: Incoming, cfg: &Config) -> anyhow::Result<()> {
    let conn = incoming.await?;
    let peer = conn.remote_id();
    if conn.alpn() != ALPN_TCP {
        conn.close(2u32.into(), b"pairing not implemented"); // ponytail: ticket 03 handles ALPN_PAIR
        return Ok(());
    }
    // ponytail: re-read on every connection = hot reload; fine for a handful of devices
    let list: Allowlist = match tokio::fs::read_to_string(&cfg.allowlist_path).await {
        Ok(s) => toml::from_str(&s).unwrap_or_else(|e| {
            error!("allowlist parse failed, denying all: {e}");
            Allowlist::default()
        }),
        Err(e) if e.kind() == std::io::ErrorKind::NotFound => Allowlist::default(),
        Err(e) => {
            error!("allowlist read failed, denying all: {e}");
            Allowlist::default()
        }
    };
    let id = peer.to_string();
    let Some(dev) = list.devices.iter().find(|d| d.endpoint_id == id) else {
        warn!(%peer, "rejected: not in allowlist");
        conn.close(1u32.into(), b"not allowed");
        return Ok(());
    };
    let device = dev.device_name.clone();
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
