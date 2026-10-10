use iroh::EndpointAddr;
use serde::{Deserialize, Serialize};

pub const ALPN_PAIR: &[u8] = b"ha-iroh/pair/0";
pub const ALPN_TCP: &[u8] = b"ha-iroh/tcp/0";

/// `"n0"` or a list of relay URLs.
#[derive(Debug, Clone, PartialEq, Serialize, Deserialize)]
#[serde(untagged)]
pub enum RelayConfig {
    Named(String),
    Urls(Vec<String>),
}

impl Default for RelayConfig {
    fn default() -> Self {
        Self::Named("n0".into())
    }
}

/// Payload of the QR URI `ha-iroh://pair?t=<base64url(JSON)>`.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairTicket {
    pub v: u32,
    pub addr: EndpointAddr,
    pub relay: RelayConfig,
    pub code: String,
    pub name: String,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairRequest {
    pub code: String,
    pub device_name: String,
}

/// `{"ok":true,"bridge_name":..}` or `{"ok":false,"error":"invalid_code|expired"}`.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct PairResponse {
    pub ok: bool,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub bridge_name: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub error: Option<String>,
}

/// Load a raw 32-byte secret key, or generate one and write it with mode 0600.
pub fn load_or_create_key(path: &std::path::Path) -> std::io::Result<iroh::SecretKey> {
    use std::io::{Error, ErrorKind, Write};
    use std::os::unix::fs::OpenOptionsExt;
    match std::fs::read(path) {
        Ok(b) => {
            let b: [u8; 32] = b.as_slice().try_into().map_err(|_| Error::new(ErrorKind::InvalidData, "secret key file must be 32 bytes"))?;
            Ok(iroh::SecretKey::from_bytes(&b))
        }
        Err(e) if e.kind() == ErrorKind::NotFound => {
            let key = iroh::SecretKey::generate();
            std::fs::OpenOptions::new().write(true).create_new(true).mode(0o600).open(path)?.write_all(&key.to_bytes())?;
            Ok(key)
        }
        Err(e) => Err(e),
    }
}
