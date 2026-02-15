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
};

export type TaskStatus = {
  audit: TaskProgress;
  scan: TaskProgress;
};

export type HealthStatus = {
  ok: boolean;
};

export type Settings = {
  baseUrl: string;
  apiToken: string;
  lang: string;
};
