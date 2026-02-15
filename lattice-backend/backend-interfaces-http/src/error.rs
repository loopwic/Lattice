use axum::http::StatusCode;
use axum::response::{IntoResponse, Response};
use axum::Json;
use serde::Serialize;

#[derive(Debug)]
pub enum HttpError {
    Unauthorized,
    BadRequest(String),
    NotFound,
    Internal(String),
}

impl From<backend_application::AppError> for HttpError {
    fn from(value: backend_application::AppError) -> Self {
        match value {
            backend_application::AppError::Unauthorized => HttpError::Unauthorized,
            backend_application::AppError::BadRequest(msg) => HttpError::BadRequest(msg),
            backend_application::AppError::Internal(err) => HttpError::Internal(err.to_string()),
        }
    }
}

#[derive(Serialize)]
struct ErrorBody {
    error: String,
}

impl IntoResponse for HttpError {
    fn into_response(self) -> Response {
        let (status, message) = match self {
            HttpError::Unauthorized => (StatusCode::UNAUTHORIZED, "unauthorized".to_string()),
            HttpError::BadRequest(msg) => (StatusCode::BAD_REQUEST, format!("bad request: {}", msg)),
            HttpError::NotFound => (StatusCode::NOT_FOUND, "not found".to_string()),
            HttpError::Internal(msg) => (StatusCode::INTERNAL_SERVER_ERROR, msg),
        };
        (status, Json(ErrorBody { error: message })).into_response()
    }
}
