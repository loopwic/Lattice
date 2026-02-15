mod analyzer;
mod app;
mod config;
mod db;
mod error;
mod ingest;
mod metrics;
mod model;
mod alert;
mod report;
mod utils;
mod web;

pub use config::AppConfig;

pub async fn run() -> anyhow::Result<()> {
    app::run().await
}
