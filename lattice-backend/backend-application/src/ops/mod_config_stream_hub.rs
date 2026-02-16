use std::collections::HashMap;

use backend_domain::ModConfigEnvelope;
use tokio::sync::{broadcast, RwLock};

const CHANNEL_BUFFER: usize = 64;

#[derive(Default)]
pub struct ModConfigStreamHub {
    channels: RwLock<HashMap<String, broadcast::Sender<ModConfigEnvelope>>>,
}

impl ModConfigStreamHub {
    pub async fn subscribe(&self, server_id: &str) -> broadcast::Receiver<ModConfigEnvelope> {
        let mut channels = self.channels.write().await;
        channels
            .entry(server_id.trim().to_lowercase())
            .or_insert_with(|| {
                let (tx, _rx) = broadcast::channel(CHANNEL_BUFFER);
                tx
            })
            .subscribe()
    }

    pub async fn publish(&self, envelope: &ModConfigEnvelope) {
        let mut channels = self.channels.write().await;
        let tx = channels
            .entry(envelope.server_id.trim().to_lowercase())
            .or_insert_with(|| {
                let (tx, _rx) = broadcast::channel(CHANNEL_BUFFER);
                tx
            });
        let _ = tx.send(envelope.clone());
    }
}
