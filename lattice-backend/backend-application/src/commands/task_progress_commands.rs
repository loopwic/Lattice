use crate::AppState;
use crate::AppError;
use backend_domain::TaskProgressUpdate;

pub async fn update_task_progress(
    state: &AppState,
    payload: TaskProgressUpdate,
) -> Result<(), AppError> {
    let mut status = state.task_status.write().await;
    let now = chrono::Utc::now().timestamp_millis();
    let update = backend_domain::TaskProgress {
        state: normalize_state(payload.state)?,
        stage: normalize_stage(payload.stage)?,
        counters: payload.counters,
        updated_at: now,
        failure: normalize_failure(payload.failure),
        trace_id: normalize_optional_text(payload.trace_id),
        throughput_per_sec: normalize_optional_number(payload.throughput_per_sec),
    };
    let key = payload.task.trim().to_lowercase();
    if key == "audit" {
        status.audit = update;
    } else if key == "scan" {
        status.scan = update;
    } else {
        return Err(AppError::BadRequest("task must be audit or scan".to_string()));
    }
    Ok(())
}

fn normalize_optional_text(value: Option<String>) -> Option<String> {
    match value {
        Some(raw) => {
            let trimmed = raw.trim();
            if trimmed.is_empty() {
                None
            } else {
                Some(trimmed.to_string())
            }
        }
        None => None,
    }
}

fn normalize_optional_number(value: Option<f64>) -> Option<f64> {
    match value {
        Some(raw) if raw.is_finite() && raw >= 0.0 => Some(raw),
        _ => None,
    }
}

fn normalize_state(value: String) -> Result<String, AppError> {
    let state = value.trim().to_uppercase();
    match state.as_str() {
        "IDLE" | "RUNNING" | "SUCCEEDED" | "FAILED" => Ok(state),
        _ => Err(AppError::BadRequest(
            "state must be one of: IDLE, RUNNING, SUCCEEDED, FAILED".to_string(),
        )),
    }
}

fn normalize_stage(value: Option<String>) -> Result<Option<String>, AppError> {
    let Some(raw_stage) = normalize_optional_text(value) else {
        return Ok(None);
    };
    let stage = raw_stage.trim().to_uppercase();
    match stage.as_str() {
        "INDEXING" | "OFFLINE_WORLD" | "OFFLINE_SB" | "OFFLINE_RS2" | "RUNTIME" => {
            Ok(Some(stage))
        }
        _ => Err(AppError::BadRequest(
            "stage must be one of: INDEXING, OFFLINE_WORLD, OFFLINE_SB, OFFLINE_RS2, RUNTIME"
                .to_string(),
        )),
    }
}

fn normalize_failure(value: Option<backend_domain::TaskFailure>) -> Option<backend_domain::TaskFailure> {
    let failure = value?;
    let code = failure.code.trim();
    let message = failure.message.trim();
    if code.is_empty() || message.is_empty() {
        return None;
    }
    Some(backend_domain::TaskFailure {
        code: code.to_string(),
        message: message.to_string(),
    })
}
