// Task status entity

use serde::{Deserialize, Serialize};

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct TaskProgress {
    pub running: bool,
    pub total: u32,
    pub done: u32,
    pub updated_at: i64,
}

#[derive(Debug, Clone, Serialize, Deserialize, Default)]
pub struct TaskStatus {
    pub audit: TaskProgress,
    pub scan: TaskProgress,
}
