//! Blocking uniffi API over iroh (SPEC §7). One tokio runtime for the whole process.
use std::{
    path::PathBuf,
    sync::{Arc, LazyLock},
    time::Duration,
};

use ha_iroh_proto::{
    load_or_create_key, read_frame, write_frame, PairRequest, PairResponse, PairTicket, ALPN_PAIR,
    ALPN_TCP,
};
use iroh::{endpoint::presets, Endpoint, EndpointAddr};
use serde::{Deserialize, Serialize};
use tokio::{io::AsyncWriteExt, runtime::Runtime, sync::Mutex};

uniffi::setup_scaffolding!();
pub mod proxy;
pub use proxy::LoopbackProxy;
use tokio_util::sync::CancellationToken;

static RT: LazyLock<Runtime> = LazyLock::new(|| {
    tokio::runtime::Builder::new_multi_thread()
        .worker_threads(2)
        .enable_all()
        .build()
        .expect("tokio runtime")
});

const CONNECT_TIMEOUT: Duration = Duration::from_secs(15);

#[derive(Debug, thiserror::Error, uniffi::Error)]
pub enum CoreError {
    #[error("io: {0}")]
    Io(String),
    #[error("denied by bridge")]
    Denied,
    #[error("timeout")]
    Timeout,
    #[error("closed")]
    Closed,
    #[error("pairing failed: {0}")]
    Pair(String),
}

impl From<std::io::Error> for CoreError {
    fn from(e: std::io::Error) -> Self {
        Self::Io(e.to_string())
    }
}

fn io(e: impl std::fmt::Display) -> CoreError {
    CoreError::Io(format!("{e:#}"))
}

#[derive(Debug, Clone, uniffi::Enum)]
pub enum RelayConfig {
    N0,
    Custom { urls: Vec<String> },
}

impl From<RelayConfig> for ha_iroh_proto::RelayConfig {
    fn from(r: RelayConfig) -> Self {
        match r {
            RelayConfig::N0 => Self::Named("n0".into()),
            RelayConfig::Custom { urls } => Self::Urls(urls),
        }
    }
}

/// Stored by the app; `info_json` is opaque to Kotlin.
#[derive(Debug, Clone, uniffi::Record)]
pub struct BridgeInfo {
    pub name: String,
    pub info_json: String,
}

#[derive(Serialize, Deserialize)]
struct Info {
    addr: EndpointAddr,
    relay: ha_iroh_proto::RelayConfig,
}

#[derive(uniffi::Object)]
pub struct Core {
    ep: Endpoint,
    key_path: PathBuf,
}

#[uniffi::export]
impl Core {
    /// One Endpoint per app. After `wipe_key` the app must construct a new `Core`.
    #[uniffi::constructor]
    pub fn new(data_dir: String, relay: RelayConfig) -> Result<Arc<Self>, CoreError> {
        let data_dir = PathBuf::from(data_dir);
        init_logging(&data_dir);
        let key_path = data_dir.join("endpoint.key");
        let key = load_or_create_key(&key_path)?;
        let mut b = Endpoint::builder(presets::N0).secret_key(key);
        if let Some(m) = ha_iroh_proto::RelayConfig::from(relay)
            .relay_mode()
            .map_err(io)?
        {
            b = b.relay_mode(m);
        }
        let ep = RT.block_on(b.bind()).map_err(io)?;
        Ok(Arc::new(Self { ep, key_path }))
    }

    pub fn endpoint_id(&self) -> String {
        self.ep.id().to_string()
    }

    /// Unpair: delete the persisted key and close the endpoint. Discard this `Core` afterwards.
    pub fn wipe_key(&self) {
        let _ = std::fs::remove_file(&self.key_path);
        RT.block_on(self.ep.close());
    }

    pub fn pair(&self, ticket_uri: String, device_name: String) -> Result<BridgeInfo, CoreError> {
        let t = PairTicket::from_uri(&ticket_uri).map_err(io)?;
        RT.block_on(async {
            let conn =
                tokio::time::timeout(CONNECT_TIMEOUT, self.ep.connect(t.addr.clone(), ALPN_PAIR))
                    .await
                    .map_err(|_| CoreError::Timeout)?
                    .map_err(io)?;
            let (mut send, mut recv) = conn.open_bi().await.map_err(io)?;
            write_frame(
                &mut send,
                &PairRequest {
                    code: t.code,
                    device_name,
                },
            )
            .await
            .map_err(io)?;
            let resp: PairResponse = read_frame(&mut recv).await.map_err(io)?;
            conn.close(0u32.into(), b"bye");
            if !resp.ok {
                return Err(CoreError::Pair(resp.error.unwrap_or_default()));
            }
            Ok(BridgeInfo {
                name: resp.bridge_name.unwrap_or(t.name),
                info_json: serde_json::to_string(&Info {
                    addr: t.addr,
                    relay: t.relay,
                })
                .map_err(io)?,
            })
        })
    }

    pub fn connect(&self, bridge: BridgeInfo) -> Result<Arc<Tunnel>, CoreError> {
        let info: Info = serde_json::from_str(&bridge.info_json).map_err(io)?;
        // `info.addr` already carries the bridge's relay URL(s) from the ticket and iroh dials the peer via those;
        // the Endpoint's relay mode (set in `new`) only picks this phone's home relay, so `info.relay` needs no handling here.
        RT.block_on(async {
            let conn = tokio::time::timeout(CONNECT_TIMEOUT, self.ep.connect(info.addr, ALPN_TCP))
                .await
                .map_err(|_| CoreError::Timeout)?
                .map_err(io)?;
            Ok(Arc::new(Tunnel { conn, cancel: CancellationToken::new() }))
        })
    }
}

