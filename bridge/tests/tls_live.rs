// Throwaway: live check against a TLS-terminated HA at HA_TLS_ADDR (default 127.0.0.1:8443).
mod common;
use common::{local_endpoint, tempdir};
use ha_iroh_bridge::{Bridge, Config};
use ha_iroh_proto::ALPN_TCP;
use iroh::EndpointAddr;

async fn run(tls: bool) -> Vec<u8> {
    let _ = tracing_subscriber::fmt().with_env_filter("ha_iroh_bridge=debug,rustls=debug").try_init();
    let dir = tempdir(if tls { "tls" } else { "plain" });
    let client = local_endpoint(vec![]).await;
    std::fs::write(dir.join("allowlist.toml"), format!("[[devices]]\nendpoint_id = \"{}\"\ndevice_name = \"t\"\nadded_at = \"now\"\n", client.id())).unwrap();
    let cfg = Config {
        ha_addr: std::env::var("HA_TLS_ADDR").unwrap_or("127.0.0.1:8443".into()),
        ha_tls: tls,
        allowlist_path: dir.join("allowlist.toml"),
        ..Config::default()
    };
    let ep = local_endpoint(vec![ALPN_TCP.to_vec()]).await;
    let addr = EndpointAddr::new(ep.id()).with_ip_addr(([127, 0, 0, 1], ep.bound_sockets()[0].port()).into());
    let (b, rx) = Bridge::new(cfg, ep).unwrap();
    tokio::spawn(async move { let _rx = rx; b.serve().await });
    let conn = client.connect(addr, ALPN_TCP).await.unwrap();
    let (mut send, mut recv) = conn.open_bi().await.unwrap();
    send.write_all(b"GET /auth/authorize?client_id=x&redirect_uri=x HTTP/1.1\r\nHost: ha.example.test\r\nConnection: close\r\n\r\n").await.unwrap();
    send.finish().unwrap();
    recv.read_to_end(1 << 20).await.unwrap()
}

#[tokio::test]
#[ignore]
// Half-close before reading the reply: close_notify makes asyncio drop the connection (documented in lib.rs).
async fn tls_hop_half_close_first_loses_reply() {
    assert!(run(true).await.is_empty());
}

#[tokio::test]
#[ignore]
async fn plain_hop_against_tls_ha_is_empty() {
    let out = run(false).await;
    println!("plain: {:?}", String::from_utf8_lossy(&out[..out.len().min(120)]));
    assert!(out.is_empty());
}

#[tokio::test]
#[ignore]
async fn tls_hop_without_half_close() {
    let _ = tracing_subscriber::fmt().with_env_filter("ha_iroh_bridge=info").try_init();
    let dir = tempdir("tls2");
    let client = local_endpoint(vec![]).await;
    std::fs::write(dir.join("allowlist.toml"), format!("[[devices]]\nendpoint_id = \"{}\"\ndevice_name = \"t\"\nadded_at = \"now\"\n", client.id())).unwrap();
    let cfg = Config { ha_addr: "127.0.0.1:8443".into(), ha_tls: true, allowlist_path: dir.join("allowlist.toml"), ..Config::default() };
    let ep = local_endpoint(vec![ALPN_TCP.to_vec()]).await;
    let addr = EndpointAddr::new(ep.id()).with_ip_addr(([127, 0, 0, 1], ep.bound_sockets()[0].port()).into());
    let (b, rx) = Bridge::new(cfg, ep).unwrap();
    tokio::spawn(async move { let _rx = rx; b.serve().await });
    let conn = client.connect(addr, ALPN_TCP).await.unwrap();
    let (mut send, mut recv) = conn.open_bi().await.unwrap();
    send.write_all(b"GET /api/ HTTP/1.1\r\nHost: ha.example.test\r\n\r\n").await.unwrap();
    let mut buf = vec![0u8; 4096];
    let n = tokio::time::timeout(std::time::Duration::from_secs(5), recv.read(&mut buf)).await.unwrap().unwrap().unwrap();
    let head = String::from_utf8_lossy(&buf[..n]).to_string();
    println!("no-half-close: {:?}", &head[..head.len().min(100)]);
    assert!(head.starts_with("HTTP/1.1 401"), "{head}");
    // now half-close; HA should finish() back eventually
    send.finish().unwrap();
    let rest = tokio::time::timeout(std::time::Duration::from_secs(5), recv.read_to_end(1 << 16)).await;
    println!("after finish: {rest:?}");
}
