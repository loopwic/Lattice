import * as React from "react";
import { invoke } from "@tauri-apps/api/core";
import { useQuery } from "@tanstack/react-query";
import { motion } from "motion/react";
import { useTheme } from "next-themes";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
import {
  fetchModConfigAckLast,
  fetchModConfigCurrent,
  updateModConfigCurrent,
} from "@/lib/api";
import { statusBadgeClass } from "@/lib/status-badge";
import { useMotionPresets } from "@/lib/motion";
import { useSettings } from "@/lib/settings";

type BackendRuntimeStatus = {
  running: boolean;
  last_error?: string | null;
};

type UiLang = "zh_cn" | "en_us";

const OPEN_DEBUG_EVENT = "lattice-open-debug-console";

function formatTimestamp(ms?: number | null) {
  if (!ms || ms <= 0) {
    return "-";
  }
  return new Date(ms).toLocaleString();
}

type ModConfigForm = {
  backend_url: string;
  api_token: string;
  server_id: string;
  rcon_host: string;
  op_command_token_required: boolean;
  op_command_token_secret: string;
  batch_size: number;
  flush_interval_ms: number;
  spool_dir: string;
  context_window_ms: number;
  audit_enabled: boolean;
  audit_interval_minutes: number;
  audit_players_per_tick: number;
  scan_enabled: boolean;
  scan_interval_minutes: number;
  scan_containers_per_tick: number;
  scan_rs2_networks_per_tick: number;
  scan_rescan_cooldown_minutes: number;
  scan_include_containers: boolean;
  scan_include_rs2: boolean;
  scan_max_avg_tick_ms: number;
  scan_max_online_players: number;
  scan_world_offline_enabled: boolean;
  scan_sb_offline_enabled: boolean;
  scan_rs2_offline_enabled: boolean;
  scan_offline_chunks_per_tick: number;
  scan_offline_sources_per_tick: number;
  scan_offline_workers: number;
  scan_offline_chunk_interval_ms: number;
  scan_include_online_runtime: boolean;
  registry_upload_enabled: boolean;
  registry_upload_chunk_size: number;
  registry_upload_languages: string;
  scan_item_filter: string;
  ops_log_level: string;
};

const MOD_CONFIG_LABELS: Record<keyof ModConfigForm, Record<UiLang, string>> = {
  backend_url: { zh_cn: "后端地址", en_us: "Backend URL" },
  api_token: { zh_cn: "API Token", en_us: "API Token" },
  server_id: { zh_cn: "服务器 ID", en_us: "Server ID" },
  rcon_host: { zh_cn: "RCON 主机", en_us: "RCON Host" },
  op_command_token_required: {
    zh_cn: "OP 指令 Token 门禁",
    en_us: "OP Command Token Gate",
  },
  op_command_token_secret: {
    zh_cn: "OP Token 密钥",
    en_us: "OP Token Secret",
  },
  batch_size: { zh_cn: "事件批次大小", en_us: "Event Batch Size" },
  flush_interval_ms: { zh_cn: "事件刷新间隔(ms)", en_us: "Flush Interval (ms)" },
  spool_dir: { zh_cn: "本地缓冲目录", en_us: "Spool Directory" },
  context_window_ms: { zh_cn: "上下文窗口(ms)", en_us: "Context Window (ms)" },
  audit_enabled: { zh_cn: "启用背包审计", en_us: "Enable Audit" },
  audit_interval_minutes: { zh_cn: "审计间隔(分钟)", en_us: "Audit Interval (min)" },
  audit_players_per_tick: { zh_cn: "每 Tick 审计玩家", en_us: "Audit Players / Tick" },
  scan_enabled: { zh_cn: "启用扫描", en_us: "Enable Scan" },
  scan_interval_minutes: { zh_cn: "扫描间隔(分钟)", en_us: "Scan Interval (min)" },
  scan_containers_per_tick: { zh_cn: "每 Tick 在线容器", en_us: "Runtime Containers / Tick" },
  scan_rs2_networks_per_tick: { zh_cn: "每 Tick RS2 网络", en_us: "RS2 Networks / Tick" },
  scan_rescan_cooldown_minutes: { zh_cn: "重扫冷却(分钟)", en_us: "Rescan Cooldown (min)" },
  scan_include_containers: { zh_cn: "包含容器扫描", en_us: "Include Containers" },
  scan_include_rs2: { zh_cn: "包含 RS2 扫描", en_us: "Include RS2" },
  scan_max_avg_tick_ms: { zh_cn: "Tick 平均耗时阈值(ms)", en_us: "Avg Tick Threshold (ms)" },
  scan_max_online_players: { zh_cn: "在线人数阈值", en_us: "Max Online Players" },
  scan_world_offline_enabled: { zh_cn: "离线世界扫描", en_us: "Offline World Scan" },
  scan_sb_offline_enabled: { zh_cn: "离线 SB 扫描", en_us: "Offline SB Scan" },
  scan_rs2_offline_enabled: { zh_cn: "离线 RS2 扫描", en_us: "Offline RS2 Scan" },
  scan_offline_chunks_per_tick: { zh_cn: "每 Tick 离线区块", en_us: "Offline Chunks / Tick" },
  scan_offline_sources_per_tick: { zh_cn: "每 Tick 离线来源", en_us: "Offline Sources / Tick" },
  scan_offline_workers: { zh_cn: "离线工作线程", en_us: "Offline Workers" },
  scan_offline_chunk_interval_ms: { zh_cn: "离线区块间隔(ms)", en_us: "Offline Chunk Interval (ms)" },
  scan_include_online_runtime: { zh_cn: "包含在线补扫", en_us: "Include Runtime Supplement" },
  registry_upload_enabled: { zh_cn: "启用注册表上传", en_us: "Enable Registry Upload" },
  registry_upload_chunk_size: { zh_cn: "注册表分片大小", en_us: "Registry Chunk Size" },
  registry_upload_languages: { zh_cn: "注册表语言(逗号分隔)", en_us: "Registry Languages (CSV)" },
  scan_item_filter: { zh_cn: "扫描物品过滤(逗号分隔)", en_us: "Scan Item Filter (CSV)" },
  ops_log_level: { zh_cn: "运维日志级别", en_us: "Ops Log Level" },
};

