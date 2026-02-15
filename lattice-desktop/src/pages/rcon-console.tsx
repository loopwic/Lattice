import * as React from "react";
import { invoke } from "@tauri-apps/api/core";
import { isTauri } from "@tauri-apps/api/core";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { statusBadgeClass } from "@/lib/status-badge";

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

export function RconConsole() {
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
  const [history, setHistory] = React.useState<ConsoleEntry[]>([]);
  const outputRef = React.useRef<HTMLDivElement | null>(null);

  const refreshStatus = React.useCallback(async () => {
    if (!tauriReady) {
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
    loadConfig();
    refreshStatus();
  }, [loadConfig, refreshStatus]);

  React.useEffect(() => {
    if (!outputRef.current) {
      return;
    }
    outputRef.current.scrollTop = outputRef.current.scrollHeight;
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
    setHistory((prev) => [
      ...prev,
      { id: crypto.randomUUID(), kind: "command", text: trimmed },
    ]);
    try {
      const response = await invoke<string>("rcon_send", { command: trimmed });
      setHistory((prev) => [
        ...prev,
        {
          id: crypto.randomUUID(),
          kind: "response",
          text: response || "(empty)",
        },
      ]);
    } catch (error) {
      const message = error instanceof Error ? error.message : "命令执行失败";
      setHistory((prev) => [
        ...prev,
        { id: crypto.randomUUID(), kind: "error", text: message },
      ]);
      toast.error(message);
    }
  }

  const sourceLabel = config.source || "local";
  const enabled = config.enabled ?? false;

  return (
    <div className="space-y-4">
      {!tauriReady && (
        <section className="section">
          <div className="section-header">
            <div className="section-title">浏览器模式</div>
          </div>
          <div className="text-xs text-muted-foreground">
            当前为浏览器模式，RCON 连接不可用。
          </div>
        </section>
      )}

      <section className="section">
        <div className="section-header">
          <div>
            <div className="section-title">RCON 连接</div>
            <div className="text-xs text-muted-foreground">
              来源: {sourceLabel} · 服务器 RCON: {enabled ? "已开启" : "未开启"}
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
              {enabled ? "可用" : "禁用"}
            </Badge>
          </div>
        </div>
        <div className="grid gap-4">
          <div className="grid gap-4 md:grid-cols-3">
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
          </div>
          <div className="flex flex-wrap gap-2">
            <Button variant="secondary" onClick={loadConfig} disabled={loading}>
              刷新配置
            </Button>
            <Button variant="secondary" onClick={saveConfig} disabled={loading}>
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
      </section>

      <section className="section">
        <div className="section-header">
          <div className="section-title">控制台</div>
        </div>
        <div className="grid gap-3">
          <div
            ref={outputRef}
            className="h-[280px] overflow-auto rounded-md bg-muted/40 p-3 font-mono text-xs text-foreground"
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
          <div className="flex flex-wrap items-center gap-2">
            <Input
              className="flex-1 min-w-[220px]"
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
        </div>
      </section>
    </div>
  );
}
