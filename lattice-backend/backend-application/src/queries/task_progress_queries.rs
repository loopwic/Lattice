use crate::AppState;
use backend_domain::TaskStatus;

pub async fn get_task_progress(state: &AppState) -> TaskStatus {
    state.task_status.read().await.clone()
}
