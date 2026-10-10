mod common;
use std::time::Duration;

use common::{local_endpoint, tempdir};
use ha_iroh_bridge::{Bridge, Config};
use ha_iroh_proto::{read_frame, write_frame, PairRequest, PairResponse, ALPN_PAIR, ALPN_TCP};
use iroh::endpoint::ConnectionError;
use iroh::{Endpoint, EndpointAddr};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;

struct Fixture {
    bridge: Bridge,
    addr: EndpointAddr,
    allowlist: std::path::PathBuf,
}

async fn fixture(name: &str) -> Fixture {
    let ha = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let ha_addr = ha.local_addr().unwrap().to_string();
    tokio::spawn(async move {
        loop {
            let (mut s, _) = ha.accept().await.unwrap();
            tokio::spawn(async move {
                let mut buf = Vec::new();
                let _ = s.read_to_end(&mut buf).await;
                let _ = s.write_all(b"pong").await;
            });
        }
    });
    let allowlist = tempdir(name).join("allowlist.toml");
    let cfg = Config {
        ha_addr,
        allowlist_path: allowlist.clone(),
        ..Config::default()
    };
    let ep = local_endpoint(vec![ALPN_PAIR.to_vec(), ALPN_TCP.to_vec()]).await;
    let port = ep.bound_sockets()[0].port();
    let addr = EndpointAddr::new(ep.id()).with_ip_addr(([127, 0, 0, 1], port).into());
    let (bridge, _events) = Bridge::new(cfg, ep).unwrap();
    tokio::spawn(bridge.clone().serve());
    Fixture {
        bridge,
        addr,
        allowlist,
    }
}

async fn pair(f: &Fixture, client: &Endpoint) {
    let code = f.bridge.mint_code().code;
    let conn = client.connect(f.addr.clone(), ALPN_PAIR).await.unwrap();
    let (mut send, mut recv) = conn.open_bi().await.unwrap();
    let req = PairRequest {
        code,
        device_name: "Pixel".into(),
    };
    write_frame(&mut send, &req).await.unwrap();
    let r: PairResponse = read_frame(&mut recv).await.unwrap();
    assert!(r.ok);
}

#[tokio::test]
async fn list_returns_paired_device() {
    let f = fixture("list").await;
    assert!(f.bridge.list().is_empty());
    let client = local_endpoint(vec![]).await;
    pair(&f, &client).await;
    let l = f.bridge.list();
    assert_eq!(l.len(), 1);
    assert_eq!(l[0].endpoint_id, client.id().to_string());
    assert_eq!(l[0].device_name, "Pixel");
    assert!(!l[0].added_at.is_empty());
}

#[tokio::test]
async fn revoke_cuts_live_connection_and_refuses_new() {
    let f = fixture("revoke").await;
    let client = local_endpoint(vec![]).await;
    pair(&f, &client).await;

    let conn = client.connect(f.addr.clone(), ALPN_TCP).await.unwrap();
    let (mut send, mut recv) = conn.open_bi().await.unwrap();
    send.write_all(b"ping").await.unwrap(); // mid-request: not finished
    tokio::time::sleep(Duration::from_millis(200)).await; // let the bridge accept the stream

    let id = client.id().to_string();
    assert!(f.bridge.revoke(&id).unwrap());

    let closed = tokio::time::timeout(Duration::from_secs(5), conn.closed())
        .await
        .unwrap();
    assert!(
        matches!(&closed, ConnectionError::ApplicationClosed(c) if c.error_code == 4u32.into()),
        "{closed:?}"
    );
    assert!(recv.read_to_end(64).await.is_err());

    // new connections are refused (close code 1)
    let c = client.connect(f.addr.clone(), ALPN_TCP).await.unwrap();
    let closed = tokio::time::timeout(Duration::from_secs(5), c.closed())
        .await
        .unwrap();
    assert!(
        matches!(&closed, ConnectionError::ApplicationClosed(c) if c.error_code == 1u32.into()),
        "{closed:?}"
    );

    assert!(!std::fs::read_to_string(&f.allowlist).unwrap().contains(&id));
    assert!(f.bridge.list().is_empty());
}

#[tokio::test]
async fn revoke_unknown_is_false() {
    let f = fixture("revoke-unknown").await;
    assert!(!f.bridge.revoke("nope").unwrap());
}
