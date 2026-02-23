import type {
  AlertDeliveryRecord,
  AlertStatus,
  AnomalyRow,
  ItemRegistryEntry,
  KeyItemRule,
  ModConfigAck,
  ModConfigEnvelope,
  ModConfigPutRequest,
  PagedResult,
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
    state: string;
    stage?: string | null;
    counters?: {
      total?: number;
      done?: number;
      targets_total_by_source?: {
        world_containers?: number;
        sb_offline?: number;
        rs2_offline?: number;
        online_runtime?: number;
      } | null;
      done_by_source?: {
        world_containers?: number;
        sb_offline?: number;
        rs2_offline?: number;
        online_runtime?: number;
      } | null;
    } | null;
    updated_at: number;
    failure?: {
      code?: string;
      message?: string;
    } | null;
    trace_id?: string | null;
    throughput_per_sec?: number | null;
  }>;
  const sourceTotals = next?.counters?.targets_total_by_source;
  const doneBySource = next?.counters?.done_by_source;
  const throughput =
    typeof next?.throughput_per_sec === "number" && Number.isFinite(next.throughput_per_sec)
      ? next.throughput_per_sec
      : null;
  const failureCode = next?.failure?.code?.trim();
  const failureMessage = next?.failure?.message?.trim();
  return {
    state:
      typeof next?.state === "string" && next.state.trim()
        ? next.state.trim().toUpperCase()
        : "IDLE",
    stage:
      typeof next?.stage === "string" && next.stage.trim()
        ? next.stage.trim().toUpperCase()
        : null,
    counters: {
      total: Number(next?.counters?.total || 0),
      done: Number(next?.counters?.done || 0),
      targets_total_by_source: {
        world_containers: Number(sourceTotals?.world_containers || 0),
        sb_offline: Number(sourceTotals?.sb_offline || 0),
        rs2_offline: Number(sourceTotals?.rs2_offline || 0),
        online_runtime: Number(sourceTotals?.online_runtime || 0),
      },
      done_by_source: {
        world_containers: Number(doneBySource?.world_containers || 0),
        sb_offline: Number(doneBySource?.sb_offline || 0),
        rs2_offline: Number(doneBySource?.rs2_offline || 0),
        online_runtime: Number(doneBySource?.online_runtime || 0),
      },
    },
    updated_at: Number(next?.updated_at || 0),
    failure:
      failureCode && failureMessage
        ? {
            code: failureCode,
            message: failureMessage,
          }
        : null,
    trace_id:
      typeof next?.trace_id === "string" && next.trace_id.trim()
        ? next.trace_id.trim()
        : null,
    throughput_per_sec: throughput,
  };
}

function normalizeTaskStatus(raw: unknown): TaskStatus {
  const obj = (raw as Record<string, unknown>) || {};
  return {
    audit: normalizeTaskProgress(obj.audit),
    scan: normalizeTaskProgress(obj.scan),
  };
}

