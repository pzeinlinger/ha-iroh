//! Desktop test client: `cli curl <path> --bridge <EndpointId> [--key client.key]`
use std::{path::PathBuf, time::Instant};

use clap::{Parser, Subcommand};
use ha_iroh_proto::{load_or_create_key, ALPN_TCP};
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
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    let Cmd::Curl { path, bridge, key } = Cli::parse().cmd;
    let key = load_or_create_key(&key)?;
    let ep = Endpoint::builder(presets::N0).secret_key(key).bind().await?;
    eprintln!("my EndpointId: {}", ep.id());

    let t = Instant::now();
    let conn = ep.connect(bridge, ALPN_TCP).await?;
    eprintln!("connect: {:?}", t.elapsed());

    let (mut send, mut recv) = conn.open_bi().await?;
    send.write_all(format!("GET {path} HTTP/1.1\r\nHost: ha.iroh\r\nConnection: close\r\n\r\n").as_bytes())
        .await?;
    // No finish() here: HA (aiohttp) drops the response if the client half-closes first.
    let resp = recv.read_to_end(64 << 20).await?;
    println!("{}", String::from_utf8_lossy(&resp));
    conn.close(0u32.into(), b"bye");
    ep.close().await;
    Ok(())
}
