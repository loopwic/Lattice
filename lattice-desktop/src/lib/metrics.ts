export type ParsedMetrics = {
  requests: number;
  events: number;
  errors: number;
  anomalies: number;
};

const METRIC_MAP: Record<string, keyof ParsedMetrics> = {
  lattice_requests_total: "requests",
  lattice_events_total: "events",
  lattice_ingest_errors_total: "errors",
  lattice_anomalies_total: "anomalies",
};

export function parsePrometheusMetrics(payload: string): ParsedMetrics {
  const metrics: ParsedMetrics = {
    requests: 0,
    events: 0,
    errors: 0,
    anomalies: 0,
  };

  payload
    .split(/\r?\n/)
    .map((line) => line.trim())
    .filter((line) => line.length > 0 && !line.startsWith("#"))
    .forEach((line) => {
      const [name, value] = line.split(/\s+/);
      const key = METRIC_MAP[name];
      if (!key) {
        return;
      }
      const parsed = Number(value);
      if (!Number.isFinite(parsed)) {
        return;
      }
      metrics[key] = parsed;
    });

  return metrics;
}
