use anyhow::Result;
use clap::Parser;
use std::path::PathBuf;
use std::sync::OnceLock;
use tracing_appender::non_blocking::WorkerGuard;
use tracing_appender::rolling::{Builder as RollingBuilder, Rotation};
use tracing_subscriber::layer::SubscriberExt;
use tracing_subscriber::util::SubscriberInitExt;
use tracing_subscriber::Layer;

static LOG_GUARD: OnceLock<WorkerGuard> = OnceLock::new();

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
    init_tracing();

    let args = Args::parse();
    if let Some(config) = args.config {
        std::env::set_var("LATTICE_CONFIG", config);
    }

    backend_bootstrap::run_standalone().await
}

fn init_tracing() {
    let env_filter = tracing_subscriber::EnvFilter::try_from_default_env()
        .unwrap_or_else(|_| tracing_subscriber::EnvFilter::new("info"));

    let console_layer = tracing_subscriber::fmt::layer()
        .with_target(false)
        .with_ansi(true)
        .with_filter(env_filter.clone());

    let log_dir = std::env::var("LATTICE_LOG_DIR")
        .ok()
        .filter(|v| !v.trim().is_empty())
        .map(PathBuf::from)
        .unwrap_or_else(|| PathBuf::from("logs"));
    let _ = std::fs::create_dir_all(&log_dir);

    match RollingBuilder::new()
        .rotation(Rotation::DAILY)
        .filename_prefix("lattice-backend")
        .filename_suffix("json")
        .max_log_files(7)
        .build(&log_dir)
    {
        Ok(file_appender) => {
            let (non_blocking, guard) = tracing_appender::non_blocking(file_appender);
            let _ = LOG_GUARD.set(guard);

            let file_layer = tracing_subscriber::fmt::layer()
                .json()
                .with_target(true)
                .with_thread_ids(true)
                .with_thread_names(true)
                .with_current_span(true)
                .with_span_list(true)
                .with_ansi(false)
                .with_writer(non_blocking)
                .with_filter(env_filter);

            tracing_subscriber::registry()
                .with(console_layer)
                .with(file_layer)
                .init();
        }
        Err(_) => {
            tracing_subscriber::registry().with(console_layer).init();
        }
    }
}
