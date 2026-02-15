import { useQuery } from "@tanstack/react-query";
import { motion } from "motion/react";
import { StatusPill } from "@/components/status-pill";
import { fetchAlertStatus, fetchMetrics, pingHealth, pingReady } from "@/lib/api";
import { parsePrometheusMetrics } from "@/lib/metrics";
import { useMotionPresets } from "@/lib/motion";
import { useSettings } from "@/lib/settings";

export function Overview() {
  const { settings } = useSettings();
  const { variants } = useMotionPresets();

  const healthQuery = useQuery({
    queryKey: ["health", settings.baseUrl],
    queryFn: () => pingHealth(settings.baseUrl),
    refetchInterval: 10_000,
  });

  const readyQuery = useQuery({
    queryKey: ["ready", settings.baseUrl],
    queryFn: () => pingReady(settings.baseUrl),
    refetchInterval: 10_000,
  });

  const alertQuery = useQuery({
    queryKey: ["alert-target", settings.baseUrl, settings.apiToken],
    queryFn: () => fetchAlertStatus(settings.baseUrl, settings.apiToken),
    refetchInterval: 15_000,
  });

  const metricsQuery = useQuery({
    queryKey: ["metrics", settings.baseUrl, settings.apiToken],
    queryFn: () => fetchMetrics(settings.baseUrl, settings.apiToken),
    refetchInterval: 15_000,
  });

  const metrics = metricsQuery.data ? parsePrometheusMetrics(metricsQuery.data) : null;

  return (
    <motion.div className="section-stack" variants={variants.listStagger} initial="initial" animate="enter">
      <motion.section className="section" variants={variants.sectionReveal}>
        <div className="section-header">
          <div>
            <div className="section-title">系统状态</div>
            <div className="section-meta">实时健康检查与告警模式</div>
          </div>
        </div>
        <div className="flex flex-wrap gap-2">
          <StatusPill label="Health" ok={healthQuery.data ?? false} />
          <StatusPill label="Ready" ok={readyQuery.data ?? false} />
          <StatusPill label="Alert" ok={alertQuery.data?.status === "ok"} />
          <StatusPill label="Mode" ok={alertQuery.data?.mode !== "unset"} />
        </div>
      </motion.section>

      <motion.section className="section" variants={variants.sectionReveal}>
        <div className="section-header">
          <div>
            <div className="section-title">运行摘要</div>
            <div className="section-meta">连接信息、核心指标和常用命令</div>
          </div>
        </div>
        <div className="grid gap-8 lg:grid-cols-3">
          <div className="space-y-2 text-sm text-muted-foreground">
            <div className="section-subtitle">连接信息</div>
            <div>Backend: {settings.baseUrl}</div>
            <div>API Token: {settings.apiToken ? "已设置" : "未设置"}</div>
            <div>搜索语言: {settings.lang}</div>
          </div>

          <div className="space-y-2 text-sm text-muted-foreground">
            <div className="section-subtitle">核心指标</div>
            <div className="section-stack">
              <div className="flex items-center justify-between">
                <span>总请求</span>
                <span className="text-foreground">{metrics?.requests ?? "-"}</span>
              </div>
              <div className="flex items-center justify-between">
                <span>事件数</span>
                <span className="text-foreground">{metrics?.events ?? "-"}</span>
              </div>
              <div className="flex items-center justify-between">
                <span>异常数</span>
                <span className="text-foreground">{metrics?.anomalies ?? "-"}</span>
              </div>
              <div className="flex items-center justify-between">
                <span>写入错误</span>
                <span className="text-foreground">{metrics?.errors ?? "-"}</span>
              </div>
            </div>
          </div>

          <div className="space-y-2 text-sm text-muted-foreground">
            <div className="section-subtitle">常用命令</div>
            <div className="font-mono text-[11px] text-foreground">/lattice registry</div>
            <div className="font-mono text-[11px] text-foreground">/lattice audit all</div>
            <div className="font-mono text-[11px] text-foreground">/lattice scan</div>
          </div>
        </div>
      </motion.section>
    </motion.div>
  );
}
