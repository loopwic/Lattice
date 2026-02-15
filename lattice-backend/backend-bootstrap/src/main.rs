use anyhow::Result;
use clap::Parser;

#[derive(Parser, Debug)]
#[command(name = "lattice-backend")]
#[command(about = "Lattice Backend Server", long_about = None)]
struct Args {
    /// Path to config file
    #[arg(short, long)]
    config: Option<String>,
}

#[tokio::main]
async fn main() -> Result<()> {
    tracing_subscriber::fmt()
        .with_env_filter(
            tracing_subscriber::EnvFilter::try_from_default_env()
                .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info")),
        )
        .init();

    let args = Args::parse();
    if let Some(config) = args.config {
        std::env::set_var("LATTICE_CONFIG", config);
    }

    backend_bootstrap::run_standalone().await
}
