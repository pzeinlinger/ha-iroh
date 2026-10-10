use std::sync::Arc;

use ha_iroh_bridge::{Bridge, Config};
use ha_iroh_core::{BridgeInfo, Core, RelayConfig};
use ha_iroh_proto::{ALPN_PAIR, ALPN_TCP};
use iroh::{endpoint::presets, Endpoint, EndpointAddr, RelayMode};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;

const RESP: &[u8] = b"HTTP/1.1 401 Unauthorized\r\nContent-Length: 2\r\n\r\n{}";

/// Plain OS thread: unlike `spawn_blocking`, it has no tokio context (as on Android).
async fn blocking<T: Send + 'static>(f: impl FnOnce() -> T + Send + 'static) -> T {
    let (tx, rx) = tokio::sync::oneshot::channel();
    std::thread::spawn(move || {
        let _ = tx.send(f());
    });
    rx.await.unwrap()
}

/// Fake HA: `echo` = read request to EOF (needs half-close) then reply `got:<req>`;
/// otherwise reply RESP after the first read, without waiting for EOF.
async fn setup(echo: bool) -> (Arc<Core>, BridgeInfo) {
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

#[tokio::test(flavor = "multi_thread")]
async fn read_until_eof_without_half_close() {
    let (core, info) = setup(false).await;
    blocking(move || {
        let t = core.connect(info).unwrap();
        let s = t.open_stream().unwrap();
        s.write(b"GET / HTTP/1.1\r\n\r\n".to_vec()).unwrap();
        let mut out = vec![];
        loop {
            let b = s.read(1024, 0).unwrap();
            if b.is_empty() {
                break; // EOF: fake HA closed after replying
            }
            out.extend(b);
        }
        assert_eq!(out, RESP);
        t.disconnect();
    })
    .await;
}

#[tokio::test(flavor = "multi_thread")]
async fn shutdown_write_propagates() {
    let (core, info) = setup(true).await;
    blocking(move || {
        let t = core.connect(info).unwrap();
        let s = t.open_stream().unwrap();
        s.write(b"hello".to_vec()).unwrap();
        s.shutdown_write().unwrap();
        let mut out = vec![];
        loop {
            let b = s.read(2, 0).unwrap(); // small max: multiple reads
            if b.is_empty() {
                break;
            }
            out.extend(b);
        }
        assert_eq!(out, b"got:hello");
    })
    .await;
}

#[test]
fn key_is_stable_and_wipe_changes_it() {
    let dir = std::env::temp_dir().join(format!(
        "ha-iroh-core-{}",
        iroh::SecretKey::generate().public()
    ));
    std::fs::create_dir_all(&dir).unwrap();
    let d = || dir.to_str().unwrap().to_string();
    let a = Core::new(d(), RelayConfig::Custom { urls: vec![] }).unwrap();
    let id = a.endpoint_id();
    drop(a);
    assert_eq!(
        Core::new(d(), RelayConfig::Custom { urls: vec![] })
            .unwrap()
            .endpoint_id(),
        id
    );
    let b = Core::new(d(), RelayConfig::Custom { urls: vec![] }).unwrap();
    b.wipe_key();
    assert_ne!(
        Core::new(d(), RelayConfig::Custom { urls: vec![] })
            .unwrap()
            .endpoint_id(),
        id
    );
}

/// Fake HA that never answers: idle stream.
#[tokio::test(flavor = "multi_thread")]
async fn read_timeout_and_disconnect_unblocks() {
    let (core, info) = setup(true).await; // echo mode waits for EOF, never replies
    blocking(move || {
        let t = core.connect(info).unwrap();
        let s = t.open_stream().unwrap();
        s.write(b"x".to_vec()).unwrap();
        assert!(matches!(
            s.read(16, 200),
            Err(ha_iroh_core::CoreError::Timeout)
        ));

        let s2 = s.clone();
        let h = std::thread::spawn(move || s2.read(16, 0));
        std::thread::sleep(std::time::Duration::from_millis(200));
        let start = std::time::Instant::now();
        s.disconnect();
        assert!(h.join().unwrap().is_err());
        assert!(start.elapsed() < std::time::Duration::from_secs(1));
    })
    .await;
}
