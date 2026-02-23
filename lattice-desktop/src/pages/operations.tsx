import * as React from "react";
import { invoke } from "@tauri-apps/api/core";
import { isTauri } from "@tauri-apps/api/core";
import { openUrl } from "@tauri-apps/plugin-opener";
import { useQuery } from "@tanstack/react-query";
import { motion } from "motion/react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Textarea } from "@/components/ui/textarea";
import {
  fetchAlertDeliveries,
  fetchAlertStatus,
  fetchMetrics,
  fetchTaskProgress,
  pingHealth,
  pingReady,
} from "@/lib/api";
import { parsePrometheusMetrics } from "@/lib/metrics";
import { useMotionPresets } from "@/lib/motion";
import { statusBadgeClass } from "@/lib/status-badge";
import { useSettings } from "@/lib/settings";
import type { AlertDeliveryRecord, TaskProgress } from "@/lib/types";

const tauriReady = isTauri();

type RconConfig = {
  host: string;
  port: number;
  password?: string | null;
  enabled?: boolean;
  source?: string | null;
};

type RconStatus = {
  connected: boolean;
};

type ConsoleEntry = {
  id: string;
  kind: "command" | "response" | "error";
  text: string;
};

const RCON_HISTORY_STORAGE_KEY = "lattice_rcon_history_v1";
const RCON_HISTORY_LIMIT = 240;
const PROGRESS_ACK_GRACE_MS = 2 * 60 * 1000;
const PROGRESS_CLOCK_SKEW_MS = 1000;

function loadRconHistory(): ConsoleEntry[] {
  try {
    const raw = localStorage.getItem(RCON_HISTORY_STORAGE_KEY);
    if (!raw) {
      return [];
    }
    const parsed = JSON.parse(raw) as ConsoleEntry[];
    if (!Array.isArray(parsed)) {
      return [];
    }
    return parsed.filter(
      (item) =>
        typeof item?.id === "string" &&
        typeof item?.text === "string" &&
        (item?.kind === "command" ||
          item?.kind === "response" ||
          item?.kind === "error"),
    );
  } catch {
    return [];
  }
}

function saveRconHistory(entries: ConsoleEntry[]) {
  try {
    localStorage.setItem(
      RCON_HISTORY_STORAGE_KEY,
      JSON.stringify(entries.slice(-RCON_HISTORY_LIMIT)),
    );
  } catch {
    // Ignore storage errors silently to avoid breaking console interactions.
  }
}

const commands = [
  {
    title: "上传物品注册表",
    command: "/lattice registry",
    description: "同步物品库与多语言名称，供搜索与规则管理使用。",
  },
  {
    title: "玩家背包审计",
    command: "/lattice audit all",
    description: "立即生成全服背包审计快照。",
  },
  {
    title: "存储扫描",
    command: "/lattice scan",
    description: "触发全量资产扫描（全地图箱子 + SB + RS2 + 在线补充）。",
  },
];

function normalizeBaseUrl(baseUrl: string) {
  return baseUrl.trim().replace(/\/$/, "");
}

function normalizeConfig(config: RconConfig): RconConfig {
  const host = config.host?.trim() || "127.0.0.1";
  const port = Number(config.port) || 25575;
  const password = config.password?.toString() ?? "";
  return {
    ...config,
    host,
    port,
    password,
  };
}

