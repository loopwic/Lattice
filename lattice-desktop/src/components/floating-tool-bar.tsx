import * as React from "react";
import { invoke, isTauri } from "@tauri-apps/api/core";
import { AnimatePresence, motion } from "motion/react";
import {
  Bug,
  ChevronDown,
  FileText,
  Radio,
  RefreshCw,
  ScanSearch,
} from "lucide-react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Tabs, TabsContent, TabsList, TabsTrigger } from "@/components/ui/tabs";
import { Textarea } from "@/components/ui/textarea";
import { useSettings } from "@/lib/settings";
import { statusBadgeClass } from "@/lib/status-badge";
import { cn } from "@/lib/utils";

type ToolKind = "debug" | "rcon";

type BackendRuntimeStatus = {
  running: boolean;
  last_error?: string | null;
};

type ProbeStatus = {
  target?: string;
  url?: string;
  ok: boolean;
  status?: number | null;
  error?: string | null;
  body?: string;
};

type BackendDebugReport = {
  timestamp_ms: number;
  runtime: BackendRuntimeStatus;
  config_path?: string | null;
  bind_addr?: string | null;
  clickhouse_url?: string | null;
  api_token_present: boolean;
  probe_base_url?: string | null;
  backend_tcp: ProbeStatus;
  clickhouse_tcp: ProbeStatus;
  health_live: ProbeStatus;
  health_ready: ProbeStatus;
  alert_check: ProbeStatus;
};

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

const tauriReady = isTauri();
const OPEN_DEBUG_EVENT = "lattice-open-debug-console";
const RCON_HISTORY_STORAGE_KEY = "lattice_rcon_history_v1";
const RCON_HISTORY_LIMIT = 240;

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
    // ignore persistence errors
  }
}

function DebugPanel({ visible }: { visible: boolean }) {
  const [debugLoading, setDebugLoading] = React.useState(false);
  const [debugLogLoading, setDebugLogLoading] = React.useState(false);
  const [debugLogPath, setDebugLogPath] = React.useState("");
  const [debugLogs, setDebugLogs] = React.useState("");
  const [debugReport, setDebugReport] = React.useState<BackendDebugReport | null>(
    null,
  );

  const loadDebugPath = React.useCallback(async () => {
    if (!tauriReady) {
      return;
    }
    try {
      const path = await invoke<string>("debug_log_path");
      setDebugLogPath(path);
    } catch {
      setDebugLogPath("");
    }
  }, []);

  const loadDebugLogs = React.useCallback(async () => {
    if (!tauriReady) {
      return;
    }
    try {
      setDebugLogLoading(true);
      const logs = await invoke<string>("debug_log_tail", { lines: 500 });
      setDebugLogs(logs);
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "读取日志失败");
    } finally {
      setDebugLogLoading(false);
    }
  }, []);

  const runDebugProbe = React.useCallback(async () => {
    if (!tauriReady) {
      return;
    }
    try {
      setDebugLoading(true);
      const report = await invoke<BackendDebugReport>("backend_debug_probe");
      setDebugReport(report);
      await loadDebugLogs();
      toast.success("诊断完成");
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "诊断失败");
    } finally {
      setDebugLoading(false);
    }
  }, [loadDebugLogs]);

  React.useEffect(() => {
    if (!visible) {
      return;
    }
    void loadDebugPath();
    void loadDebugLogs();
  }, [loadDebugLogs, loadDebugPath, visible]);

  async function copyDebugLogs() {
    try {
      await navigator.clipboard.writeText(debugLogs || "");
      toast.success("日志已复制");
    } catch {
      toast.error("复制失败");
    }
  }

  return (
    <div className="grid gap-3">
      <div className="flex flex-wrap items-center gap-2 text-xs text-muted-foreground">
        <span>日志文件:</span>
        <span className="truncate">{debugLogPath || "不可用"}</span>
      </div>
      <div className="flex flex-wrap gap-2">
        <Button
          variant="secondary"
          size="sm"
          onClick={runDebugProbe}
          disabled={debugLoading}
        >
          <ScanSearch className="h-4 w-4" />
          {debugLoading ? "诊断中..." : "运行自检"}
        </Button>
        <Button
          variant="secondary"
          size="sm"
          onClick={loadDebugLogs}
          disabled={debugLogLoading}
        >
          <RefreshCw className="h-4 w-4" />
          {debugLogLoading ? "刷新中..." : "刷新日志"}
        </Button>
        <Button variant="secondary" size="sm" onClick={copyDebugLogs}>
          <FileText className="h-4 w-4" />
          复制日志
        </Button>
      </div>

      <Tabs defaultValue="logs" className="w-full">
        <TabsList className="w-full justify-start">
          <TabsTrigger value="logs">运行日志</TabsTrigger>
          <TabsTrigger value="probe">自检结果</TabsTrigger>
        </TabsList>
        <TabsContent value="logs" className="mt-2">
          <Label className="mb-2 block">最近 500 行</Label>
          <Textarea
            className="min-h-[42vh] font-mono text-xs"
            readOnly
            value={debugLogs || "暂无日志。"}
          />
        </TabsContent>
        <TabsContent value="probe" className="mt-2">
          <Label className="mb-2 block">探针输出</Label>
          <Textarea
            className="min-h-[42vh] font-mono text-xs"
            readOnly
            value={
              debugReport
                ? JSON.stringify(debugReport, null, 2)
                : "尚未运行自检。点击“运行自检”查看连通性结果。"
            }
          />
        </TabsContent>
      </Tabs>
    </div>
  );
}

