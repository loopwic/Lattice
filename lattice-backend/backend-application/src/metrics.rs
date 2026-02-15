use std::sync::atomic::{AtomicU64, Ordering};

#[derive(Debug, Default)]
pub struct Metrics {
    ingest_requests: AtomicU64,
    ingest_events: AtomicU64,
    ingest_errors: AtomicU64,
    anomalies: AtomicU64,
}

impl Metrics {
    pub fn record_ingest(&self, event_count: usize) {
        self.ingest_requests.fetch_add(1, Ordering::Relaxed);
        self.ingest_events
            .fetch_add(event_count as u64, Ordering::Relaxed);
    }

    pub fn record_ingest_error(&self) {
        self.ingest_errors.fetch_add(1, Ordering::Relaxed);
    }

    pub fn record_anomalies(&self, count: usize) {
        self.anomalies.fetch_add(count as u64, Ordering::Relaxed);
    }

    pub fn render_prometheus(&self) -> String {
        let requests = self.ingest_requests.load(Ordering::Relaxed);
        let events = self.ingest_events.load(Ordering::Relaxed);
        let errors = self.ingest_errors.load(Ordering::Relaxed);
        let anomalies = self.anomalies.load(Ordering::Relaxed);

        format!(
            "# TYPE lattice_ingest_requests_total counter\n\
lattice_ingest_requests_total {}\n\
# TYPE lattice_ingest_events_total counter\n\
lattice_ingest_events_total {}\n\
# TYPE lattice_ingest_errors_total counter\n\
lattice_ingest_errors_total {}\n\
# TYPE lattice_anomalies_total counter\n\
lattice_anomalies_total {}\n",
            requests, events, errors, anomalies
        )
    }
}
