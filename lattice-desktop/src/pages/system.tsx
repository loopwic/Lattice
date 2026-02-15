import * as React from "react";
import { invoke } from "@tauri-apps/api/core";
import { motion } from "motion/react";
import { useTheme } from "next-themes";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Textarea } from "@/components/ui/textarea";
import { useMotionPresets } from "@/lib/motion";
import { useSettings } from "@/lib/settings";

type BackendRuntimeStatus = {
  running: boolean;
  last_error?: string | null;
};

export function System() {
  const { settings, updateSettings } = useSettings();
  const { theme, setTheme } = useTheme();
  const { variants } = useMotionPresets();

  const [baseUrl, setBaseUrl] = React.useState(settings.baseUrl);
  const [apiToken, setApiToken] = React.useState(settings.apiToken);
  const [lang, setLang] = React.useState(settings.lang || "zh_cn");

  const [content, setContent] = React.useState("");
  const [loading, setLoading] = React.useState(true);
  const [saving, setSaving] = React.useState(false);
  const [backendRuntime, setBackendRuntime] =
    React.useState<BackendRuntimeStatus | null>(null);

  React.useEffect(() => {
    setBaseUrl(settings.baseUrl);
    setApiToken(settings.apiToken);
    setLang(settings.lang || "zh_cn");
  }, [settings]);

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
    });
    toast.success("连接设置已保存");
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
