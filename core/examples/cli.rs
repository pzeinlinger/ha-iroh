//! Desktop test client:
//!   `cli curl <path> --bridge <EndpointId> [--key cli.key]`
//!   `cli pair <ha-iroh://pair?t=...> [--device-name cli] [--key cli.key]`
use std::{path::PathBuf, time::Instant};

use clap::{Parser, Subcommand};
use ha_iroh_proto::{
    load_or_create_key, read_frame, write_frame, PairRequest, PairResponse, PairTicket,
    ALPN_PAIR, ALPN_TCP,
};
use iroh::{endpoint::presets, Endpoint, EndpointId};

#[derive(Parser)]
struct Cli {
    #[command(subcommand)]
    cmd: Cmd,
}

#[derive(Subcommand)]
enum Cmd {
    Curl {
        path: String,
        #[arg(long)]
        bridge: EndpointId,
        #[arg(long, default_value = "cli.key")]
        key: PathBuf,
    },
    Pair {
        uri: String,
        #[arg(long, default_value = "cli")]
        device_name: String,
        #[arg(long, default_value = "cli.key")]
        key: PathBuf,
    },
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let (path, bridge, key) = match Cli::parse().cmd {
        Cmd::Curl { path, bridge, key } => (path, bridge, key),
        Cmd::Pair {
            uri,
            device_name,
            key,
        } => return pair(&uri, device_name, &key).await,
    };
    let key = load_or_create_key(&key)?;
    let ep = Endpoint::builder(presets::N0)
        .secret_key(key)
        .bind()
        .await?;
    eprintln!("my EndpointId: {}", ep.id());

    let t = Instant::now();
    let conn = ep.connect(bridge, ALPN_TCP).await?;
    eprintln!("connect: {:?}", t.elapsed());

    let (mut send, mut recv) = conn.open_bi().await?;
    send.write_all(
        format!("GET {path} HTTP/1.1\r\nHost: ha.iroh\r\nConnection: close\r\n\r\n").as_bytes(),
    )
    .await?;
    // No finish() here: HA (aiohttp) drops the response if the client half-closes first.
    let resp = recv.read_to_end(64 << 20).await?;
    println!("{}", String::from_utf8_lossy(&resp));
    conn.close(0u32.into(), b"bye");
    ep.close().await;
    Ok(())
}

async fn pair(uri: &str, device_name: String, key: &std::path::Path) -> anyhow::Result<()> {
    let ticket = PairTicket::from_uri(uri)?;
    let mut b = Endpoint::builder(presets::N0).secret_key(load_or_create_key(key)?);
    if let Some(m) = ticket.relay.relay_mode()? {
        b = b.relay_mode(m);
    }
    let ep = b.bind().await?;
    eprintln!("my EndpointId: {}", ep.id());
    let conn = ep.connect(ticket.addr, ALPN_PAIR).await?;
    let (mut send, mut recv) = conn.open_bi().await?;
    write_frame(
        &mut send,
        &PairRequest {
            code: ticket.code,
            device_name,
        },
    )
    .await?;
    let resp: PairResponse = read_frame(&mut recv).await?;
    println!("{}", serde_json::to_string(&resp)?);
    conn.close(0u32.into(), b"bye");
    ep.close().await;
    if !resp.ok {
        std::process::exit(1);
    }
    Ok(())
}
