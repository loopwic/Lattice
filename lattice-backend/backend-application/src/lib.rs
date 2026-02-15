// Backend Application Layer

pub mod commands;
pub mod detect;
pub mod dtos;
pub mod error;
pub mod ingest;
pub mod metrics;
pub mod ops;
pub mod queries;
pub mod query;
pub mod state;

pub use error::AppError;
pub use metrics::Metrics;
pub use state::AppState;