#[derive(uniffi::Object)]
pub struct Tunnel {
    conn: iroh::endpoint::Connection,
    /// Parent of every proxy's token; cancelled by `disconnect`.
    cancel: CancellationToken,
}

#[uniffi::export]
impl Tunnel {
    pub fn open_stream(&self) -> Result<Arc<Stream>, CoreError> {
        let (send, recv) = RT
            .block_on(async { tokio::time::timeout(CONNECT_TIMEOUT, self.conn.open_bi()).await })
            .map_err(|_| CoreError::Timeout)?
            .map_err(map_conn)?;
        Ok(Arc::new(Stream {
            send: send.into(),
            recv: recv.into(),
            closed: CancellationToken::new(),
        }))
    }

    pub fn start_loopback_proxy(&self) -> Result<Arc<LoopbackProxy>, CoreError> {
        proxy::start(self.conn.clone(), self.cancel.child_token())
    }

    pub fn disconnect(&self) {
        self.cancel.cancel();
        self.conn.close(0u32.into(), b"bye");
    }

    /// Human-readable snapshot of the selected network path, e.g. `direct 192.168.1.5:4433 12ms (+1 path)`.
    pub fn conn_info(&self) -> String {
        let paths = self.conn.paths();
        let Some(p) = paths.iter().find(|p| p.is_selected()).or_else(|| paths.iter().next()) else {
            return "no path".into();
        };
        let extra = paths.len().saturating_sub(1);
        // TransportAddr's Display already carries an `ip:` / `relay:` prefix
        let mut s = format!("{} {}ms", p.remote_addr(), p.rtt().as_millis());
        if extra > 0 {
            s.push_str(&format!(" (+{extra} path{})", if extra > 1 { "s" } else { "" }));
        }
        s
    }
}

/// A closed-by-bridge connection means we are not (or no longer) on the allowlist.
fn map_conn(e: iroh::endpoint::ConnectionError) -> CoreError {
    match e {
        iroh::endpoint::ConnectionError::ApplicationClosed(_) => CoreError::Denied,
        e => io(e),
    }
}

#[derive(uniffi::Object)]
pub struct Stream {
    send: Mutex<iroh::endpoint::SendStream>,
    recv: Mutex<iroh::endpoint::RecvStream>,
    /// Cancelled by `disconnect` so it can't deadlock behind a blocked `read`.
    closed: CancellationToken,
}

#[uniffi::export]
impl Stream {
    /// Empty = EOF. `timeout_ms` 0 = none.
    pub fn read(&self, max: u32, timeout_ms: u32) -> Result<Vec<u8>, CoreError> {
        RT.block_on(async {
            let mut buf = vec![0; max as usize];
            let read = async { self.recv.lock().await.read(&mut buf).await.map_err(io) };
            let n = tokio::select! {
                _ = self.closed.cancelled() => return Err(CoreError::Closed),
                r = with_timeout(timeout_ms, read) => r??,
            };
            buf.truncate(n.unwrap_or(0));
            Ok(buf)
        })
    }

    pub fn write(&self, data: Vec<u8>) -> Result<(), CoreError> {
        RT.block_on(async {
            tokio::select! {
                _ = self.closed.cancelled() => Err(CoreError::Closed),
                r = async { self.send.lock().await.write_all(&data).await.map_err(io) } => r,
            }
        })
    }

    pub fn shutdown_write(&self) -> Result<(), CoreError> {
        RT.block_on(async { self.send.lock().await.shutdown().await.map_err(io) })
    }

    /// (Named `disconnect`: uniffi's generated `AutoCloseable.close()` would clash.)
    pub fn disconnect(&self) {
        self.closed.cancel();
        RT.block_on(async {
            let _ = self.send.lock().await.reset(0u32.into());
            let _ = self.recv.lock().await.stop(0u32.into());
        })
    }
}

async fn with_timeout<T>(ms: u32, f: impl std::future::Future<Output = T>) -> Result<T, CoreError> {
    if ms == 0 {
        return Ok(f.await);
    }
    tokio::time::timeout(Duration::from_millis(ms as u64), f)
        .await
        .map_err(|_| CoreError::Timeout)
}

/// iroh logs to logcat (tag `ha_iroh_core`) on Android, stderr elsewhere.
/// Filter: `HA_IROH_LOG` env, else `<data_dir>/log_filter` (e.g. via `adb shell run-as`), else `iroh=debug`.
fn init_logging(data_dir: &std::path::Path) {
    use std::sync::Once;
    static ONCE: Once = Once::new();
    ONCE.call_once(|| {
        use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt, EnvFilter};
        let filter = EnvFilter::try_from_env("HA_IROH_LOG")
            .or_else(|_| std::fs::read_to_string(data_dir.join("log_filter")).map_err(|_| ()).and_then(|f| EnvFilter::try_new(f.trim()).map_err(|_| ())))
            .unwrap_or_else(|_| EnvFilter::new("iroh=debug"));
        #[cfg(target_os = "android")]
        let _ = tracing_subscriber::registry().with(filter).with(tracing_android::layer("ha_iroh_core").unwrap()).try_init();
        #[cfg(not(target_os = "android"))]
        let _ = tracing_subscriber::registry().with(filter).with(tracing_subscriber::fmt::layer().with_writer(std::io::stderr)).try_init();
    });
}
