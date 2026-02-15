use crate::AppState;
use backend_domain::TaskProgressUpdate;

pub async fn update_task_progress(state: &AppState, payload: TaskProgressUpdate) {
    let mut status = state.task_status.write().await;
    let now = chrono::Utc::now().timestamp_millis();
    let update = backend_domain::TaskProgress {
        running: payload.running,
        total: payload.total,
        done: payload.done,
        updated_at: now,
        reason_code: normalize_optional_text(payload.reason_code),
        reason_message: normalize_optional_text(payload.reason_message),
        targets_total_by_source: payload.targets_total_by_source,
    };
    let key = payload.task.trim().to_lowercase();
    if key == "audit" {
        status.audit = update;
    } else if key == "scan" {
        status.scan = update;
    }
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
