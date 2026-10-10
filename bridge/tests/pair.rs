mod common;
use std::time::Duration;

use common::{local_endpoint, tempdir};
use ha_iroh_bridge::{Bridge, Config, Event};
use ha_iroh_proto::{
    read_frame, write_frame, PairRequest, PairResponse, PairTicket, ALPN_PAIR, ALPN_TCP,
};
use iroh::{Endpoint, EndpointAddr};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;
use tokio::sync::mpsc::Receiver;

struct Fixture {
    bridge: Bridge,
    events: Receiver<Event>,
    addr: EndpointAddr,
    allowlist: std::path::PathBuf,
}

async fn fixture(name: &str, pair_ttl: u64) -> Fixture {
    let dir = tempdir(name);
    let ha = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let ha_addr = ha.local_addr().unwrap().to_string();
    tokio::spawn(async move {
        loop {
            let (mut s, _) = ha.accept().await.unwrap();
            tokio::spawn(async move {
                let mut buf = Vec::new();
                s.read_to_end(&mut buf).await.unwrap();
                s.write_all(b"pong").await.unwrap();
            });
        }
    });
    let allowlist = dir.join("allowlist.toml");
    let cfg = Config {
        ha_addr: ha_addr.clone(),
        allowlist_path: allowlist.clone(),
        pair_ttl,
        name: "Test HA".into(),
        ..Config::default()
    };
    let ep = local_endpoint(vec![ALPN_PAIR.to_vec(), ALPN_TCP.to_vec()]).await;
    let port = ep.bound_sockets()[0].port();
    let addr = EndpointAddr::new(ep.id()).with_ip_addr(([127, 0, 0, 1], port).into());
    let (bridge, events) = Bridge::new(cfg, ep).unwrap();
    tokio::spawn(bridge.clone().serve());
    Fixture {
        bridge,
        events,
        addr,
        allowlist,
    }
}

async fn pair(client: &Endpoint, addr: &EndpointAddr, code: &str) -> anyhow::Result<PairResponse> {
    let conn = client.connect(addr.clone(), ALPN_PAIR).await?;
    let (mut send, mut recv) = conn.open_bi().await?;
    write_frame(
        &mut send,
        &PairRequest {
            code: code.into(),
            device_name: "Pixel".into(),
        },
    )
    .await?;
    read_frame(&mut recv).await
}

fn err(r: PairResponse) -> String {
    assert!(!r.ok);
    r.error.unwrap()
}

#[tokio::test]
async fn pair_happy_path_then_tunnel_works() {
    let mut f = fixture("pair-ok", 600).await;
    let client = local_endpoint(vec![]).await;
    let ticket = f.bridge.mint_code();
    assert_eq!(
        PairTicket::from_uri(&ticket.to_uri()).unwrap().code,
        ticket.code
    );

    let r = pair(&client, &f.addr, &ticket.code).await.unwrap();
    assert!(r.ok, "{r:?}");
    assert_eq!(r.bridge_name.as_deref(), Some("Test HA"));
    match f.events.recv().await.unwrap() {
        Event::Paired {
            endpoint_id,
            device_name,
        } => {
            assert_eq!(endpoint_id, client.id().to_string());
            assert_eq!(device_name, "Pixel");
        }
    }
    let disk = std::fs::read_to_string(&f.allowlist).unwrap();
    assert!(
        disk.contains(&client.id().to_string()) && disk.contains("Pixel"),
        "{disk}"
    );

    // re-pairing the same client replaces its entry
    let again = f.bridge.mint_code();
    assert!(pair(&client, &f.addr, &again.code).await.unwrap().ok);
    let disk = std::fs::read_to_string(&f.allowlist).unwrap();
    assert_eq!(disk.matches(&client.id().to_string()).count(), 1, "{disk}");

    // allowlist hot-applied: tunnel works with no restart
    let conn = client.connect(f.addr.clone(), ALPN_TCP).await.unwrap();
    let (mut send, mut recv) = conn.open_bi().await.unwrap();
    send.write_all(b"ping").await.unwrap();
    send.finish().unwrap();
    assert_eq!(recv.read_to_end(64).await.unwrap(), b"pong");
}

#[tokio::test]
async fn code_is_single_use() {
    let f = fixture("pair-reuse", 600).await;
    let client = local_endpoint(vec![]).await;
    let code = f.bridge.mint_code().code;
    assert!(pair(&client, &f.addr, &code).await.unwrap().ok);
    assert_eq!(
        err(pair(&client, &f.addr, &code).await.unwrap()),
        "invalid_code"
    );
}

#[tokio::test]
async fn expired_code() {
    let f = fixture("pair-exp", 0).await;
    let client = local_endpoint(vec![]).await;
    let code = f.bridge.mint_code().code;
    assert_eq!(err(pair(&client, &f.addr, &code).await.unwrap()), "expired");
}

#[tokio::test]
async fn repeated_bad_codes_are_rate_limited() {
    let f = fixture("pair-rl", 600).await;
    let client = local_endpoint(vec![]).await;
    for _ in 0..5 {
        assert_eq!(
            err(pair(&client, &f.addr, "nope").await.unwrap()),
            "invalid_code"
        );
    }
    let code = f.bridge.mint_code().code; // even a valid code is refused now
    let res = tokio::time::timeout(Duration::from_secs(10), pair(&client, &f.addr, &code))
        .await
        .unwrap();
    assert!(res.is_err(), "expected close, got {res:?}");
    let conn = client.connect(f.addr.clone(), ALPN_PAIR).await.unwrap();
    assert!(format!("{:?}", conn.closed().await).contains("rate_limited"));
}
