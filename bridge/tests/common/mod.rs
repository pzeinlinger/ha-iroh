#![allow(dead_code)]
use iroh::{endpoint::presets, Endpoint, RelayMode, SecretKey};

pub async fn local_endpoint(alpns: Vec<Vec<u8>>) -> Endpoint {
    Endpoint::builder(presets::Minimal)
        .relay_mode(RelayMode::Disabled)
        .alpns(alpns)
        .bind()
        .await
        .unwrap()
}

pub fn tempdir(name: &str) -> std::path::PathBuf {
    let d = std::env::temp_dir().join(format!(
        "ha-iroh-test-{name}-{}",
        SecretKey::generate().public()
    ));
    std::fs::create_dir_all(&d).unwrap();
    d
}