function normalizePagedResult<T>(raw: unknown): PagedResult<T> {
  const obj = (raw ?? {}) as Partial<PagedResult<T>>;
  const items = Array.isArray(obj.items) ? obj.items : [];
  const page = Number(obj.page || 1);
  const pageSize = Number(obj.page_size || 50);
  const totalItems = Number(obj.total_items || 0);
  const totalPages = Number(obj.total_pages || 1);
  return {
    items,
    page: Number.isFinite(page) && page > 0 ? page : 1,
    page_size: Number.isFinite(pageSize) && pageSize > 0 ? pageSize : 50,
    total_items: Number.isFinite(totalItems) && totalItems >= 0 ? totalItems : 0,
    total_pages: Number.isFinite(totalPages) && totalPages > 0 ? totalPages : 1,
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
  page: number,
  pageSize: number,
  player?: string,
) {
  const query = new URLSearchParams();
  query.set("date", date);
  query.set("page", String(page));
  query.set("page_size", String(pageSize));
  if (player) {
    query.set("player", player);
  }
  const res = await fetch(buildUrl(baseUrl, `/v2/detect/anomalies?${query.toString()}`), {
    headers: buildHeaders(apiToken, false),
  });
  const raw = await jsonOrThrow<unknown>(res);
  return normalizePagedResult<AnomalyRow>(raw);
}

export async function fetchStorageScan(
  baseUrl: string,
  apiToken: string,
  date: string,
  page: number,
  pageSize: number,
  item?: string,
) {
  const query = new URLSearchParams();
  query.set("date", date);
  query.set("page", String(page));
  query.set("page_size", String(pageSize));
  if (item) {
    query.set("item", item);
  }
  const res = await fetch(buildUrl(baseUrl, `/v2/detect/storage-scan?${query.toString()}`), {
    headers: buildHeaders(apiToken, false),
  });
  const raw = await jsonOrThrow<unknown>(res);
  return normalizePagedResult<StorageScanRow>(raw);
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

function normalizeModConfigEnvelope(raw: unknown): ModConfigEnvelope | null {
  const next = (raw ?? {}) as Partial<ModConfigEnvelope>;
  if (!next || typeof next !== "object") {
    return null;
  }
  if (typeof next.server_id !== "string" || !next.server_id.trim()) {
    return null;
  }
  if (typeof next.revision !== "number" || !Number.isFinite(next.revision)) {
    return null;
  }
  const config = next.config;
  const safeConfig =
    config && typeof config === "object" && !Array.isArray(config)
      ? (config as Record<string, unknown>)
      : {};
  return {
    server_id: next.server_id,
    revision: next.revision,
    updated_at_ms: Number(next.updated_at_ms || 0),
    updated_by: typeof next.updated_by === "string" ? next.updated_by : "",
    checksum_sha256:
      typeof next.checksum_sha256 === "string" ? next.checksum_sha256 : "",
    config: safeConfig,
  };
}

function normalizeModConfigAck(raw: unknown): ModConfigAck | null {
  const next = (raw ?? {}) as Partial<ModConfigAck>;
  if (!next || typeof next !== "object") {
    return null;
  }
  if (typeof next.server_id !== "string" || !next.server_id.trim()) {
    return null;
  }
  if (typeof next.revision !== "number" || !Number.isFinite(next.revision)) {
    return null;
  }
  const changedKeys = Array.isArray(next.changed_keys)
    ? next.changed_keys.filter((item): item is string => typeof item === "string")
    : [];
  return {
    server_id: next.server_id,
    revision: next.revision,
    status: typeof next.status === "string" ? next.status : "UNKNOWN",
    message: typeof next.message === "string" ? next.message : null,
    applied_at_ms: Number(next.applied_at_ms || 0),
    changed_keys: changedKeys,
  };
}

export async function fetchModConfigCurrent(
  baseUrl: string,
  apiToken: string,
  serverId: string,
): Promise<ModConfigEnvelope | null> {
  const query = new URLSearchParams();
  query.set("server_id", serverId.trim() || "server-01");
  const res = await fetch(
    buildUrl(baseUrl, `/v2/ops/mod-config/current?${query.toString()}`),
    {
      headers: buildHeaders(apiToken, false),
    },
  );
  const raw = await jsonOrThrow<unknown | null>(res);
  if (!raw) {
    return null;
  }
  return normalizeModConfigEnvelope(raw);
}

export async function updateModConfigCurrent(
  baseUrl: string,
  apiToken: string,
  serverId: string,
  payload: ModConfigPutRequest,
): Promise<ModConfigEnvelope> {
  const query = new URLSearchParams();
  query.set("server_id", serverId.trim() || "server-01");
  const res = await fetch(
    buildUrl(baseUrl, `/v2/ops/mod-config/current?${query.toString()}`),
    {
      method: "PUT",
      headers: buildHeaders(apiToken, true),
      body: JSON.stringify(payload),
    },
  );
  const raw = await jsonOrThrow<unknown>(res);
  const normalized = normalizeModConfigEnvelope(raw);
  if (!normalized) {
    throw new Error("配置发布成功，但返回格式无效");
  }
  return normalized;
}

export async function fetchModConfigAckLast(
  baseUrl: string,
  apiToken: string,
  serverId: string,
): Promise<ModConfigAck | null> {
  const query = new URLSearchParams();
  query.set("server_id", serverId.trim() || "server-01");
  const res = await fetch(
    buildUrl(baseUrl, `/v2/ops/mod-config/ack/last?${query.toString()}`),
    {
      headers: buildHeaders(apiToken, false),
    },
  );
  const raw = await jsonOrThrow<unknown | null>(res);
  if (!raw) {
    return null;
  }
  return normalizeModConfigAck(raw);
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
