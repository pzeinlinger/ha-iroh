#![allow(dead_code)] // each test binary uses a subset
use std::sync::Arc;

use ha_iroh_bridge::{Bridge, Config};
use ha_iroh_core::{BridgeInfo, Core, RelayConfig};
use ha_iroh_proto::{ALPN_PAIR, ALPN_TCP};
use iroh::{endpoint::presets, Endpoint, EndpointAddr, RelayMode};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;

pub const RESP: &[u8] = b"HTTP/1.1 401 Unauthorized\r\nContent-Length: 2\r\n\r\n{}";

/// Plain OS thread: unlike `spawn_blocking`, it has no tokio context (as on Android).
pub async fn blocking<T: Send + 'static>(f: impl FnOnce() -> T + Send + 'static) -> T {
    let (tx, rx) = tokio::sync::oneshot::channel();
    std::thread::spawn(move || {
        let _ = tx.send(f());
    });
    rx.await.unwrap()
}

/// Fake HA: `echo` = read request to EOF (needs half-close) then reply `got:<req>`;
/// otherwise reply RESP after the first read, without waiting for EOF.
pub async fn setup(echo: bool) -> (Arc<Core>, BridgeInfo) {
    let ha = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let ha_addr = ha.local_addr().unwrap().to_string();
    tokio::spawn(async move {
        loop {
            let (mut s, _) = ha.accept().await.unwrap();
            tokio::spawn(async move {
                if echo {
                    let mut buf = Vec::new();
                    s.read_to_end(&mut buf).await.unwrap();
                    s.write_all(&[b"got:", &buf[..]].concat()).await.unwrap();
                } else {
                    let _n = s.read(&mut [0; 1024]).await.unwrap();
                    s.write_all(RESP).await.unwrap();
                }
            });
        }
    });
    let dir = std::env::temp_dir().join(format!(
        "ha-iroh-core-{}",
        iroh::SecretKey::generate().public()
    ));
    std::fs::create_dir_all(&dir).unwrap();
    let ep = Endpoint::builder(presets::Minimal)
        .relay_mode(RelayMode::Disabled)
        .alpns(vec![ALPN_PAIR.to_vec(), ALPN_TCP.to_vec()])
        .bind()
        .await
        .unwrap();
    let port = ep.bound_sockets()[0].port();
    let addr = EndpointAddr::new(ep.id()).with_ip_addr(([127, 0, 0, 1], port).into());
    let cfg = Config {
        ha_addr,
        allowlist_path: dir.join("allowlist.toml"),
        ..Config::default()
    };
    let (bridge, rx) = Bridge::new(cfg, ep).unwrap();
    let mut ticket = bridge.mint_code();
    ticket.addr = addr;
    ticket.relay = ha_iroh_proto::RelayConfig::Urls(vec![]);
    tokio::spawn(async move {
        let _rx = rx;
        bridge.serve().await
    });
    // Core is blocking and owns its own runtime: drive it off the test runtime.
    blocking(move || {
        let core = Core::new(
            dir.to_str().unwrap().into(),
            RelayConfig::Custom { urls: vec![] },
        )
        .unwrap();
        let info = core.pair(ticket.to_uri(), "test".into()).unwrap();
        (core, info)
    })
    .await
}

