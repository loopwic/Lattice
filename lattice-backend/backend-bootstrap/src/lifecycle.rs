use anyhow::Result;
use axum::Router;
use tokio::net::TcpListener;
use tokio::sync::oneshot;
use tower_http::cors::CorsLayer;
use tower_http::limit::RequestBodyLimitLayer;
use tower_http::timeout::TimeoutLayer;
use tower_http::trace::TraceLayer;
use tracing::info;

use backend_application::AppState;
use backend_infrastructure::schedule_reports;
use backend_interfaces_http::build_router;

use crate::context::AppContext;

pub struct BackendHandle {
    shutdown_tx: Option<oneshot::Sender<()>>,
    worker: Option<std::thread::JoinHandle<()>>,
}

impl BackendHandle {
    pub fn stop(mut self) {
        if let Some(tx) = self.shutdown_tx.take() {
            let _ = tx.send(());
        }
        if let Some(worker) = self.worker.take() {
            let _ = worker.join();
        }
    }
}

fn build_router_with_layers(state: AppState) -> Router {
    build_router(state.clone())
        .layer(CorsLayer::permissive())
        .layer(RequestBodyLimitLayer::new(
            usize::try_from(state.config.max_body_bytes).unwrap_or(usize::MAX),
        ))
        .layer(TimeoutLayer::new(std::time::Duration::from_secs(
            state.config.request_timeout_seconds,
        )))
        .layer(TraceLayer::new_for_http())
}

pub async fn run_standalone() -> Result<()> {
    let context = AppContext::new().await?;
    let state = context.state;

    tokio::spawn(schedule_reports(state.clone()));

    let app = build_router_with_layers(state.clone());
    let addr: std::net::SocketAddr = state.config.bind_addr.parse()?;
    let listener = TcpListener::bind(addr).await?;
    info!("listening on {}", addr);

    axum::serve(listener, app)
        .with_graceful_shutdown(shutdown_signal())
        .await?;
    Ok(())
}

pub fn start_embedded(config_path: impl AsRef<std::path::Path>) -> Result<BackendHandle> {
    std::env::set_var(
        "LATTICE_CONFIG",
        config_path.as_ref().to_string_lossy().to_string(),
    );

    let (shutdown_tx, shutdown_rx) = oneshot::channel::<()>();
    let worker = std::thread::Builder::new()
        .name("lattice-backend".to_string())
        .spawn(move || {
            let runtime = match tokio::runtime::Builder::new_multi_thread()
                .thread_name("lattice-backend-rt")
                .enable_all()
                .build()
            {
                Ok(runtime) => runtime,
                Err(err) => {
                    eprintln!("embedded backend runtime init failed: {err}");
                    return;
                }
            };

            runtime.block_on(async move {
                if let Err(err) = run_embedded_with_shutdown(shutdown_rx).await {
                    eprintln!("embedded backend exited: {err}");
                }
            });
        })?;

    Ok(BackendHandle {
        shutdown_tx: Some(shutdown_tx),
        worker: Some(worker),
    })
}

async fn run_embedded_with_shutdown(mut shutdown_rx: oneshot::Receiver<()>) -> Result<()> {
    let context = AppContext::new().await?;
    let state = context.state;

    tokio::spawn(schedule_reports(state.clone()));

    let app = build_router_with_layers(state.clone());
    let addr: std::net::SocketAddr = state.config.bind_addr.parse()?;
    let listener = TcpListener::bind(addr).await?;
    info!("embedded backend listening on {}", addr);

    axum::serve(listener, app)
        .with_graceful_shutdown(async move {
            let _ = (&mut shutdown_rx).await;
        })
        .await?;
    Ok(())
}

async fn shutdown_signal() {
    let ctrl_c = async {
        let _ = tokio::signal::ctrl_c().await;
    };

    #[cfg(unix)]
    let terminate = async {
        use tokio::signal::unix::{signal, SignalKind};
        let mut sigterm = signal(SignalKind::terminate()).expect("sigterm handler");
        sigterm.recv().await;
    };

    #[cfg(not(unix))]
    let terminate = std::future::pending::<()>();

    tokio::select! {
        _ = ctrl_c => {},
        _ = terminate => {},
    }
}
