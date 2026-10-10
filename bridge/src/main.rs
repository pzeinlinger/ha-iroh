use std::{io::ErrorKind, path::PathBuf};

use anyhow::{bail, Context};
use clap::{Parser, Subcommand};
use ha_iroh_bridge::{serve, Config};
use ha_iroh_proto::{load_or_create_key, RelayConfig, ALPN_PAIR, ALPN_TCP};
use iroh::{endpoint::presets, Endpoint, RelayMode, RelayUrl};

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
        .with_env_filter(tracing_subscriber::EnvFilter::try_from_default_env().unwrap_or_else(|_| "info".into()))
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
    match &cfg.relay {
        RelayConfig::Named(n) if n == "n0" => {}
        RelayConfig::Named(n) => bail!("relay must be \"n0\" or a list of URLs, got {n:?}"),
        RelayConfig::Urls(urls) => {
            let urls = urls.iter().map(|u| u.parse::<RelayUrl>()).collect::<Result<Vec<_>, _>>()?;
            b = b.relay_mode(RelayMode::custom(urls));
        }
    }
    let ep = b.bind().await?;
    println!(r#"{{"event":"ready","endpoint_id":"{}"}}"#, ep.id());
    tracing::info!(name = cfg.name, ha_addr = cfg.ha_addr, "bridge running");
    tokio::select! {
        _ = serve(cfg, ep.clone()) => {}
        _ = tokio::signal::ctrl_c() => ep.close().await,
    }
    Ok(())
}
