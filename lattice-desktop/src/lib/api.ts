import type {
  AlertDeliveryRecord,
  AlertStatus,
  AnomalyRow,
  ItemRegistryEntry,
  KeyItemRule,
  StorageScanRow,
  TaskStatus,
} from "@/lib/types";

function normalizeBaseUrl(baseUrl: string) {
  const trimmed = baseUrl.trim();
  if (!trimmed) {
    return "http://127.0.0.1:3234";
  }
  if (!/^https?:\/\//i.test(trimmed)) {
    return `http://${trimmed.replace(/^\/+/, "")}`;
  }
  if (trimmed.endsWith("/")) {
    return trimmed.slice(0, -1);
  }
  return trimmed;
}

function buildUrl(baseUrl: string, path: string) {
  return `${normalizeBaseUrl(baseUrl)}${path}`;
}

function buildHeaders(apiToken: string, isJson = false) {
  const headers: Record<string, string> = {};
  const trimmed = apiToken.trim();
  if (trimmed) {
    headers.Authorization = `Bearer ${trimmed}`;
  }
  if (isJson) {
    headers["Content-Type"] = "application/json";
  }
  return headers;
}

async function jsonOrThrow<T>(response: Response): Promise<T> {
  if (!response.ok) {
    const text = await response.text();
    throw new Error(text || `Request failed (${response.status})`);
  }
  return (await response.json()) as T;
}

function normalizeTaskProgress(raw: unknown) {
  const next = raw as Partial<{
    running: boolean;
    total: number;
    done: number;
    updated_at: number;
    reason_code?: string | null;
    reason_message?: string | null;
    targets_total_by_source?: {
      world_containers?: number;
      sb_offline?: number;
      rs2_offline?: number;
      online_runtime?: number;
    } | null;
  }>;
  const sourceTotals = next?.targets_total_by_source;
  return {
    running: Boolean(next?.running),
    total: Number(next?.total || 0),
    done: Number(next?.done || 0),
    updated_at: Number(next?.updated_at || 0),
    reason_code:
      typeof next?.reason_code === "string" && next.reason_code.trim()
        ? next.reason_code.trim()
        : null,
    reason_message:
      typeof next?.reason_message === "string" && next.reason_message.trim()
        ? next.reason_message.trim()
        : null,
    targets_total_by_source: sourceTotals
      ? {
          world_containers: Number(sourceTotals.world_containers || 0),
          sb_offline: Number(sourceTotals.sb_offline || 0),
          rs2_offline: Number(sourceTotals.rs2_offline || 0),
          online_runtime: Number(sourceTotals.online_runtime || 0),
        }
      : null,
  };
}

function normalizeTaskStatus(raw: unknown): TaskStatus {
  const obj = (raw as Record<string, unknown>) || {};
  const auditRaw =
    obj.audit ?? obj.inventory_audit ?? obj.audit_all ?? obj.player_audit;
  const scanRaw = obj.scan ?? obj.storage_scan ?? obj.full_scan ?? obj.storage;
  return {
    audit: normalizeTaskProgress(auditRaw),
    scan: normalizeTaskProgress(scanRaw),
  };
}

export async function fetchKeyItems(baseUrl: string, apiToken: string) {
  const res = await fetch(buildUrl(baseUrl, "/v2/detect/rules"), {
    headers: buildHeaders(apiToken, false),
  });
  return jsonOrThrow<KeyItemRule[]>(res);
}

export async function saveKeyItems(
  baseUrl: string,
  apiToken: string,
  rules: KeyItemRule[],
) {
  const res = await fetch(buildUrl(baseUrl, "/v2/detect/rules"), {
    method: "PUT",
    headers: buildHeaders(apiToken, true),
    body: JSON.stringify({ rules }),
  });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `Save failed (${res.status})`);
  }
}

export async function searchRegistry(
  baseUrl: string,
  apiToken: string,
  query: string,
  lang: string,
  limit = 30,
) {
  const url = buildUrl(
    baseUrl,
    `/v2/query/item-registry?query=${encodeURIComponent(query)}&limit=${limit}&lang=${encodeURIComponent(lang)}`,
  );
  const res = await fetch(url, {
    headers: buildHeaders(apiToken, false),
  });
  return jsonOrThrow<ItemRegistryEntry[]>(res);
}

