mod common;
use common::*;
use ha_iroh_core::proxy::host_ok;
use std::io::{Read, Write};
use std::net::TcpStream;

#[test]
fn host_gate() {
    let req = |h: &str| format!("GET / HTTP/1.1\r\n{h}\r\nAccept: */*\r\n\r\n").into_bytes();
    assert!(host_ok(&req("Host: tok.localhost:123"), "tok", 123));
    assert!(host_ok(&req("hOsT:   TOK.localhost:123 "), "tok", 123));
    assert!(!host_ok(&req("Host: tok.localhost"), "tok", 123)); // port is required
    assert!(!host_ok(&req("Host: tok.localhost:123\r\nHost: tok.localhost:123"), "tok", 123)); // duplicate
    assert!(!host_ok(&req("Host: evil"), "tok", 123));
    assert!(!host_ok(&req("Host: tok.localhost:124"), "tok", 123));
    assert!(!host_ok(&req("Host: xtok.localhost:123"), "tok", 123));
    assert!(!host_ok(&req("X-Other: a"), "tok", 123));
    assert!(!host_ok(b"GET / HTTP/1.1\r\nHost: tok.localhost:123\r\n", "tok", 123)); // head incomplete
}

fn http(port: u16, host: &str) -> String {
    let mut s = TcpStream::connect(("127.0.0.1", port)).unwrap();
    s.write_all(format!("GET / HTTP/1.1\r\nHost: {host}\r\n\r\n").as_bytes()).unwrap();
    let mut out = String::new();
    let _ = s.read_to_string(&mut out);
    out
}

#[tokio::test(flavor = "multi_thread")]
async fn proxy_gates_host_and_stops() {
    let (core, info) = setup(false).await;
    blocking(move || {
        let t = core.connect(info).unwrap();
        let p = t.start_loopback_proxy().unwrap();
        let (port, tok) = (p.port(), p.token());
        assert_eq!(tok.len(), 26);
        assert!(http(port, "evil").starts_with("HTTP/1.1 403"));
        // oversized head without CRLFCRLF -> 403 (even though the Host header is right)
        let mut s = TcpStream::connect(("127.0.0.1", port)).unwrap();
        s.write_all(format!("GET / HTTP/1.1\r\nHost: {tok}.localhost:{port}\r\nX: {}", "a".repeat(9000)).as_bytes()).unwrap();
        let mut out = String::new();
        let _ = s.read_to_string(&mut out);
        assert!(out.starts_with("HTTP/1.1 403"), "{out:?}");
        assert_eq!(http(port, &format!("{tok}.localhost:{port}")), String::from_utf8(RESP.to_vec()).unwrap());
        p.stop();
        let gone = (0..50).any(|_| {
            std::thread::sleep(std::time::Duration::from_millis(100));
            TcpStream::connect(("127.0.0.1", port)).is_err()
        });
        assert!(gone, "port still open after stop");
        t.disconnect();
    })
    .await;
}