const MOD_CONFIG_DEFAULTS: ModConfigForm = {
  backend_url: "http://127.0.0.1:3234",
  api_token: "",
  server_id: "server-01",
  rcon_host: "127.0.0.1",
  op_command_token_required: false,
  op_command_token_secret: "",
  batch_size: 200,
  flush_interval_ms: 1000,
  spool_dir: "spool",
  context_window_ms: 2000,
  audit_enabled: true,
  audit_interval_minutes: 30,
  audit_players_per_tick: 4,
  scan_enabled: true,
  scan_interval_minutes: 1440,
  scan_containers_per_tick: 1,
  scan_rs2_networks_per_tick: 1,
  scan_rescan_cooldown_minutes: 1440,
  scan_include_containers: true,
  scan_include_rs2: true,
  scan_max_avg_tick_ms: 25,
  scan_max_online_players: -1,
  scan_world_offline_enabled: true,
  scan_sb_offline_enabled: true,
  scan_rs2_offline_enabled: true,
  scan_offline_chunks_per_tick: 1,
  scan_offline_sources_per_tick: 1,
  scan_offline_workers: 1,
  scan_offline_chunk_interval_ms: 1000,
  scan_include_online_runtime: false,
  registry_upload_enabled: true,
  registry_upload_chunk_size: 500,
  registry_upload_languages: "zh_cn,en_us",
  scan_item_filter: "",
  ops_log_level: "INFO",
};

const MOD_CONFIG_KEYS = new Set<keyof ModConfigForm>(Object.keys(MOD_CONFIG_DEFAULTS) as Array<keyof ModConfigForm>);

function resolveUiLang(lang: string | undefined): UiLang {
  return lang === "en_us" ? "en_us" : "zh_cn";
}

function tr(lang: UiLang, zh: string, en: string) {
  return lang === "en_us" ? en : zh;
}

function fieldLabel(lang: UiLang, key: keyof ModConfigForm) {
  return MOD_CONFIG_LABELS[key][lang];
}

function asObject(value: unknown): Record<string, unknown> {
  if (!value || typeof value !== "object" || Array.isArray(value)) {
    return {};
  }
  return value as Record<string, unknown>;
}

function getString(raw: Record<string, unknown>, key: keyof ModConfigForm, fallback: string) {
  const value = raw[key];
  if (typeof value !== "string") {
    return fallback;
  }
  return value;
}

function getNumber(raw: Record<string, unknown>, key: keyof ModConfigForm, fallback: number) {
  const value = raw[key];
  if (typeof value === "number" && Number.isFinite(value)) {
    return value;
  }
  if (typeof value === "string" && value.trim()) {
    const parsed = Number(value);
    if (Number.isFinite(parsed)) {
      return parsed;
    }
  }
  return fallback;
}

function getBoolean(raw: Record<string, unknown>, key: keyof ModConfigForm, fallback: boolean) {
  const value = raw[key];
  if (typeof value === "boolean") {
    return value;
  }
  if (typeof value === "string") {
    if (value === "true") {
      return true;
    }
    if (value === "false") {
      return false;
    }
  }
  return fallback;
}

function getArrayAsCsv(raw: Record<string, unknown>, key: keyof ModConfigForm, fallback: string) {
  const value = raw[key];
  if (!Array.isArray(value)) {
    return fallback;
  }
  return value
    .filter((item): item is string => typeof item === "string" && item.trim().length > 0)
    .map((item) => item.trim())
    .join(",");
}

function parseModConfigForm(rawValue: unknown): ModConfigForm {
  const raw = asObject(rawValue);
  return {
    backend_url: getString(raw, "backend_url", MOD_CONFIG_DEFAULTS.backend_url),
    api_token: getString(raw, "api_token", MOD_CONFIG_DEFAULTS.api_token),
    server_id: getString(raw, "server_id", MOD_CONFIG_DEFAULTS.server_id),
    rcon_host: getString(raw, "rcon_host", MOD_CONFIG_DEFAULTS.rcon_host),
    op_command_token_required: getBoolean(
      raw,
      "op_command_token_required",
      MOD_CONFIG_DEFAULTS.op_command_token_required,
    ),
    op_command_token_secret: getString(
      raw,
      "op_command_token_secret",
      MOD_CONFIG_DEFAULTS.op_command_token_secret,
    ),
    batch_size: getNumber(raw, "batch_size", MOD_CONFIG_DEFAULTS.batch_size),
    flush_interval_ms: getNumber(raw, "flush_interval_ms", MOD_CONFIG_DEFAULTS.flush_interval_ms),
    spool_dir: getString(raw, "spool_dir", MOD_CONFIG_DEFAULTS.spool_dir),
    context_window_ms: getNumber(raw, "context_window_ms", MOD_CONFIG_DEFAULTS.context_window_ms),
    audit_enabled: getBoolean(raw, "audit_enabled", MOD_CONFIG_DEFAULTS.audit_enabled),
    audit_interval_minutes: getNumber(raw, "audit_interval_minutes", MOD_CONFIG_DEFAULTS.audit_interval_minutes),
    audit_players_per_tick: getNumber(raw, "audit_players_per_tick", MOD_CONFIG_DEFAULTS.audit_players_per_tick),
    scan_enabled: getBoolean(raw, "scan_enabled", MOD_CONFIG_DEFAULTS.scan_enabled),
    scan_interval_minutes: getNumber(raw, "scan_interval_minutes", MOD_CONFIG_DEFAULTS.scan_interval_minutes),
    scan_containers_per_tick: getNumber(raw, "scan_containers_per_tick", MOD_CONFIG_DEFAULTS.scan_containers_per_tick),
    scan_rs2_networks_per_tick: getNumber(raw, "scan_rs2_networks_per_tick", MOD_CONFIG_DEFAULTS.scan_rs2_networks_per_tick),
    scan_rescan_cooldown_minutes: getNumber(raw, "scan_rescan_cooldown_minutes", MOD_CONFIG_DEFAULTS.scan_rescan_cooldown_minutes),
    scan_include_containers: getBoolean(raw, "scan_include_containers", MOD_CONFIG_DEFAULTS.scan_include_containers),
    scan_include_rs2: getBoolean(raw, "scan_include_rs2", MOD_CONFIG_DEFAULTS.scan_include_rs2),
    scan_max_avg_tick_ms: getNumber(raw, "scan_max_avg_tick_ms", MOD_CONFIG_DEFAULTS.scan_max_avg_tick_ms),
    scan_max_online_players: getNumber(raw, "scan_max_online_players", MOD_CONFIG_DEFAULTS.scan_max_online_players),
    scan_world_offline_enabled: getBoolean(raw, "scan_world_offline_enabled", MOD_CONFIG_DEFAULTS.scan_world_offline_enabled),
    scan_sb_offline_enabled: getBoolean(raw, "scan_sb_offline_enabled", MOD_CONFIG_DEFAULTS.scan_sb_offline_enabled),
    scan_rs2_offline_enabled: getBoolean(raw, "scan_rs2_offline_enabled", MOD_CONFIG_DEFAULTS.scan_rs2_offline_enabled),
    scan_offline_chunks_per_tick: getNumber(raw, "scan_offline_chunks_per_tick", MOD_CONFIG_DEFAULTS.scan_offline_chunks_per_tick),
    scan_offline_sources_per_tick: getNumber(raw, "scan_offline_sources_per_tick", MOD_CONFIG_DEFAULTS.scan_offline_sources_per_tick),
    scan_offline_workers: getNumber(raw, "scan_offline_workers", MOD_CONFIG_DEFAULTS.scan_offline_workers),
    scan_offline_chunk_interval_ms: getNumber(raw, "scan_offline_chunk_interval_ms", MOD_CONFIG_DEFAULTS.scan_offline_chunk_interval_ms),
    scan_include_online_runtime: getBoolean(raw, "scan_include_online_runtime", MOD_CONFIG_DEFAULTS.scan_include_online_runtime),
    registry_upload_enabled: getBoolean(raw, "registry_upload_enabled", MOD_CONFIG_DEFAULTS.registry_upload_enabled),
    registry_upload_chunk_size: getNumber(raw, "registry_upload_chunk_size", MOD_CONFIG_DEFAULTS.registry_upload_chunk_size),
    registry_upload_languages: getArrayAsCsv(raw, "registry_upload_languages", MOD_CONFIG_DEFAULTS.registry_upload_languages),
    scan_item_filter: getArrayAsCsv(raw, "scan_item_filter", MOD_CONFIG_DEFAULTS.scan_item_filter),
    ops_log_level: getString(raw, "ops_log_level", MOD_CONFIG_DEFAULTS.ops_log_level),
  };
}