function RconPanel({ visible }: { visible: boolean }) {
  const [config, setConfig] = React.useState<RconConfig>({
    host: "127.0.0.1",
    port: 25575,
    password: "",
    enabled: false,
    source: "local",
  });
  const [loading, setLoading] = React.useState(true);
  const [connecting, setConnecting] = React.useState(false);
  const [connected, setConnected] = React.useState(false);
  const [command, setCommand] = React.useState("");
  const [history, setHistory] = React.useState<ConsoleEntry[]>(() =>
    loadRconHistory(),
  );
  const outputRef = React.useRef<HTMLDivElement | null>(null);

  const appendHistory = React.useCallback((entry: ConsoleEntry) => {
    setHistory((prev) => [...prev, entry].slice(-RCON_HISTORY_LIMIT));
  }, []);

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

  const loadConfig = React.useCallback(async () => {
    if (!tauriReady) {
      setLoading(false);
      return;
    }
    try {
      setLoading(true);
      const loaded = await invoke<RconConfig>("rcon_config_get");
      setConfig((prev) => normalizeConfig({ ...prev, ...loaded }));
    } catch (error) {
      toast.error(
        error instanceof Error ? error.message : "加载 RCON 配置失败",
      );
    } finally {
      setLoading(false);
    }
  }, []);

  React.useEffect(() => {
    void loadConfig();
  }, [loadConfig]);

  React.useEffect(() => {
    if (visible) {
      void refreshStatus();
    }
  }, [refreshStatus, visible]);

  React.useEffect(() => {
    if (!outputRef.current) {
      return;
    }
    outputRef.current.scrollTop = outputRef.current.scrollHeight;
  }, [history]);

  React.useEffect(() => {
    saveRconHistory(history);
  }, [history]);

  async function saveConfig() {
    if (!tauriReady) {
      toast.error("浏览器模式无法保存配置");
      return;
    }
    try {
      setLoading(true);
      const next = normalizeConfig(config);
      await invoke("rcon_config_set", { config: next });
      setConfig(next);
      toast.success("配置已保存");
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "保存配置失败");
    } finally {
      setLoading(false);
    }
  }

  async function handleConnect() {
    if (!tauriReady) {
      toast.error("浏览器模式无法连接 RCON");
      return;
    }
    try {
      setConnecting(true);
      const next = normalizeConfig(config);
      await invoke("rcon_connect", { config: next });
      setConnected(true);
      toast.success("RCON 已连接");
    } catch (error) {
      toast.error(error instanceof Error ? error.message : "连接失败");
    } finally {
      setConnecting(false);
    }
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
      appendHistory({ id: crypto.randomUUID(), kind: "error", text: message });
      toast.error(message);
    }
  }

  const sourceLabel = config.source || "local";
  const enabled = config.enabled ?? false;

  return (
    <div className="grid gap-3">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <div className="text-xs text-muted-foreground">
          来源: {sourceLabel} · 服务器 RCON: {enabled ? "已开启" : "未开启"}
        </div>
        <div className="flex items-center gap-2">
          <Badge className={connected ? statusBadgeClass.ok : statusBadgeClass.down}>
            {connected ? "已连接" : "未连接"}
          </Badge>
        </div>
      </div>

      {!tauriReady && (
        <div className="rounded-md border border-border/60 bg-muted/30 px-3 py-2 text-xs text-muted-foreground backdrop-blur-sm">
          当前为浏览器模式，RCON 连接不可用。
        </div>
      )}

      <div className="grid gap-3 sm:grid-cols-3">
        <div className="grid gap-1.5">
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
        <div className="grid gap-1.5">
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
        <div className="grid gap-1.5">
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
      </div>

      <div className="flex flex-wrap gap-2">
        <Button variant="secondary" size="sm" onClick={loadConfig} disabled={loading}>
          刷新配置
        </Button>
        <Button variant="secondary" size="sm" onClick={saveConfig} disabled={loading}>
          保存配置
        </Button>
        {!connected ? (
          <Button size="sm" onClick={handleConnect} disabled={connecting || loading}>
            {connecting ? "连接中..." : "连接"}
          </Button>
        ) : (
          <Button
            size="sm"
            variant="ghost"
            onClick={handleDisconnect}
            disabled={connecting}
          >
            断开连接
          </Button>
        )}
      </div>

      <div
        ref={outputRef}
        className="h-[34vh] overflow-auto rounded-md border border-border/60 bg-muted/24 p-3 font-mono text-xs text-foreground backdrop-blur-sm"
      >
        {history.length === 0 && (
          <div className="text-muted-foreground">等待命令输出...</div>
        )}
        {history.map((entry) => (
          <div
            key={entry.id}
            className={
              entry.kind === "error"
                ? "text-destructive"
                : entry.kind === "command"
                  ? "text-muted-foreground"
                  : "text-foreground"
            }
          >
            {entry.kind === "command" ? "> " : ""}
            {entry.text}
          </div>
        ))}
      </div>

      <div className="flex items-center gap-2">
        <Input
          className="flex-1"
          placeholder="输入指令，例如 list"
          value={command}
          onChange={(event) => setCommand(event.target.value)}
          onKeyDown={(event) => {
            if (event.key === "Enter" && !event.shiftKey) {
              event.preventDefault();
              void handleSend();
            }
          }}
        />
        <Button size="sm" onClick={handleSend} disabled={!connected}>
          发送
        </Button>
      </div>
    </div>
  );
}

