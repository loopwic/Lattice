pub mod context;
pub mod lifecycle;
mod napcat_bridge;

pub use lifecycle::{run_standalone, start_embedded, BackendHandle};

pub async fn run() -> anyhow::Result<()> {
    run_standalone().await
}