export async function fetchAnomalies(
  baseUrl: string,
  apiToken: string,
  date: string,
  player?: string,
) {
  const query = new URLSearchParams();
  query.set("date", date);
  if (player) {
    query.set("player", player);
  }
  const res = await fetch(buildUrl(baseUrl, `/v2/detect/anomalies?${query.toString()}`), {
    headers: buildHeaders(apiToken, false),
  });
  return jsonOrThrow<AnomalyRow[]>(res);
}

export async function fetchStorageScan(
  baseUrl: string,
  apiToken: string,
  date: string,
  item?: string,
  limit = 200,
) {
  const query = new URLSearchParams();
  query.set("date", date);
  if (item) {
    query.set("item", item);
  }
  query.set("limit", String(limit));
  const res = await fetch(buildUrl(baseUrl, `/v2/detect/storage-scan?${query.toString()}`), {
    headers: buildHeaders(apiToken, false),
  });
  return jsonOrThrow<StorageScanRow[]>(res);
}

export async function fetchAlertStatus(
  baseUrl: string,
  apiToken: string,
): Promise<AlertStatus> {
  const res = await fetch(buildUrl(baseUrl, "/v2/ops/alert-target/check"), {
    headers: buildHeaders(apiToken, false),
  });
  let payload: AlertStatus | null = null;
  try {
    payload = (await res.json()) as AlertStatus;
  } catch {
    payload = null;
  }

  if (
    payload &&
    typeof payload.status === "string" &&
    typeof payload.mode === "string"
  ) {
    return payload;
  }

  if (!res.ok) {
    throw new Error(`Alert check failed (${res.status})`);
  }
  throw new Error("Alert check returned invalid payload");
}

export async function fetchTaskProgress(
  baseUrl: string,
  apiToken: string,
): Promise<TaskStatus> {
  const res = await fetch(buildUrl(baseUrl, "/v2/ops/task-progress"), {
    headers: buildHeaders(apiToken, false),
  });
  const raw = await jsonOrThrow<unknown>(res);
  return normalizeTaskStatus(raw);
}

function normalizeAlertDelivery(raw: unknown): AlertDeliveryRecord {
  const next = (raw ?? {}) as Partial<AlertDeliveryRecord>;
  const rules = Array.isArray(next.rule_ids)
    ? next.rule_ids.filter((item): item is string => typeof item === "string")
    : [];
  return {
    timestamp_ms: Number(next.timestamp_ms || 0),
    status: typeof next.status === "string" ? next.status : "unknown",
    mode: typeof next.mode === "string" ? next.mode : "unset",
    attempts: Number(next.attempts || 0),
    alert_count: Number(next.alert_count || 0),
    rule_ids: rules,
    error: typeof next.error === "string" ? next.error : null,
  };
}

export async function fetchAlertDeliveries(
  baseUrl: string,
  apiToken: string,
  limit = 50,
): Promise<AlertDeliveryRecord[]> {
  const size = Math.max(1, Math.min(200, Math.floor(limit)));
  const res = await fetch(
    buildUrl(baseUrl, `/v2/ops/alert-deliveries?limit=${size}`),
    {
      headers: buildHeaders(apiToken, false),
    },
  );
  const raw = await jsonOrThrow<unknown[]>(res);
  if (!Array.isArray(raw)) {
    return [];
  }
  return raw.map((item) => normalizeAlertDelivery(item));
}

export async function fetchLastAlertDelivery(
  baseUrl: string,
  apiToken: string,
): Promise<AlertDeliveryRecord | null> {
  const res = await fetch(buildUrl(baseUrl, "/v2/ops/alert-deliveries/last"), {
    headers: buildHeaders(apiToken, false),
  });
  const raw = await jsonOrThrow<unknown | null>(res);
  if (!raw) {
    return null;
  }
  return normalizeAlertDelivery(raw);
}

export async function pingHealth(baseUrl: string) {
  const res = await fetch(buildUrl(baseUrl, "/v2/ops/health/live"));
  return res.ok;
}

export async function pingReady(baseUrl: string) {
  const res = await fetch(buildUrl(baseUrl, "/v2/ops/health/ready"));
  return res.ok;
}

export async function fetchMetrics(baseUrl: string, apiToken: string) {
  const res = await fetch(buildUrl(baseUrl, "/v2/ops/metrics/prometheus"), {
    headers: buildHeaders(apiToken, false),
  });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(text || `Metrics failed (${res.status})`);
  }
  return res.text();
}
