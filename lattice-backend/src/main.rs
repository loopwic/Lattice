#[tokio::main]
async fn main() -> anyhow::Result<()> {
    lattice_backend::run().await
}
