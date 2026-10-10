mod common;
use common::*;
use ha_iroh_core::{Core, RelayConfig};

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