function splitCsv(value: string) {
  return value
    .split(",")
    .map((item) => item.trim())
    .filter((item) => item.length > 0);
}

function buildModConfigPayload(form: ModConfigForm, extras: Record<string, unknown>) {
  const numberOr = (value: number, fallback: number) =>
    Number.isFinite(value) ? value : fallback;
  return {
    ...extras,
    backend_url: form.backend_url.trim(),
    api_token: form.api_token,
    server_id: form.server_id.trim() || MOD_CONFIG_DEFAULTS.server_id,
    rcon_host: form.rcon_host.trim() || MOD_CONFIG_DEFAULTS.rcon_host,
    op_command_token_required: form.op_command_token_required,
    op_command_token_secret: form.op_command_token_secret,
    batch_size: numberOr(form.batch_size, MOD_CONFIG_DEFAULTS.batch_size),
    flush_interval_ms: numberOr(
      form.flush_interval_ms,
      MOD_CONFIG_DEFAULTS.flush_interval_ms,
    ),
    spool_dir: form.spool_dir.trim() || MOD_CONFIG_DEFAULTS.spool_dir,
    context_window_ms: numberOr(
      form.context_window_ms,
      MOD_CONFIG_DEFAULTS.context_window_ms,
    ),
    audit_enabled: form.audit_enabled,
    audit_interval_minutes: numberOr(
      form.audit_interval_minutes,
      MOD_CONFIG_DEFAULTS.audit_interval_minutes,
    ),
    audit_players_per_tick: numberOr(
      form.audit_players_per_tick,
      MOD_CONFIG_DEFAULTS.audit_players_per_tick,
    ),
    scan_enabled: form.scan_enabled,
    scan_interval_minutes: numberOr(
      form.scan_interval_minutes,
      MOD_CONFIG_DEFAULTS.scan_interval_minutes,
    ),
    scan_containers_per_tick: numberOr(
      form.scan_containers_per_tick,
      MOD_CONFIG_DEFAULTS.scan_containers_per_tick,
    ),
    scan_rs2_networks_per_tick: numberOr(
      form.scan_rs2_networks_per_tick,
      MOD_CONFIG_DEFAULTS.scan_rs2_networks_per_tick,
    ),
    scan_rescan_cooldown_minutes: numberOr(
      form.scan_rescan_cooldown_minutes,
      MOD_CONFIG_DEFAULTS.scan_rescan_cooldown_minutes,
    ),
    scan_include_containers: form.scan_include_containers,
    scan_include_rs2: form.scan_include_rs2,
    scan_max_avg_tick_ms: numberOr(
      form.scan_max_avg_tick_ms,
      MOD_CONFIG_DEFAULTS.scan_max_avg_tick_ms,
    ),
    scan_max_online_players: numberOr(
      form.scan_max_online_players,
      MOD_CONFIG_DEFAULTS.scan_max_online_players,
    ),
    scan_world_offline_enabled: form.scan_world_offline_enabled,
    scan_sb_offline_enabled: form.scan_sb_offline_enabled,
    scan_rs2_offline_enabled: form.scan_rs2_offline_enabled,
    scan_offline_chunks_per_tick: numberOr(
      form.scan_offline_chunks_per_tick,
      MOD_CONFIG_DEFAULTS.scan_offline_chunks_per_tick,
    ),
    scan_offline_sources_per_tick: numberOr(
      form.scan_offline_sources_per_tick,
      MOD_CONFIG_DEFAULTS.scan_offline_sources_per_tick,
    ),
    scan_offline_workers: numberOr(
      form.scan_offline_workers,
      MOD_CONFIG_DEFAULTS.scan_offline_workers,
    ),
    scan_offline_chunk_interval_ms: numberOr(
      form.scan_offline_chunk_interval_ms,
      MOD_CONFIG_DEFAULTS.scan_offline_chunk_interval_ms,
    ),
    scan_include_online_runtime: form.scan_include_online_runtime,
    registry_upload_enabled: form.registry_upload_enabled,
    registry_upload_chunk_size: numberOr(
      form.registry_upload_chunk_size,
      MOD_CONFIG_DEFAULTS.registry_upload_chunk_size,
    ),
    registry_upload_languages: splitCsv(form.registry_upload_languages),
    scan_item_filter: splitCsv(form.scan_item_filter),
    ops_log_level: form.ops_log_level.trim() || MOD_CONFIG_DEFAULTS.ops_log_level,
  };
}

