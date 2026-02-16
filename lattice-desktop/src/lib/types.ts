export type RiskLevel = "LOW" | "MEDIUM" | "HIGH";

export type KeyItemRule = {
  item_id: string;
  threshold: number;
  risk_level: RiskLevel;
};

export type ItemRegistryEntry = {
  item_id: string;
  name?: string | null;
  names?: Record<string, string> | null;
  namespace?: string | null;
  path?: string | null;
};

export type AnomalyRow = {
  event_time: string;
  server_id: string;
  player_uuid: string;
  player_name: string;
  item_id: string;
  count: number;
  risk_level: RiskLevel | string;
  rule_id: string;
  reason: string;
  evidence_json: string;
};

export type StorageScanRow = {
  event_time: string;
  item_id: string;
  count: number;
  storage_mod: string;
  storage_id: string;
  dim: string;
  x: number | null;
  y: number | null;
  z: number | null;
  rule_id: string;
  threshold: number;
  risk_level: RiskLevel | string;
  reason: string;
};

export type AlertStatus = {
  status: string;
  mode: string;
};

export type TaskProgress = {
  running: boolean;
  total: number;
  done: number;
  updated_at: number;
  reason_code?: string | null;
  reason_message?: string | null;
  targets_total_by_source?: TargetsTotalBySource | null;
  phase?: string | null;
  done_by_source?: DoneBySource | null;
  trace_id?: string | null;
  throughput_per_sec?: number | null;
};

export type TaskStatus = {
  audit: TaskProgress;
  scan: TaskProgress;
};

export type TargetsTotalBySource = {
  world_containers: number;
  sb_offline: number;
  rs2_offline: number;
  online_runtime: number;
};

export type DoneBySource = {
  world_containers: number;
  sb_offline: number;
  rs2_offline: number;
  online_runtime: number;
};

export type AlertDeliveryRecord = {
  timestamp_ms: number;
  status: string;
  mode: string;
  attempts: number;
  alert_count: number;
  rule_ids: string[];
  error?: string | null;
};

export type ModConfigEnvelope = {
  server_id: string;
  revision: number;
  updated_at_ms: number;
  updated_by: string;
  checksum_sha256: string;
  config: Record<string, unknown>;
};

export type ModConfigPutRequest = {
  server_id?: string;
  updated_by?: string;
  config: Record<string, unknown>;
};

export type ModConfigAck = {
  server_id: string;
  revision: number;
  status: string;
  message?: string | null;
  applied_at_ms: number;
  changed_keys: string[];
};

export type HealthStatus = {
  ok: boolean;
};

export type Settings = {
  baseUrl: string;
  apiToken: string;
  lang: string;
  debugMode: boolean;
};
