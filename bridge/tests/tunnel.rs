use ha_iroh_bridge::{serve, Config};
use ha_iroh_proto::ALPN_TCP;
use iroh::{endpoint::presets, Endpoint, EndpointAddr, RelayMode, SecretKey};
use tokio::io::{AsyncReadExt, AsyncWriteExt};
use tokio::net::TcpListener;

const REQ: &[u8] = b"GET /api/ HTTP/1.1\r\nHost: ha.iroh\r\nConnection: close\r\n\r\n";
const RESP: &[u8] = b"HTTP/1.1 401 Unauthorized\r\nContent-Type: application/json\r\nContent-Length: 26\r\n\r\n{\"message\":\"Unauthorized\"}";

async fn local_endpoint(alpns: Vec<Vec<u8>>) -> Endpoint {
    Endpoint::builder(presets::Minimal)
        .relay_mode(RelayMode::Disabled)
        .alpns(alpns)
        .bind()
        .await
        .unwrap()
}

/// Fake HA + bridge (allowlist holds `allowed`), returns bridge addr.
async fn setup(allowed: Option<&Endpoint>, dir: &std::path::Path) -> EndpointAddr {
    let ha = TcpListener::bind("127.0.0.1:0").await.unwrap();
    let ha_addr = ha.local_addr().unwrap().to_string();
    tokio::spawn(async move {
        loop {
            let (mut s, _) = ha.accept().await.unwrap();
            tokio::spawn(async move {
                // Read request until the peer half-closes.
                let mut buf = Vec::new();
                s.read_to_end(&mut buf).await.unwrap();
                assert_eq!(buf, REQ);
                s.write_all(RESP).await.unwrap();
            });
        }
    });

    let allowlist_path = dir.join("allowlist.toml");
    if let Some(c) = allowed {
        std::fs::write(
            &allowlist_path,
            format!(
                "[[devices]]\nendpoint_id = \"{}\"\ndevice_name = \"test\"\nadded_at = \"now\"\n",
                c.id()
            ),
        )
        .unwrap();
    }
    let cfg = Config { ha_addr, allowlist_path, ..Config::default() };
    let bridge = local_endpoint(vec![ALPN_TCP.to_vec()]).await;
    let port = bridge.bound_sockets()[0].port();
    let addr = EndpointAddr::new(bridge.id()).with_ip_addr(([127, 0, 0, 1], port).into());
    tokio::spawn(serve(cfg, bridge));
    addr
}

#[tokio::test]
async fn allowlisted_client_gets_full_response() {
    let dir = tempdir("ok");
    let client = local_endpoint(vec![]).await;
    let addr = setup(Some(&client), &dir).await;

    let conn = client.connect(addr, ALPN_TCP).await.unwrap();
    let (mut send, mut recv) = conn.open_bi().await.unwrap();
    send.write_all(REQ).await.unwrap();
    send.finish().unwrap(); // half-close must reach the fake HA, else it never answers
    let out = recv.read_to_end(1 << 16).await.unwrap(); // and TCP close must come back as finish()
    assert_eq!(out, RESP);
}

#[tokio::test]
async fn unlisted_client_is_closed() {
    let dir = tempdir("deny");
    let other = local_endpoint(vec![]).await;
    let addr = setup(None, &dir).await;

    let conn = other.connect(addr, ALPN_TCP).await.unwrap();
    let res = async {
        let (mut send, mut recv) = conn.open_bi().await?;
        send.write_all(REQ).await?;
        send.finish()?;
        anyhow::Ok(recv.read_to_end(1 << 16).await?)
    }
    .await;
    assert!(res.is_err(), "expected close, got {res:?}");
    let reason = conn.closed().await;
    assert!(format!("{reason:?}").contains("not allowed"), "{reason:?}");
}

fn tempdir(name: &str) -> std::path::PathBuf {
    let d = std::env::temp_dir().join(format!("ha-iroh-test-{name}-{}", SecretKey::generate().public()));
    std::fs::create_dir_all(&d).unwrap();
    d
}