function ConfigKeyLabel({ lang, field }: { lang: UiLang; field: keyof ModConfigForm }) {
  return (
    <Label
      title={field}
      className="cursor-help underline decoration-dotted underline-offset-4"
    >
      {fieldLabel(lang, field)}
    </Label>
  );
}

export function System() {
  const { settings, updateSettings } = useSettings();
  const { theme, setTheme } = useTheme();
  const { variants } = useMotionPresets();

  const [baseUrl, setBaseUrl] = React.useState(settings.baseUrl);
  const [apiToken, setApiToken] = React.useState(settings.apiToken);
  const [lang, setLang] = React.useState(settings.lang || "zh_cn");
  const uiLang = resolveUiLang(lang);

  const [content, setContent] = React.useState("");
  const [loading, setLoading] = React.useState(true);
  const [saving, setSaving] = React.useState(false);
  const [backendRuntime, setBackendRuntime] =
    React.useState<BackendRuntimeStatus | null>(null);
  const [modServerId, setModServerId] = React.useState("server-01");
  const [modConfigForm, setModConfigForm] = React.useState<ModConfigForm>(
    MOD_CONFIG_DEFAULTS,
  );
  const [modConfigExtras, setModConfigExtras] = React.useState<
    Record<string, unknown>
  >({});
  const [modConfigRevision, setModConfigRevision] = React.useState<number | null>(
    null,
  );
  const [modConfigDirty, setModConfigDirty] = React.useState(false);
  const [publishingModConfig, setPublishingModConfig] = React.useState(false);

  const modConfigQuery = useQuery({
    queryKey: [
      "mod-config-current",
      settings.baseUrl,
      settings.apiToken,
      modServerId,
    ],
    queryFn: () =>
      fetchModConfigCurrent(settings.baseUrl, settings.apiToken, modServerId),
    refetchInterval: 15_000,
  });

  const modConfigAckQuery = useQuery({
    queryKey: [
      "mod-config-ack-last",
      settings.baseUrl,
      settings.apiToken,
      modServerId,
    ],
    queryFn: () =>
      fetchModConfigAckLast(settings.baseUrl, settings.apiToken, modServerId),
    refetchInterval: 10_000,
  });

  React.useEffect(() => {
    setBaseUrl(settings.baseUrl);
    setApiToken(settings.apiToken);
    setLang(settings.lang || "zh_cn");
  }, [settings.apiToken, settings.baseUrl, settings.lang]);

  React.useEffect(() => {
    const envelope = modConfigQuery.data;
    if (!envelope) {
      if (!modConfigDirty) {
        setModConfigForm(MOD_CONFIG_DEFAULTS);
        setModConfigExtras({});
        setModConfigRevision(null);
      }
      return;
    }
    const changedRevision = modConfigRevision !== envelope.revision;
    if (changedRevision || !modConfigDirty) {
      setModConfigForm(parseModConfigForm(envelope.config));
      const nextExtras = Object.fromEntries(
        Object.entries(envelope.config).filter(([key]) => !MOD_CONFIG_KEYS.has(key as keyof ModConfigForm)),
      );
      setModConfigExtras(nextExtras);
      setModConfigDirty(false);
    }
    setModConfigRevision(envelope.revision);
  }, [modConfigDirty, modConfigQuery.data, modConfigRevision]);

  const loadConfig = React.useCallback(async () => {
    try {
      setLoading(true);
      const data = await invoke<string>("backend_config_get");
      setContent(data);
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "加载配置失败");
    } finally {
      setLoading(false);
    }
  }, []);

  const loadRuntimeStatus = React.useCallback(async () => {
    try {
      const data = await invoke<BackendRuntimeStatus>("backend_runtime_status");
      setBackendRuntime(data);
    } catch {
      setBackendRuntime(null);
    }
  }, []);

  React.useEffect(() => {
    loadConfig();
    loadRuntimeStatus();
  }, [loadConfig, loadRuntimeStatus]);

  function saveConnection() {
    updateSettings({
      baseUrl: baseUrl.trim(),
      apiToken: apiToken.trim(),
      lang,
      debugMode: settings.debugMode,
    });
    toast.success("连接设置已保存");
  }

  function onDebugModeChange(value: string) {
    const nextEnabled = value === "on";
    updateSettings({
      ...settings,
      debugMode: nextEnabled,
    });
    toast.success(nextEnabled ? "调试模式已开启" : "调试模式已关闭");
  }

  function openDebugConsole() {
    window.dispatchEvent(new Event(OPEN_DEBUG_EVENT));
  }

  function updateModConfigField<K extends keyof ModConfigForm>(
    key: K,
    value: ModConfigForm[K],
  ) {
    setModConfigForm((prev) => ({ ...prev, [key]: value }));
    setModConfigDirty(true);
  }

  async function saveConfig(restart: boolean) {
    try {
      setSaving(true);
      await invoke("backend_config_set", { content });
      if (restart) {
        await invoke("backend_restart");
        await loadRuntimeStatus();
      }
      toast.success(restart ? "已保存并重启后端" : "配置已保存");
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "保存失败");
    } finally {
      setSaving(false);
    }
  }

  async function restartBackend() {
    try {
      setSaving(true);
      await invoke("backend_restart");
      await loadRuntimeStatus();
      toast.success("后端已重启");
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "重启失败");
    } finally {
      setSaving(false);
    }
  }

  async function refreshModConfig() {
    try {
      await Promise.all([modConfigQuery.refetch(), modConfigAckQuery.refetch()]);
      toast.success("已刷新动态配置状态");
    } catch {
      toast.error("刷新动态配置失败");
    }
  }

  async function publishModConfig() {
    const serverId = modServerId.trim() || "server-01";
    const payloadConfig = buildModConfigPayload(modConfigForm, modConfigExtras);

    try {
      setPublishingModConfig(true);
      const envelope = await updateModConfigCurrent(
        settings.baseUrl,
        settings.apiToken,
        serverId,
        {
          server_id: serverId,
          updated_by: "desktop",
          config: payloadConfig,
        },
      );
      setModConfigRevision(envelope.revision);
      setModConfigDirty(false);
      setModConfigForm(parseModConfigForm(envelope.config));
      const nextExtras = Object.fromEntries(
        Object.entries(envelope.config).filter(([key]) => !MOD_CONFIG_KEYS.has(key as keyof ModConfigForm)),
      );
      setModConfigExtras(nextExtras);
      await Promise.all([modConfigQuery.refetch(), modConfigAckQuery.refetch()]);
      toast.success(`配置已发布，revision=${envelope.revision}`);
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "配置发布失败");
    } finally {
      setPublishingModConfig(false);
    }
  }

  return (
    <motion.div className="section-stack" variants={variants.listStagger} initial="initial" animate="enter">
      <motion.section className="section" variants={variants.sectionReveal}>
        <div className="section-header">
          <div>
            <div className="section-title">连接与外观</div>
            <div className="section-meta">管理后端连接、语言与主题设置</div>
            <div className="section-meta">
              嵌入后端: {backendRuntime?.running ? "运行中" : "未运行"}
              {backendRuntime?.last_error
                ? `（${backendRuntime.last_error}）`
                : ""}
            </div>
          </div>
          <Button onClick={saveConnection}>保存设置</Button>
        </div>

        <div className="grid gap-4 lg:grid-cols-2">
          <div className="grid gap-2">
            <Label htmlFor="base-url">后端地址</Label>
            <Input
              id="base-url"
              value={baseUrl}
              onChange={(event) => setBaseUrl(event.target.value)}
              placeholder="http://127.0.0.1:3234"
            />
          </div>

          <div className="grid gap-2">
            <Label htmlFor="api-token">API Token</Label>
            <Input
              id="api-token"
              type="password"
              value={apiToken}
              onChange={(event) => setApiToken(event.target.value)}
              placeholder="Bearer token"
            />
          </div>

          <div className="grid gap-2">
            <Label>搜索语言</Label>
            <Select value={lang} onValueChange={setLang}>
              <SelectTrigger>
                <SelectValue placeholder="选择语言" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="zh_cn">中文</SelectItem>
                <SelectItem value="en_us">English</SelectItem>
              </SelectContent>
            </Select>
          </div>

          <div className="grid gap-2">
            <Label>主题</Label>
            <Select value={theme ?? "system"} onValueChange={setTheme}>
              <SelectTrigger>
                <SelectValue placeholder="选择主题" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="system">跟随系统</SelectItem>
                <SelectItem value="light">亮色</SelectItem>
                <SelectItem value="dark">暗色</SelectItem>
              </SelectContent>
            </Select>
          </div>

          <div className="grid gap-2">
            <Label>调试模式</Label>
            <Select
              value={settings.debugMode ? "on" : "off"}
              onValueChange={onDebugModeChange}
            >
              <SelectTrigger>
                <SelectValue placeholder="选择模式" />
              </SelectTrigger>
              <SelectContent>
                <SelectItem value="off">关闭</SelectItem>
                <SelectItem value="on">开启</SelectItem>
              </SelectContent>
            </Select>
          </div>
        </div>
      </motion.section>

      {settings.debugMode ? (
        <motion.section className="section" variants={variants.sectionReveal}>
          <div className="section-header">
            <div>
              <div className="section-title">Debug 模式</div>
              <div className="section-meta">
                调试面板已常驻启用，可在任意页面右下角浮动按钮打开。
              </div>
            </div>
            <div className="flex flex-wrap gap-2">
              <Button variant="secondary" onClick={openDebugConsole}>
                打开调试面板
              </Button>
            </div>
          </div>
        </motion.section>
      ) : null}

      <motion.section className="section" variants={variants.sectionReveal}>
        <div className="section-header">
          <div>
            <div className="section-title">
              {tr(uiLang, "Mod 动态配置", "Mod Dynamic Config")}
            </div>
            <div className="section-meta">
              {tr(
                uiLang,
                "发布后会生成 revision，mod 通过 WS + pull 同步并回写本地 lattice.toml。",
                "Publishing creates a new revision. The mod syncs through WS + pull fallback and persists to local lattice.toml.",
              )}
            </div>
          </div>
          <div className="flex flex-wrap gap-2">
            <Button
              variant="secondary"
              onClick={refreshModConfig}
              disabled={publishingModConfig}
            >
              {tr(uiLang, "刷新状态", "Refresh Status")}
            </Button>
            <Button onClick={publishModConfig} disabled={publishingModConfig}>
              {publishingModConfig
                ? tr(uiLang, "发布中...", "Publishing...")
                : tr(uiLang, "发布配置", "Publish Config")}
            </Button>
          </div>
        </div>

        <div className="grid gap-4 lg:grid-cols-2">
          <div className="grid gap-2">
            <Label>{tr(uiLang, "目标服务器", "Target Server")}</Label>
            <Input
              value={modServerId}
              onChange={(event) => setModServerId(event.target.value)}
              placeholder="server-01"
            />
          </div>
          <div className="grid gap-2">
            <Label>{tr(uiLang, "当前 Revision", "Current Revision")}</Label>
            <div className="flex items-center gap-2 rounded-md border border-border/60 bg-muted/18 px-3 py-2 text-sm">
              <span className="text-foreground">
                {modConfigQuery.data?.revision ?? "-"}
              </span>
              {modConfigQuery.data?.updated_by ? (
                <span className="text-xs text-muted-foreground">
                  by {modConfigQuery.data.updated_by}
                </span>
              ) : null}
              <span className="text-xs text-muted-foreground">
                {formatTimestamp(modConfigQuery.data?.updated_at_ms ?? null)}
              </span>
            </div>
          </div>
        </div>

        <div className="mt-4 grid gap-3">
          <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
            <span>{tr(uiLang, "回执状态", "Ack Status")}</span>
            <Badge
              className={
                modConfigAckQuery.data?.status === "APPLIED"
                  ? statusBadgeClass.ok
                  : modConfigAckQuery.data?.status === "PARTIAL"
                    ? statusBadgeClass.medium
                    : modConfigAckQuery.data
                      ? statusBadgeClass.down
                      : statusBadgeClass.info
              }
            >
              {modConfigAckQuery.data?.status ||
                tr(uiLang, "无回执", "No Ack Yet")}
            </Badge>
            <span>
              revision {modConfigAckQuery.data?.revision ?? "-"}
            </span>
            <span>{formatTimestamp(modConfigAckQuery.data?.applied_at_ms ?? null)}</span>
          </div>
          {modConfigAckQuery.data?.message ? (
            <div className="text-xs text-muted-foreground">
              {tr(uiLang, "回执消息", "Ack Message")}:{" "}
              {modConfigAckQuery.data.message}
            </div>
          ) : null}
          {(modConfigAckQuery.data?.changed_keys?.length ?? 0) > 0 ? (
            <div className="text-xs text-muted-foreground">
              {tr(uiLang, "生效字段", "Applied Keys")}:{" "}
              {modConfigAckQuery.data?.changed_keys.join(", ")}
            </div>
          ) : null}
        </div>

        <div className="mt-4 space-y-4">
          <div className="section-subtitle">
            {tr(uiLang, "基础配置", "Base Settings")}
          </div>
          <div className="grid gap-4 lg:grid-cols-2">
            <div className="grid gap-2">
              <ConfigKeyLabel lang={uiLang} field="backend_url" />
              <Input
                value={modConfigForm.backend_url}
                onChange={(event) =>
                  updateModConfigField("backend_url", event.target.value)
                }
                placeholder="http://127.0.0.1:3234"
              />
            </div>
            <div className="grid gap-2">
              <ConfigKeyLabel lang={uiLang} field="api_token" />
              <Input
                type="password"
                value={modConfigForm.api_token}
                onChange={(event) =>
                  updateModConfigField("api_token", event.target.value)
                }
              />
            </div>
            <div className="grid gap-2">
              <ConfigKeyLabel lang={uiLang} field="server_id" />
              <Input
                value={modConfigForm.server_id}
                onChange={(event) =>
                  updateModConfigField("server_id", event.target.value)
                }
              />
            </div>
            <div className="grid gap-2">
              <ConfigKeyLabel lang={uiLang} field="rcon_host" />
              <Input
                value={modConfigForm.rcon_host}
                onChange={(event) =>
                  updateModConfigField("rcon_host", event.target.value)
                }
              />
            </div>
            <div className="grid gap-2">
              <ConfigKeyLabel lang={uiLang} field="op_command_token_required" />
              <Select
                value={modConfigForm.op_command_token_required ? "true" : "false"}
                onValueChange={(value) =>
                  updateModConfigField("op_command_token_required", value === "true")
                }
              >
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="true">{tr(uiLang, "开启", "Enabled")}</SelectItem>
                  <SelectItem value="false">{tr(uiLang, "关闭", "Disabled")}</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="grid gap-2">
              <ConfigKeyLabel lang={uiLang} field="op_command_token_secret" />
              <Input
                type="password"
                value={modConfigForm.op_command_token_secret}
                onChange={(event) =>
                  updateModConfigField("op_command_token_secret", event.target.value)
                }
                placeholder={tr(uiLang, "留空表示禁用签名校验", "Empty means no signing key")}
              />
            </div>
            <div className="grid gap-2">
              <ConfigKeyLabel lang={uiLang} field="spool_dir" />
              <Input
                value={modConfigForm.spool_dir}
                onChange={(event) =>
                  updateModConfigField("spool_dir", event.target.value)
                }
              />
            </div>
            <div className="grid gap-2">
              <ConfigKeyLabel lang={uiLang} field="ops_log_level" />
              <Select
                value={modConfigForm.ops_log_level.toUpperCase()}
                onValueChange={(value) =>
                  updateModConfigField("ops_log_level", value)
                }
              >
                <SelectTrigger>
                  <SelectValue
                    placeholder={tr(uiLang, "选择日志级别", "Select Log Level")}
                  />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="DEBUG">DEBUG</SelectItem>
                  <SelectItem value="INFO">INFO</SelectItem>
                  <SelectItem value="WARN">WARN</SelectItem>
                  <SelectItem value="ERROR">ERROR</SelectItem>
                </SelectContent>
              </Select>
            </div>
          </div>

          <div className="section-subtitle">
            {tr(uiLang, "事件与审计", "Events & Audit")}
          </div>
          <div className="grid gap-4 lg:grid-cols-3">
            <div className="grid gap-2">
              <ConfigKeyLabel lang={uiLang} field="batch_size" />
              <Input
                type="number"
                value={modConfigForm.batch_size}
                onChange={(event) =>
                  updateModConfigField("batch_size", Number(event.target.value || 0))
                }
              />
            </div>
            <div className="grid gap-2">
              <ConfigKeyLabel lang={uiLang} field="flush_interval_ms" />
              <Input
                type="number"
                value={modConfigForm.flush_interval_ms}
                onChange={(event) =>
                  updateModConfigField(
                    "flush_interval_ms",
                    Number(event.target.value || 0),
                  )
                }
              />
            </div>
            <div className="grid gap-2">
              <ConfigKeyLabel lang={uiLang} field="context_window_ms" />
              <Input
                type="number"
                value={modConfigForm.context_window_ms}
                onChange={(event) =>
                  updateModConfigField(
                    "context_window_ms",
                    Number(event.target.value || 0),
                  )
                }
              />
            </div>
            <div className="grid gap-2">
              <ConfigKeyLabel lang={uiLang} field="audit_enabled" />
              <Select
                value={modConfigForm.audit_enabled ? "true" : "false"}
                onValueChange={(value) =>
                  updateModConfigField("audit_enabled", value === "true")
                }
              >
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="true">{tr(uiLang, "开启", "Enabled")}</SelectItem>
                  <SelectItem value="false">{tr(uiLang, "关闭", "Disabled")}</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="grid gap-2">
              <ConfigKeyLabel lang={uiLang} field="audit_interval_minutes" />
              <Input
                type="number"
                value={modConfigForm.audit_interval_minutes}
                onChange={(event) =>
                  updateModConfigField(
                    "audit_interval_minutes",
                    Number(event.target.value || 0),
                  )
                }
              />
            </div>
            <div className="grid gap-2">
              <ConfigKeyLabel lang={uiLang} field="audit_players_per_tick" />
              <Input
                type="number"
                value={modConfigForm.audit_players_per_tick}
                onChange={(event) =>
                  updateModConfigField(
                    "audit_players_per_tick",
                    Number(event.target.value || 0),
                  )
                }
              />
            </div>
          </div>

          <div className="section-subtitle">
            {tr(uiLang, "扫描稳态参数", "Scan Steady-State")}
          </div>
          <div className="grid gap-4 lg:grid-cols-3">
            <div className="grid gap-2">
              <ConfigKeyLabel lang={uiLang} field="scan_enabled" />
              <Select
                value={modConfigForm.scan_enabled ? "true" : "false"}
                onValueChange={(value) =>
                  updateModConfigField("scan_enabled", value === "true")
                }
              >
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="true">{tr(uiLang, "开启", "Enabled")}</SelectItem>
                  <SelectItem value="false">{tr(uiLang, "关闭", "Disabled")}</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="grid gap-2">
              <ConfigKeyLabel lang={uiLang} field="scan_interval_minutes" />
              <Input
                type="number"
                value={modConfigForm.scan_interval_minutes}
                onChange={(event) =>
                  updateModConfigField(
                    "scan_interval_minutes",
                    Number(event.target.value || 0),
                  )
                }
              />
            </div>
            <div className="grid gap-2">
              <ConfigKeyLabel lang={uiLang} field="scan_rescan_cooldown_minutes" />
              <Input
                type="number"
                value={modConfigForm.scan_rescan_cooldown_minutes}
                onChange={(event) =>
                  updateModConfigField(
                    "scan_rescan_cooldown_minutes",
                    Number(event.target.value || 0),
                  )
                }
              />
            </div>
            <div className="grid gap-2">
              <ConfigKeyLabel lang={uiLang} field="scan_containers_per_tick" />
              <Input
                type="number"
                value={modConfigForm.scan_containers_per_tick}
                onChange={(event) =>
                  updateModConfigField(
                    "scan_containers_per_tick",
                    Number(event.target.value || 0),
                  )
                }
              />
            </div>
            <div className="grid gap-2">
              <ConfigKeyLabel lang={uiLang} field="scan_rs2_networks_per_tick" />
              <Input
                type="number"
                value={modConfigForm.scan_rs2_networks_per_tick}
                onChange={(event) =>
                  updateModConfigField(
                    "scan_rs2_networks_per_tick",
                    Number(event.target.value || 0),
                  )
                }
              />
            </div>
            <div className="grid gap-2">
              <ConfigKeyLabel lang={uiLang} field="scan_max_avg_tick_ms" />
              <Input
                type="number"
                value={modConfigForm.scan_max_avg_tick_ms}
                onChange={(event) =>
                  updateModConfigField(
                    "scan_max_avg_tick_ms",
                    Number(event.target.value || 0),
                  )
                }
              />
            </div>
            <div className="grid gap-2">
              <ConfigKeyLabel lang={uiLang} field="scan_max_online_players" />
              <Input
                type="number"
                value={modConfigForm.scan_max_online_players}
                onChange={(event) =>
                  updateModConfigField(
                    "scan_max_online_players",
                    Number(event.target.value || 0),
                  )
                }
              />
            </div>
            <div className="grid gap-2">
              <ConfigKeyLabel lang={uiLang} field="scan_include_containers" />
              <Select
                value={modConfigForm.scan_include_containers ? "true" : "false"}
                onValueChange={(value) =>
                  updateModConfigField("scan_include_containers", value === "true")
                }
              >
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="true">{tr(uiLang, "开启", "Enabled")}</SelectItem>
                  <SelectItem value="false">{tr(uiLang, "关闭", "Disabled")}</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="grid gap-2">
              <ConfigKeyLabel lang={uiLang} field="scan_include_rs2" />
              <Select
                value={modConfigForm.scan_include_rs2 ? "true" : "false"}
                onValueChange={(value) =>
                  updateModConfigField("scan_include_rs2", value === "true")
                }
              >
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="true">{tr(uiLang, "开启", "Enabled")}</SelectItem>
                  <SelectItem value="false">{tr(uiLang, "关闭", "Disabled")}</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="grid gap-2">
              <ConfigKeyLabel lang={uiLang} field="scan_world_offline_enabled" />
              <Select
                value={modConfigForm.scan_world_offline_enabled ? "true" : "false"}
                onValueChange={(value) =>
                  updateModConfigField(
                    "scan_world_offline_enabled",
                    value === "true",
                  )
                }
              >
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="true">{tr(uiLang, "开启", "Enabled")}</SelectItem>
                  <SelectItem value="false">{tr(uiLang, "关闭", "Disabled")}</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="grid gap-2">
              <ConfigKeyLabel lang={uiLang} field="scan_sb_offline_enabled" />
              <Select
                value={modConfigForm.scan_sb_offline_enabled ? "true" : "false"}
                onValueChange={(value) =>
                  updateModConfigField("scan_sb_offline_enabled", value === "true")
                }
              >
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="true">{tr(uiLang, "开启", "Enabled")}</SelectItem>
                  <SelectItem value="false">{tr(uiLang, "关闭", "Disabled")}</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="grid gap-2">
              <ConfigKeyLabel lang={uiLang} field="scan_rs2_offline_enabled" />
              <Select
                value={modConfigForm.scan_rs2_offline_enabled ? "true" : "false"}
                onValueChange={(value) =>
                  updateModConfigField("scan_rs2_offline_enabled", value === "true")
                }
              >
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="true">{tr(uiLang, "开启", "Enabled")}</SelectItem>
                  <SelectItem value="false">{tr(uiLang, "关闭", "Disabled")}</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="grid gap-2">
              <ConfigKeyLabel lang={uiLang} field="scan_offline_chunks_per_tick" />
              <Input
                type="number"
                value={modConfigForm.scan_offline_chunks_per_tick}
                onChange={(event) =>
                  updateModConfigField(
                    "scan_offline_chunks_per_tick",
                    Number(event.target.value || 0),
                  )
                }
              />
            </div>
            <div className="grid gap-2">
              <ConfigKeyLabel lang={uiLang} field="scan_offline_sources_per_tick" />
              <Input
                type="number"
                value={modConfigForm.scan_offline_sources_per_tick}
                onChange={(event) =>
                  updateModConfigField(
                    "scan_offline_sources_per_tick",
                    Number(event.target.value || 0),
                  )
                }
              />
            </div>
            <div className="grid gap-2">
              <ConfigKeyLabel lang={uiLang} field="scan_offline_workers" />
              <Input
                type="number"
                value={modConfigForm.scan_offline_workers}
                onChange={(event) =>
                  updateModConfigField(
                    "scan_offline_workers",
                    Number(event.target.value || 0),
                  )
                }
              />
            </div>
            <div className="grid gap-2">
              <ConfigKeyLabel lang={uiLang} field="scan_offline_chunk_interval_ms" />
              <Input
                type="number"
                value={modConfigForm.scan_offline_chunk_interval_ms}
                onChange={(event) =>
                  updateModConfigField(
                    "scan_offline_chunk_interval_ms",
                    Number(event.target.value || 0),
                  )
                }
              />
            </div>
            <div className="grid gap-2">
              <ConfigKeyLabel lang={uiLang} field="scan_include_online_runtime" />
              <Select
                value={modConfigForm.scan_include_online_runtime ? "true" : "false"}
                onValueChange={(value) =>
                  updateModConfigField(
                    "scan_include_online_runtime",
                    value === "true",
                  )
                }
              >
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="true">{tr(uiLang, "开启", "Enabled")}</SelectItem>
                  <SelectItem value="false">{tr(uiLang, "关闭", "Disabled")}</SelectItem>
                </SelectContent>
              </Select>
            </div>
          </div>

          <div className="section-subtitle">
            {tr(uiLang, "注册表与过滤", "Registry & Filter")}
          </div>
          <div className="grid gap-4 lg:grid-cols-3">
            <div className="grid gap-2">
              <ConfigKeyLabel lang={uiLang} field="registry_upload_enabled" />
              <Select
                value={modConfigForm.registry_upload_enabled ? "true" : "false"}
                onValueChange={(value) =>
                  updateModConfigField("registry_upload_enabled", value === "true")
                }
              >
                <SelectTrigger>
                  <SelectValue />
                </SelectTrigger>
                <SelectContent>
                  <SelectItem value="true">{tr(uiLang, "开启", "Enabled")}</SelectItem>
                  <SelectItem value="false">{tr(uiLang, "关闭", "Disabled")}</SelectItem>
                </SelectContent>
              </Select>
            </div>
            <div className="grid gap-2">
              <ConfigKeyLabel lang={uiLang} field="registry_upload_chunk_size" />
              <Input
                type="number"
                value={modConfigForm.registry_upload_chunk_size}
                onChange={(event) =>
                  updateModConfigField(
                    "registry_upload_chunk_size",
                    Number(event.target.value || 0),
                  )
                }
              />
            </div>
            <div className="grid gap-2 lg:col-span-3">
              <ConfigKeyLabel lang={uiLang} field="registry_upload_languages" />
              <Input
                value={modConfigForm.registry_upload_languages}
                onChange={(event) =>
                  updateModConfigField(
                    "registry_upload_languages",
                    event.target.value,
                  )
                }
                placeholder="zh_cn,en_us"
              />
            </div>
            <div className="grid gap-2 lg:col-span-3">
              <ConfigKeyLabel lang={uiLang} field="scan_item_filter" />
              <Input
                value={modConfigForm.scan_item_filter}
                onChange={(event) =>
                  updateModConfigField("scan_item_filter", event.target.value)
                }
                placeholder="minecraft:diamond,minecraft:netherite_ingot"
              />
            </div>
          </div>

          <div className="text-xs text-muted-foreground">
            {tr(
              uiLang,
              "已启用固定字段表单；发布时会保留服务端中未在表单展示的扩展字段。",
              "Using fixed-key form; unknown server-side extension keys are preserved on publish.",
            )}
          </div>
          {modConfigQuery.isError ? (
            <div className="mt-2 text-xs text-destructive">
              {tr(uiLang, "拉取失败", "Load Failed")}:{" "}
              {(modConfigQuery.error as Error).message}
            </div>
          ) : null}
          {modConfigAckQuery.isError ? (
            <div className="mt-2 text-xs text-destructive">
              {tr(uiLang, "回执查询失败", "Ack Query Failed")}:{" "}
              {(modConfigAckQuery.error as Error).message}
            </div>
          ) : null}
        </div>
      </motion.section>

      <motion.section className="section" variants={variants.sectionReveal}>
        <div className="section-header">
          <div>
            <div className="section-title">后端配置</div>
            <div className="section-meta">该配置会写入应用本地文件，修改后建议重启后端。</div>
          </div>
          <div className="flex flex-wrap gap-2">
            <Button variant="secondary" onClick={loadConfig} disabled={loading || saving}>
              重新加载
            </Button>
            <Button variant="secondary" onClick={restartBackend} disabled={saving}>
              仅重启后端
            </Button>
            <Button onClick={() => saveConfig(true)} disabled={saving}>
              保存并重启
            </Button>
          </div>
        </div>

        <Textarea
          className="min-h-[420px] font-mono text-xs"
          value={content}
          onChange={(event) => setContent(event.target.value)}
          readOnly={loading}
        />

        <div className="mt-4 flex justify-end gap-2">
          <Button variant="ghost" onClick={() => saveConfig(false)} disabled={saving}>
            仅保存
          </Button>
        </div>
      </motion.section>
    </motion.div>
  );
}