export function FloatingToolBar() {
  const { settings } = useSettings();
  const initialTool = settings.debugMode ? "debug" : "rcon";
  const [activeTool, setActiveTool] = React.useState<ToolKind>(
    initialTool,
  );
  const [panelOpen, setPanelOpen] = React.useState(false);

  const availableTools = React.useMemo<ToolKind[]>(
    () => (settings.debugMode ? ["debug", "rcon"] : ["rcon"]),
    [settings.debugMode],
  );

  React.useEffect(() => {
    if (activeTool === "debug" && !settings.debugMode) {
      setActiveTool("rcon");
      setPanelOpen(false);
    }
  }, [activeTool, settings.debugMode]);

  const onToolClick = React.useCallback(
    (tool: ToolKind) => {
      if (tool === activeTool) {
        setPanelOpen((prevOpen) => !prevOpen);
        return;
      }
      setActiveTool(tool);
      setPanelOpen(true);
    },
    [activeTool],
  );

  React.useEffect(() => {
    function handleOpenDebug() {
      if (settings.debugMode) {
        setActiveTool("debug");
      } else {
        setActiveTool("rcon");
      }
      setPanelOpen(true);
    }
    window.addEventListener(OPEN_DEBUG_EVENT, handleOpenDebug);
    return () => {
      window.removeEventListener(OPEN_DEBUG_EVENT, handleOpenDebug);
    };
  }, [settings.debugMode]);

  return (
    <div className="pointer-events-none fixed bottom-5 right-5 z-[73] flex flex-col items-end gap-2">
      <AnimatePresence>
        {panelOpen && (
          <motion.section
            className="pointer-events-auto w-[min(96vw,58rem)] rounded-2xl border border-border/70 bg-background p-3 shadow-lg shadow-black/10 dark:shadow-black/35"
            initial={{ opacity: 0, y: 8, scale: 0.99 }}
            animate={{ opacity: 1, y: 0, scale: 1 }}
            exit={{ opacity: 0, y: 10, scale: 0.99 }}
            transition={{ duration: 0.2, ease: [0.22, 1, 0.36, 1] }}
          >
            <div className="mb-3 flex items-center justify-between border-b border-border/60 pb-2">
              <div className="text-xs font-medium text-foreground/80">
                {activeTool === "debug" ? "Debug Console" : "RCON Console"}
              </div>
              <button
                type="button"
                className="inline-flex h-8 w-8 items-center justify-center rounded-md text-foreground/70 transition-colors hover:bg-muted hover:text-foreground"
                onClick={() => setPanelOpen(false)}
                aria-label="关闭面板"
              >
                <ChevronDown className="h-4 w-4" />
              </button>
            </div>

            <AnimatePresence mode="wait">
              {activeTool === "debug" ? (
                <motion.div
                  key="debug-panel"
                  initial={{ opacity: 0, y: 4 }}
                  animate={{ opacity: 1, y: 0 }}
                  exit={{ opacity: 0, y: -4 }}
                  transition={{ duration: 0.2 }}
                >
                  <DebugPanel visible={panelOpen} />
                </motion.div>
              ) : (
                <motion.div
                  key="rcon-panel"
                  initial={{ opacity: 0, y: 4 }}
                  animate={{ opacity: 1, y: 0 }}
                  exit={{ opacity: 0, y: -4 }}
                  transition={{ duration: 0.2 }}
                >
                  <RconPanel visible={panelOpen} />
                </motion.div>
              )}
            </AnimatePresence>
          </motion.section>
        )}
      </AnimatePresence>

      <motion.section
        className="pointer-events-auto"
        initial={{ opacity: 0, y: 8, scale: 0.99 }}
        animate={{ opacity: 1, y: 0, scale: 1 }}
        transition={{ duration: 0.24, ease: [0.22, 1, 0.36, 1] }}
      >
        <div className="inline-flex items-center gap-1 rounded-xl border border-border/60 bg-muted/55 p-1 backdrop-blur-md">
          {availableTools.map((tool) => {
            const active = panelOpen && activeTool === tool;
            const label = tool === "debug" ? "Debug" : "RCON";
            const Icon = tool === "debug" ? Bug : Radio;
            return (
              <motion.button
                key={tool}
                type="button"
                layout
                className={cn(
                  "inline-flex h-9 items-center rounded-lg text-xs transition-colors",
                  active
                    ? "gap-1.5 bg-foreground px-3 text-background"
                    : "w-9 justify-center text-foreground/80 hover:bg-muted hover:text-foreground",
                )}
                onClick={() => onToolClick(tool)}
                transition={{ duration: 0.2, ease: [0.22, 1, 0.36, 1] }}
              >
                <Icon className="h-3.5 w-3.5 shrink-0" />
                <AnimatePresence initial={false}>
                  {active && (
                    <motion.span
                      initial={{ opacity: 0, width: 0 }}
                      animate={{ opacity: 1, width: "auto" }}
                      exit={{ opacity: 0, width: 0 }}
                      transition={{ duration: 0.16 }}
                      className="overflow-hidden whitespace-nowrap"
                    >
                      {label}
                    </motion.span>
                  )}
                </AnimatePresence>
              </motion.button>
            );
          })}
        </div>
      </motion.section>
    </div>
  );
}
