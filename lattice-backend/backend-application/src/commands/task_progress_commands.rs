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
    };
    let key = payload.task.trim().to_lowercase();
    if key == "audit" {
        status.audit = update;
    } else if key == "scan" {
        status.scan = update;
    }
}
