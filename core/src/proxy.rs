//! Loopback HTTP gate in front of the tunnel, for the dashboard WebView only (SPEC §7).
use std::{sync::Arc, time::Duration};

use iroh::endpoint::Connection;
use tokio::{
    io::{copy_bidirectional, join, AsyncReadExt, AsyncWriteExt},
    net::{TcpListener, TcpStream},
};
use tokio_util::sync::CancellationToken;

use crate::{io, CoreError, RT};

const HEAD_MAX: usize = 8192;
const HEAD_TIMEOUT: Duration = Duration::from_secs(10);

/// True iff the request head is complete (CRLFCRLF seen; the caller caps it at 8 KiB) and has exactly one
/// `Host` header, equal to `<token>.localhost:<port>`.
pub fn host_ok(head: &[u8], token: &str, port: u16) -> bool {
    let Some(end) = head.windows(4).position(|w| w == b"\r\n\r\n") else { return false };
    let Ok(head) = std::str::from_utf8(&head[..end]) else { return false };
    let mut hosts = head.split("\r\n").skip(1).filter_map(|l| {
        let (k, v) = l.split_once(':')?;
        k.eq_ignore_ascii_case("host").then_some(v.trim())
    });
    let (Some(h), None) = (hosts.next(), hosts.next()) else { return false };
    h.eq_ignore_ascii_case(&format!("{token}.localhost:{port}"))
}

#[derive(uniffi::Object)]
pub struct LoopbackProxy {
    port: u16,
    token: String,
    cancel: CancellationToken,
}

#[uniffi::export]
impl LoopbackProxy {
    pub fn port(&self) -> u16 {
        self.port
    }
    pub fn token(&self) -> String {
        self.token.clone()
    }
    /// Closes the listener and all proxied connections.
    pub fn stop(&self) {
        self.cancel.cancel();
    }
}

pub(crate) fn start(conn: Connection, cancel: CancellationToken) -> Result<Arc<LoopbackProxy>, CoreError> {
    let listener = RT.block_on(TcpListener::bind("127.0.0.1:0")).map_err(io)?;
    let port = listener.local_addr().map_err(io)?.port();
    let token = data_encoding::BASE32_NOPAD.encode(&rand::random::<[u8; 16]>()).to_lowercase();
    let t = token.clone();
    let c = cancel.clone();
    RT.spawn(async move {
        loop {
            let tcp = tokio::select! {
                _ = c.cancelled() => break,
                r = listener.accept() => match r { Ok((s, _)) => s, Err(_) => break },
            };
            let (conn, t, c) = (conn.clone(), t.clone(), c.clone());
            tokio::spawn(async move {
                tokio::select! {
                    _ = c.cancelled() => {}
                    _ = serve(tcp, conn, &t, port) => {}
                }
            });
        }
    });
    Ok(Arc::new(LoopbackProxy { port, token, cancel }))
}

async fn serve(mut tcp: TcpStream, conn: Connection, token: &str, port: u16) -> std::io::Result<()> {
    let mut buf = [0u8; HEAD_MAX];
    let mut len = 0;
    let read_head = async {
        while len < HEAD_MAX && !buf[..len].windows(4).any(|w| w == b"\r\n\r\n") {
            match tcp.read(&mut buf[len..]).await? {
                0 => break,
                n => len += n,
            }
        }
        std::io::Result::Ok(())
    };
    let _ = tokio::time::timeout(HEAD_TIMEOUT, read_head).await;
    if !host_ok(&buf[..len], token, port) {
        tcp.write_all(b"HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n").await?;
        return tcp.shutdown().await; // flush + FIN so the client reads the 403 before the close
    }
    let (mut send, recv) = conn.open_bi().await.map_err(std::io::Error::other)?;
    send.write_all(&buf[..len]).await.map_err(std::io::Error::other)?;
    copy_bidirectional(&mut join(recv, send), &mut tcp).await.map(|_| ())
}
