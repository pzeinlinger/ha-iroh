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

impl RelayConfig {
    /// `None` = iroh's default (n0) relays.
    pub fn relay_mode(&self) -> anyhow::Result<Option<iroh::RelayMode>> {
        match self {
            Self::Named(n) if n == "n0" => Ok(None),
            Self::Named(n) => anyhow::bail!("relay must be \"n0\" or a list of URLs, got {n:?}"),
            Self::Urls(u) => Ok(Some(iroh::RelayMode::custom(
                u.iter()
                    .map(|u| u.parse::<iroh::RelayUrl>())
                    .collect::<Result<Vec<_>, _>>()?,
            ))),
        }
    }
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

const URI_PREFIX: &str = "ha-iroh://pair?t=";

impl PairTicket {
    pub fn to_uri(&self) -> String {
        format!(
            "{URI_PREFIX}{}",
            data_encoding::BASE64URL_NOPAD
                .encode(&serde_json::to_vec(self).expect("ticket serializes"))
        )
    }

    pub fn from_uri(uri: &str) -> anyhow::Result<Self> {
        let t = uri
            .trim()
            .strip_prefix(URI_PREFIX)
            .ok_or_else(|| anyhow::anyhow!("not a ha-iroh pairing URI"))?;
        Ok(serde_json::from_slice(
            &data_encoding::BASE64URL_NOPAD.decode(t.as_bytes())?,
        )?)
    }
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
            let b: [u8; 32] = b.as_slice().try_into().map_err(|_| {
                Error::new(ErrorKind::InvalidData, "secret key file must be 32 bytes")
            })?;
            Ok(iroh::SecretKey::from_bytes(&b))
        }
        Err(e) if e.kind() == ErrorKind::NotFound => {
            let key = iroh::SecretKey::generate();
            std::fs::OpenOptions::new()
                .write(true)
                .create_new(true)
                .mode(0o600)
                .open(path)?
                .write_all(&key.to_bytes())?;
            Ok(key)
        }
        Err(e) => Err(e),
    }
}

const MAX_FRAME: usize = 64 << 10;

/// u32 BE length + JSON.
pub async fn write_frame<W: tokio::io::AsyncWrite + Unpin, T: Serialize>(
    w: &mut W,
    v: &T,
) -> anyhow::Result<()> {
    use tokio::io::AsyncWriteExt;
    let b = serde_json::to_vec(v)?;
    w.write_all(&(b.len() as u32).to_be_bytes()).await?;
    w.write_all(&b).await?;
    Ok(())
}

/// Rejects frames over 64 KiB before allocating.
pub async fn read_frame<R: tokio::io::AsyncRead + Unpin, T: serde::de::DeserializeOwned>(
    r: &mut R,
) -> anyhow::Result<T> {
    use tokio::io::AsyncReadExt;
    let n = r.read_u32().await? as usize;
    anyhow::ensure!(n <= MAX_FRAME, "frame too large: {n}");
    let mut b = vec![0; n];
    r.read_exact(&mut b).await?;
    Ok(serde_json::from_slice(&b)?)
}
