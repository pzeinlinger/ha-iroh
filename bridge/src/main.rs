use std::{io::ErrorKind, path::PathBuf};

use anyhow::Context;
use clap::{Parser, Subcommand};
use ha_iroh_bridge::{Bridge, Config};
use ha_iroh_proto::{load_or_create_key, ALPN_PAIR, ALPN_TCP};
use iroh::{endpoint::presets, Endpoint};
use serde_json::{json, Value};
use std::time::Duration;
use tokio::io::{AsyncBufReadExt, AsyncWriteExt, BufReader};

#[derive(Parser)]
struct Cli {
    #[command(subcommand)]
    cmd: Cmd,
}

#[derive(Subcommand)]
enum Cmd {
    Run {
        #[arg(long, default_value = "ha-iroh-bridge.toml")]
        config: PathBuf,
    },
}

#[tokio::main]
async fn main() -> anyhow::Result<()> {
    tracing_subscriber::fmt()
        .with_writer(std::io::stderr)
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()),
        )
        .init();
    let Cmd::Run { config } = Cli::parse().cmd;
    let cfg: Config = match std::fs::read_to_string(&config) {
        Ok(s) => toml::from_str(&s).with_context(|| format!("parsing {}", config.display()))?,
        Err(e) if e.kind() == ErrorKind::NotFound => Config::default(),
        Err(e) => return Err(e).with_context(|| format!("reading {}", config.display())),
    };

    let mut b = Endpoint::builder(presets::N0)
        .secret_key(load_or_create_key(&cfg.secret_key_path)?)
        .alpns(vec![ALPN_PAIR.to_vec(), ALPN_TCP.to_vec()]);
    if let Some(m) = cfg.relay.relay_mode()? {
        b = b.relay_mode(m);
    }
    let ep = b.bind().await?;
    tracing::info!(name = cfg.name, ha_addr = cfg.ha_addr, "bridge running");
    let (bridge, mut events) = Bridge::new(cfg, ep.clone())?;
    emit(&json!({"event": "ready", "endpoint_id": ep.id().to_string()})).await?;
    tokio::spawn(bridge.clone().serve());

    // Admin channel: JSON lines on stdin. EOF means the parent is gone, so shut down.
    // All stdout writes happen in this one loop.
    let mut lines = BufReader::new(tokio::io::stdin()).lines();
    let mut term = tokio::signal::unix::signal(tokio::signal::unix::SignalKind::terminate())?;
    loop {
        tokio::select! {
            line = lines.next_line() => match line {
                Ok(None) => break,
                Ok(Some(l)) => emit(&command(&bridge, &ep, &l).await).await?,
                // invalid UTF-8: the line was consumed, keep going
                Err(e) if e.kind() == ErrorKind::InvalidData => emit(&json!({"ok": false, "error": "bad_request"})).await?,
                Err(e) => return Err(e.into()),
            },
            Some(ev) = events.recv() => emit(&serde_json::to_value(ev)?).await?,
            _ = tokio::signal::ctrl_c() => break,
            _ = term.recv() => break,
        }
    }
    ep.close().await;
    Ok(())
}

async fn command(bridge: &Bridge, ep: &Endpoint, line: &str) -> Value {
    let bad = json!({"ok": false, "error": "bad_request"});
    match serde_json::from_str::<Value>(line)
        .ok()
        .as_ref()
        .and_then(|v| v["cmd"].as_str())
    {
        Some("pair") => {
            // let the relay URL land in the ticket; offline is fine, direct addrs still work
            let _ = tokio::time::timeout(Duration::from_secs(3), ep.online()).await;
            let t = bridge.mint_code();
            json!({"ok": true, "ticket": t.to_uri(), "code": t.code})
        }
        _ => bad,
    }
}

async fn emit(v: &Value) -> std::io::Result<()> {
    let mut out = tokio::io::stdout();
    out.write_all(format!("{v}\n").as_bytes()).await?;
    out.flush().await
}