export function Operations() {
  const { settings } = useSettings();
  const { variants } = useMotionPresets();

  const [config, setConfig] = React.useState<RconConfig>({
    host: "127.0.0.1",
    port: 25575,
    password: "",
    enabled: false,
    source: "local",
  });
  const [loading, setLoading] = React.useState(true);
  const [saving, setSaving] = React.useState(false);
  const [connecting, setConnecting] = React.useState(false);
  const [connected, setConnected] = React.useState(false);
  const [rconSettingsOpen, setRconSettingsOpen] = React.useState(false);
  const [command, setCommand] = React.useState("");
  const [history, setHistory] = React.useState<ConsoleEntry[]>(() =>
    loadRconHistory(),
  );
  const [scanQueuedAt, setScanQueuedAt] = React.useState<number | null>(null);
  const [auditQueuedAt, setAuditQueuedAt] = React.useState<number | null>(null);
  const outputRef = React.useRef<HTMLDivElement | null>(null);
  const autoConnectTriedRef = React.useRef(false);

  const healthQuery = useQuery({
    queryKey: ["ops-health", settings.baseUrl],
    queryFn: () => pingHealth(settings.baseUrl),
    refetchInterval: 15_000,
  });

  const readyQuery = useQuery({
    queryKey: ["ops-ready", settings.baseUrl],
    queryFn: () => pingReady(settings.baseUrl),
    refetchInterval: 15_000,
  });

  const alertQuery = useQuery({
    queryKey: ["ops-alert-target", settings.baseUrl, settings.apiToken],
    queryFn: () => fetchAlertStatus(settings.baseUrl, settings.apiToken),
    refetchInterval: 15_000,
  });

  const metricsQuery = useQuery({
    queryKey: ["ops-metrics", settings.baseUrl, settings.apiToken],
    queryFn: () => fetchMetrics(settings.baseUrl, settings.apiToken),
    refetchInterval: 15_000,
  });

  const taskQuery = useQuery({
    queryKey: ["task-progress", settings.baseUrl, settings.apiToken],
    queryFn: () => fetchTaskProgress(settings.baseUrl, settings.apiToken),
    refetchInterval: 2000,
  });

  const alertDeliveriesQuery = useQuery({
    queryKey: ["ops-alert-deliveries", settings.baseUrl, settings.apiToken],
    queryFn: () => fetchAlertDeliveries(settings.baseUrl, settings.apiToken, 20),
    refetchInterval: 15_000,
  });

  const metrics = metricsQuery.data
    ? parsePrometheusMetrics(metricsQuery.data)
    : null;
  const baseUrl = normalizeBaseUrl(settings.baseUrl);
  const audit = taskQuery.data?.audit;
  const scan = taskQuery.data?.scan;
  const lastAlertDelivery = alertDeliveriesQuery.data?.[0] ?? null;

  const refreshStatus = React.useCallback(async () => {
    if (!tauriReady) {
      setConnected(false);
      return;
    }
    try {
      const status = await invoke<RconStatus>("rcon_status");
      setConnected(status.connected);
    } catch {
      setConnected(false);
    }
  }, []);

  const connectWithConfig = React.useCallback(
    async (nextConfig: RconConfig, silent = false) => {
      if (!tauriReady) {
        if (!silent) {
          toast.error("浏览器模式无法连接 RCON");
        }
        return;
      }
      try {
        setConnecting(true);
        await invoke("rcon_connect", { config: nextConfig });
        setConnected(true);
        if (!silent) {
          toast.success("RCON 已连接");
        }
      } catch (error) {
        setConnected(false);
        if (!silent) {
          toast.error(error instanceof Error ? error.message : "连接失败");
        }
      } finally {
        setConnecting(false);
      }
    },
    [],
  );

  const loadConfig = React.useCallback(async () => {
    if (!tauriReady) {
      setLoading(false);
      return;
    }
    try {
      setLoading(true);
      const loaded = await invoke<RconConfig>("rcon_config_get");
      const next = normalizeConfig({
        host: loaded.host,
        port: loaded.port,
        password: loaded.password,
        enabled: loaded.enabled,
        source: loaded.source,
      });
      setConfig((prev) => normalizeConfig({ ...prev, ...loaded }));

      if (!autoConnectTriedRef.current) {
        autoConnectTriedRef.current = true;
        const hasPassword = Boolean(next.password?.trim());
        if (hasPassword) {
          void connectWithConfig(next, true);
        }
      }
    } catch (error) {
      toast.error(
        error instanceof Error ? error.message : "加载 RCON 配置失败",
      );
    } finally {
      setLoading(false);
    }
  }, [connectWithConfig]);

  React.useEffect(() => {
    loadConfig();
    refreshStatus();
  }, [loadConfig, refreshStatus]);

  React.useEffect(() => {
    if (!outputRef.current) {
      return;
    }
    outputRef.current.scrollTop = outputRef.current.scrollHeight;
  }, [history]);

  React.useEffect(() => {
    saveRconHistory(history);
  }, [history]);

  const appendHistory = React.useCallback((entry: ConsoleEntry) => {
    setHistory((prev) => [...prev, entry].slice(-RCON_HISTORY_LIMIT));
  }, []);

  function percent(progress?: TaskProgress) {
    if (!progress || progress.counters.total <= 0) {
      return 0;
    }
    return Math.min(
      100,
      Math.round((progress.counters.done / progress.counters.total) * 100),
    );
  }

  function hasProgressAckAfterQueue(
    progress: TaskProgress | undefined,
    queuedAt: number | null,
  ) {
    if (!progress || !queuedAt || progress.updated_at <= 0) {
      return false;
    }
    return progress.updated_at + PROGRESS_CLOCK_SKEW_MS >= queuedAt;
  }

  function formatElapsed(ms: number) {
    if (ms < 10_000) {
      return "刚刚";
    }
    if (ms < 60_000) {
      return `${Math.max(1, Math.floor(ms / 1000))} 秒前`;
    }
    if (ms < 60 * 60 * 1000) {
      return `${Math.floor(ms / 60_000)} 分钟前`;
    }
    return `${Math.floor(ms / (60 * 60 * 1000))} 小时前`;
  }

  function taskRuntimeLabel(
    progress: TaskProgress | undefined,
    queuedAt: number | null,
  ) {
    if (!progress) {
      return "等待数据";
    }
    if (progress.state === "RUNNING") {
      return "运行中";
    }
    const waitingAck =
      queuedAt !== null && !hasProgressAckAfterQueue(progress, queuedAt);
    if (waitingAck) {
      if (Date.now() - queuedAt > PROGRESS_ACK_GRACE_MS) {
        return "上报超时";
      }
      return "等待上报";
    }
    if (progress.state === "SUCCEEDED") {
      return "已完成";
    }
    if (progress.state === "FAILED") {
      return "执行失败";
    }
    return "空闲";
  }

  function taskProgressLabel(
    progress: TaskProgress | undefined,
    queuedAt: number | null,
  ) {
    if (!progress) {
      return "等待数据";
    }
    if (progress.counters.total > 0) {
      return `${progress.counters.done} / ${progress.counters.total}`;
    }
    const waitingAck =
      queuedAt !== null && !hasProgressAckAfterQueue(progress, queuedAt);
    if (waitingAck) {
      if (Date.now() - queuedAt > PROGRESS_ACK_GRACE_MS) {
        return "命令已发送，但 2 分钟内未收到进度上报";
      }
      return "命令已发送，等待插件上报";
    }
    if (
      progress.state === "FAILED" &&
      progress.failure?.message
    ) {
      return `失败：${progress.failure.message}`;
    }
    if (
      progress.counters.total === 0 &&
      progress.counters.done === 0 &&
      progress.updated_at > 0
    ) {
      return "0 / 0（无可执行目标）";
    }
    return `${progress.counters.done} / ${progress.counters.total}`;
  }

  function reasonLabel(code: string | null | undefined) {
    if (!code) {
      return "";
    }
    if (code === "NO_TARGETS") {
      return "无可执行目标";
    }
    if (code === "WORLD_INDEX_FAILED") {
      return "离线世界索引失败";
    }
    if (code === "SB_DATA_UNAVAILABLE") {
      return "SB 离线数据不可达";
    }
    if (code === "RS2_DATA_UNAVAILABLE") {
      return "RS2 离线数据不可达";
    }
    if (code === "HEALTH_GUARD_BLOCKED") {
      return "负载保护触发，扫描延后";
    }
    if (code === "WORLD_DIR_SUBMIT_FAILED") {
      return "世界目录索引任务提交失败";
    }
    if (code === "WORLD_REGION_SUBMIT_FAILED") {
      return "区块文件扫描任务提交失败";
    }
    if (code === "WORLD_RESULT_FAILED") {
      return "离线世界扫描执行失败";
    }
    if (code === "WORLD_QUEUE_OVERFLOW") {
      return "离线世界扫描队列溢出";
    }
    if (code === "OFFLINE_TASK_SUBMIT_FAILED") {
      return "离线数据任务提交失败";
    }
    if (code === "OFFLINE_RESULT_FAILED") {
      return "离线数据扫描执行失败";
    }
    if (code === "SB_PARSE_FAILED") {
      return "SB 离线数据解析失败";
    }
    if (code === "SB_NESTED_TRUNCATED") {
      return "SB 嵌套背包解析截断";
    }
    if (code === "NBT_DEPTH_TRUNCATED") {
      return "Nbt 递归深度超限";
    }
    return code;
  }

  function reasonSuggestion(code: string | null | undefined) {
    if (!code) {
      return "";
    }
    if (code === "NO_TARGETS") {
      return "建议检查存档路径、SB/RS2 数据目录以及扫描开关。";
    }
    if (code === "WORLD_INDEX_FAILED") {
      return "建议检查 region 文件读权限或磁盘状态，再重试离线扫描。";
    }
    if (code === "SB_DATA_UNAVAILABLE") {
      return "建议确认 SophisticatedBackpacks 数据文件是否存在且可读。";
    }
    if (code === "RS2_DATA_UNAVAILABLE") {
      return "建议确认 RS2 网络数据文件是否存在且可读。";
    }
    if (code === "HEALTH_GUARD_BLOCKED") {
      return "建议降低并发/频率，或在低峰时段重新触发扫描。";
    }
    if (code === "WORLD_DIR_SUBMIT_FAILED" || code === "WORLD_REGION_SUBMIT_FAILED") {
      return "建议检查存档目录读权限、磁盘配额与线程资源后重试。";
    }
    if (code === "WORLD_RESULT_FAILED" || code === "OFFLINE_RESULT_FAILED") {
      return "建议查看后台错误日志并修复异常数据文件后重试。";
    }
    if (code === "WORLD_QUEUE_OVERFLOW") {
      return "建议降低扫描并发并扩大离线队列容量配置。";
    }
    if (code === "OFFLINE_TASK_SUBMIT_FAILED") {
      return "建议检查离线数据扫描线程池配置与系统负载。";
    }
    if (code === "SB_PARSE_FAILED" || code === "SB_NESTED_TRUNCATED") {
      return "建议检查 SB 数据文件完整性，并清理异常嵌套背包数据。";
    }
    if (code === "NBT_DEPTH_TRUNCATED") {
      return "建议排查异常深层容器数据，避免递归深度越界。";
    }
    return "";
  }

  function stageLabel(stage: string | null | undefined) {
    if (!stage) {
      return "未知阶段";
    }
    if (stage === "INDEXING") {
      return "离线索引";
    }
    if (stage === "OFFLINE_WORLD") {
      return "离线世界容器扫描";
    }
    if (stage === "OFFLINE_SB") {
      return "SB 离线数据扫描";
    }
    if (stage === "OFFLINE_RS2") {
      return "RS2 离线数据扫描";
    }
    if (stage === "RUNTIME") {
      return "在线补充扫描";
    }
    return stage;
  }

  function scanProgressLabel(
    progress: TaskProgress | undefined,
    queuedAt: number | null,
  ) {
    const base = taskProgressLabel(progress, queuedAt);
    if (!progress || progress.counters.total > 0) {
      return base;
    }
    if (progress.failure?.message) {
      return `0 / 0（${progress.failure.message}）`;
    }
    return base;
  }

  function scanSourcesLabel(progress: TaskProgress | undefined) {
    const sources = progress?.counters.targets_total_by_source;
    const doneBySource = progress?.counters.done_by_source;
    if (!sources || !doneBySource) {
      return "来源进度: -";
    }
    return `来源进度: world ${doneBySource?.world_containers ?? 0}/${sources?.world_containers ?? 0} · SB ${doneBySource?.sb_offline ?? 0}/${sources?.sb_offline ?? 0} · RS2 ${doneBySource?.rs2_offline ?? 0}/${sources?.rs2_offline ?? 0} · runtime ${doneBySource?.online_runtime ?? 0}/${sources?.online_runtime ?? 0}`;
  }

  function formatDeliveryStatus(delivery: AlertDeliveryRecord | null) {
    if (!delivery) {
      return "无发送记录";
    }
    const status = delivery.status === "success" ? "成功" : "失败";
    return `${status} · ${delivery.mode.toUpperCase()} · 重试 ${delivery.attempts} 次`;
  }

  function taskUpdateMetaLabel(
    progress: TaskProgress | undefined,
    queuedAt: number | null,
  ) {
    if (!progress || progress.updated_at <= 0) {
      return "最后上报: 无";
    }
    const ageLabel = formatElapsed(Math.max(0, Date.now() - progress.updated_at));
    if (queuedAt !== null && !hasProgressAckAfterQueue(progress, queuedAt)) {
      return `最后上报: ${ageLabel}（早于本次命令）`;
    }
    return `最后上报: ${ageLabel}`;
  }

  React.useEffect(() => {
    if (
      scanQueuedAt !== null &&
      scan &&
      hasProgressAckAfterQueue(scan, scanQueuedAt)
    ) {
      setScanQueuedAt(null);
    }
  }, [scan, scanQueuedAt]);

  React.useEffect(() => {
    if (
      auditQueuedAt !== null &&
      audit &&
      hasProgressAckAfterQueue(audit, auditQueuedAt)
    ) {
      setAuditQueuedAt(null);
    }
  }, [audit, auditQueuedAt]);

  async function handleCopy(text: string) {
    try {
      await navigator.clipboard.writeText(text);
      toast.success("命令已复制");
    } catch {
      toast.error("复制失败，请手动复制");
    }
  }

  async function handleOpen(url: string) {
    try {
      await openUrl(url);
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "打开失败");
    }
  }

  async function saveConfig() {
    if (!tauriReady) {
      toast.error("浏览器模式无法保存配置");
      return;
    }
    try {
      setSaving(true);
      const next = normalizeConfig(config);
      await invoke("rcon_config_set", { config: next });
      setConfig(next);
      toast.success("配置已保存");
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "保存配置失败");
    } finally {
      setSaving(false);
    }
  }

  async function handleConnect() {
    const next = normalizeConfig(config);
    await connectWithConfig(next, false);
  }

  async function handleDisconnect() {
    if (!tauriReady) {
      return;
    }
    try {
      setConnecting(true);
      await invoke("rcon_disconnect");
      setConnected(false);
      toast.success("已断开连接");
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "断开失败");
    } finally {
      setConnecting(false);
    }
  }

  async function handleSend() {
    const trimmed = command.trim();
    if (!trimmed) {
      return;
    }
    if (!connected) {
      toast.error("请先连接 RCON");
      return;
    }
    const normalized = trimmed.toLowerCase();
    if (/^\/?lattice\s+scan\b/.test(normalized)) {
      setScanQueuedAt(Date.now());
    }
    if (/^\/?lattice\s+audit\b/.test(normalized)) {
      setAuditQueuedAt(Date.now());
    }
    setCommand("");
    appendHistory({ id: crypto.randomUUID(), kind: "command", text: trimmed });
    try {
      const response = await invoke<string>("rcon_send", { command: trimmed });
      appendHistory({
        id: crypto.randomUUID(),
        kind: "response",
        text: response || "(empty)",
      });
    } catch (error) {
      const message = error instanceof Error ? error.message : "命令执行失败";
      appendHistory({
        id: crypto.randomUUID(),
        kind: "error",
        text: message,
      });
      toast.error(message);
    }
  }

  function renderConsoleEntry(entry: ConsoleEntry) {
    if (entry.kind === "command") {
      const [verb, ...rest] = entry.text.trim().split(/\s+/);
      return (
        <>
          <span className="text-muted-foreground">{">"}</span>{" "}
          <span className="font-semibold text-foreground">{verb}</span>
          {rest.length > 0 && (
            <span className="text-foreground/82"> {rest.join(" ")}</span>
          )}
        </>
      );
    }
    return entry.text;
  }

  const sourceLabel = config.source || "local";
  const enabled = config.enabled ?? false;
  const runtimeLabel = connected ? "运行中" : "待连接";

  return (
    <motion.div
      className="section-stack"
      variants={variants.listStagger}
      initial="initial"
      animate="enter"
    >
      <motion.section className="section" variants={variants.sectionReveal}>
        <div className="section-header">
          <div>
            <div className="section-title">命令与任务</div>
            <div className="section-meta">快速复制服务器命令并查看后台进度</div>
          </div>
        </div>

        <div className="grid gap-8 lg:grid-cols-2">
          <div className="space-y-4">
            <div className="section-subtitle">命令中心</div>
            <div className="section-stack">
              {commands.map((item) => (
                <div key={item.command}>
                  <div className="flex flex-wrap items-start justify-between gap-3">
                    <div>
                      <div className="text-sm font-semibold text-foreground">
                        {item.title}
                      </div>
                      <div className="mt-1 text-xs text-muted-foreground">
                        {item.description}
                      </div>
                    </div>
                    <Button
                      size="sm"
                      variant="secondary"
                      onClick={() => handleCopy(item.command)}
                    >
                      复制命令
                    </Button>
                  </div>
                  <div className="mt-2 font-mono text-[11px] text-foreground">
                    {item.command}
                  </div>
                </div>
              ))}
            </div>
          </div>

          <div className="space-y-4">
            <div className="section-subtitle">任务进度</div>
            <div className="section-stack text-sm">
              <div>
                <div className="flex items-center justify-between text-foreground">
                  <span>背包审计</span>
                  <span className="text-xs text-muted-foreground">
                    {taskRuntimeLabel(audit, auditQueuedAt)}
                  </span>
                </div>
                <div className="mt-2 h-1.5 w-full bg-muted/70">
                  <div
                    className="h-1.5 bg-foreground/78 transition-all"
                    style={{ width: `${percent(audit)}%` }}
                  />
                </div>
                <div className="mt-2 text-xs text-muted-foreground">
                  {taskProgressLabel(audit, auditQueuedAt)}
                </div>
                <div className="mt-1 text-[11px] text-muted-foreground/80">
                  {taskUpdateMetaLabel(audit, auditQueuedAt)}
                </div>
              </div>

              <div>
                <div className="flex items-center justify-between text-foreground">
                  <span>全服扫描</span>
                  <span className="text-xs text-muted-foreground">
                    {taskRuntimeLabel(scan, scanQueuedAt)}
                  </span>
                </div>
                <div className="mt-2 h-1.5 w-full bg-muted/70">
                  <div
                    className="h-1.5 bg-foreground/60 transition-all"
                    style={{ width: `${percent(scan)}%` }}
                  />
                </div>
                <div className="mt-2 text-xs text-muted-foreground">
                  {scanProgressLabel(scan, scanQueuedAt)}
                </div>
                <div className="mt-1 text-[11px] text-muted-foreground/80">
                  {scanSourcesLabel(scan)}
                </div>
                <div className="mt-1 text-[11px] text-muted-foreground/80">
                  阶段: {stageLabel(scan?.stage)}
                  {scan?.throughput_per_sec !== null &&
                  scan?.throughput_per_sec !== undefined
                    ? ` · 吞吐 ${scan.throughput_per_sec.toFixed(2)}/s`
                    : ""}
                </div>
                <div className="mt-1 text-[11px] text-muted-foreground/80">
                  Trace: {scan?.trace_id || "-"}
                </div>
                {scan?.failure?.code && (
                  <div className="mt-1 text-[11px] text-muted-foreground/80">
                    原因码: {scan.failure.code}（{reasonLabel(scan.failure.code)}）
                  </div>
                )}
                {scan?.failure?.code && (
                  <div className="mt-1 text-[11px] text-muted-foreground/80">
                    建议: {reasonSuggestion(scan.failure.code)}
                  </div>
                )}
                <div className="mt-1 text-[11px] text-muted-foreground/80">
                  {taskUpdateMetaLabel(scan, scanQueuedAt)}
                </div>
              </div>
            </div>
          </div>
        </div>
      </motion.section>

      <motion.section className="section" variants={variants.sectionReveal}>
        <div className="section-header">
          <div>
            <div className="section-title">报表与状态</div>
            <div className="section-meta">健康状态与原始指标</div>
          </div>
        </div>

        <div className="grid gap-8 lg:grid-cols-[1.1fr_1fr]">
          <div className="space-y-5">
            <div className="space-y-2">
              <Label>快捷入口</Label>
              <div className="flex flex-wrap gap-2">
                <Button
                  variant="secondary"
                  onClick={() => handleOpen(`${baseUrl}/v2/ops/metrics/prometheus`)}
                >
                  Prometheus 指标
                </Button>
                <Button
                  variant="secondary"
                  onClick={() => handleOpen(`${baseUrl}/v2/ops/health/live`)}
                >
                  Health Live
                </Button>
              </div>
            </div>

            <div className="space-y-2">
              <div className="section-subtitle">Health & Alert</div>
              <div className="flex flex-wrap gap-2">
                <Badge
                  className={
                    healthQuery.data ? statusBadgeClass.ok : statusBadgeClass.down
                  }
                >
                  Health {healthQuery.data ? "OK" : "DOWN"}
                </Badge>
                <Badge
                  className={
                    readyQuery.data ? statusBadgeClass.ok : statusBadgeClass.down
                  }
                >
                  Ready {readyQuery.data ? "OK" : "DOWN"}
                </Badge>
                <Badge
                  className={
                    alertQuery.data?.status === "ok"
                      ? statusBadgeClass.ok
                      : statusBadgeClass.medium
                  }
                >
                  Alert {alertQuery.data?.status ?? "-"}
                </Badge>
                <Badge className={statusBadgeClass.info}>
                  Mode {alertQuery.data?.mode ?? "-"}
                </Badge>
              </div>
            </div>

            <div className="section-stack text-sm text-muted-foreground">
              <div className="flex items-center justify-between">
                <span>总请求</span>
                <span className="text-foreground">
                  {metrics?.requests ?? "-"}
                </span>
              </div>
              <div className="flex items-center justify-between">
                <span>事件数</span>
                <span className="text-foreground">
                  {metrics?.events ?? "-"}
                </span>
              </div>
              <div className="flex items-center justify-between">
                <span>异常数</span>
                <span className="text-foreground">
                  {metrics?.anomalies ?? "-"}
                </span>
              </div>
              <div className="flex items-center justify-between">
                <span>写入错误</span>
                <span className="text-foreground">
                  {metrics?.errors ?? "-"}
                </span>
              </div>
              <div className="flex items-center justify-between">
                <span>告警发送</span>
                <span className="text-foreground">
                  {formatDeliveryStatus(lastAlertDelivery)}
                </span>
              </div>
            </div>

            <div className="space-y-2">
              <div className="section-subtitle">最近告警回执</div>
              <div className="section-stack text-xs text-muted-foreground">
                {(alertDeliveriesQuery.data ?? []).slice(0, 6).map((item) => (
                  <div
                    key={`${item.timestamp_ms}-${item.mode}-${item.status}`}
                    className="flex items-center justify-between gap-2"
                  >
                    <span>
                      {item.status === "success" ? "成功" : "失败"} ·{" "}
                      {item.mode.toUpperCase()} · {item.alert_count} 条 ·{" "}
                      {item.rule_ids.join(", ")}
                    </span>
                    <span className="text-[11px]">
                      {formatElapsed(Math.max(0, Date.now() - item.timestamp_ms))}
                    </span>
                  </div>
                ))}
                {(alertDeliveriesQuery.data?.length ?? 0) === 0 && (
                  <div>暂无发送回执</div>
                )}
              </div>
            </div>
          </div>

          <div>
            <div className="section-subtitle">Raw Metrics</div>
            <Textarea
              readOnly
              className="mt-2 min-h-[300px] font-mono text-xs"
              value={metricsQuery.data || "等待指标数据..."}
            />
          </div>
        </div>
      </motion.section>

      <motion.section className="section" variants={variants.sectionReveal}>
        <div className="section-header">
          <div className="flex items-center gap-3">
            <div className="section-title">RCON 控制台</div>
            <div className="text-xs text-muted-foreground">
              {config.host}:{config.port} · {runtimeLabel}
            </div>
          </div>
          <div className="flex flex-wrap items-center gap-2">
            <Badge
              className={connected ? statusBadgeClass.ok : statusBadgeClass.down}
            >
              {connected ? "已连接" : "未连接"}
            </Badge>
            <Badge
              className={enabled ? statusBadgeClass.low : statusBadgeClass.medium}
            >
              {enabled ? "配置开启" : "配置未开启"}
            </Badge>
            <Button
              variant="secondary"
              onClick={() => setRconSettingsOpen(true)}
              disabled={loading}
            >
              连接设置
            </Button>
          </div>
        </div>

        {!tauriReady && (
          <div className="mb-4 text-xs text-muted-foreground">
            当前为浏览器模式，RCON 连接不可用。
          </div>
        )}

        <Dialog open={rconSettingsOpen} onOpenChange={setRconSettingsOpen}>
          <DialogContent className="w-[min(92vw,34rem)]">
            <DialogHeader>
              <DialogTitle>RCON 连接设置</DialogTitle>
              <DialogDescription>
                来源: {sourceLabel} · 配置标记: {enabled ? "已开启" : "未开启"}
              </DialogDescription>
            </DialogHeader>

            <div className="grid gap-4">
              <div className="grid gap-4 sm:grid-cols-2">
                <div className="grid gap-2">
                  <Label>Host</Label>
                  <Input
                    value={config.host}
                    onChange={(event) =>
                      setConfig((prev) => ({ ...prev, host: event.target.value }))
                    }
                    placeholder="127.0.0.1"
                    disabled={loading}
                  />
                </div>
                <div className="grid gap-2">
                  <Label>Port</Label>
                  <Input
                    type="number"
                    min={1}
                    value={config.port}
                    onChange={(event) =>
                      setConfig((prev) => ({
                        ...prev,
                        port: Number(event.target.value || 0),
                      }))
                    }
                    disabled={loading}
                  />
                </div>
              </div>

              <div className="grid gap-2">
                <Label>Password</Label>
                <Input
                  type="password"
                  value={config.password ?? ""}
                  onChange={(event) =>
                    setConfig((prev) => ({
                      ...prev,
                      password: event.target.value,
                    }))
                  }
                  disabled={loading}
                />
              </div>

              <div className="flex flex-wrap gap-2">
                <Button
                  variant="secondary"
                  onClick={loadConfig}
                  disabled={loading || saving}
                >
                  刷新配置
                </Button>
                <Button
                  variant="secondary"
                  onClick={saveConfig}
                  disabled={loading || saving}
                >
                  保存配置
                </Button>
                {!connected ? (
                  <Button onClick={handleConnect} disabled={connecting || loading}>
                    {connecting ? "连接中..." : "连接"}
                  </Button>
                ) : (
                  <Button
                    variant="ghost"
                    onClick={handleDisconnect}
                    disabled={connecting}
                  >
                    断开连接
                  </Button>
                )}
              </div>
            </div>
          </DialogContent>
        </Dialog>

        <div
          ref={outputRef}
          className="mt-5 h-[280px] overflow-auto rounded-md border border-border/60 bg-muted/26 p-3 font-mono text-xs text-foreground"
        >
          {history.length === 0 && (
            <div className="text-muted-foreground">等待命令输出...</div>
          )}
          {history.map((entry) => (
            <div
              key={entry.id}
              className={
                entry.kind === "error"
                  ? "whitespace-pre-wrap break-words leading-5 text-destructive"
                  : entry.kind === "command"
                    ? "mb-1 whitespace-pre-wrap break-words rounded-sm bg-muted/30 px-1.5 py-0.5 text-foreground"
                    : "whitespace-pre-wrap break-words leading-5 text-muted-foreground"
              }
            >
              {renderConsoleEntry(entry)}
            </div>
          ))}
        </div>

        <div className="mt-3 flex flex-wrap items-center gap-2">
          <Input
            className="min-w-[220px] flex-1"
            placeholder="输入指令，例如 list"
            value={command}
            onChange={(event) => setCommand(event.target.value)}
            onKeyDown={(event) => {
              if (event.key === "Enter" && !event.shiftKey) {
                event.preventDefault();
                handleSend();
              }
            }}
          />
          <Button onClick={handleSend} disabled={!connected}>
            发送
          </Button>
        </div>
      </motion.section>
    </motion.div>
  );
}
