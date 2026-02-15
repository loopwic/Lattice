import * as React from "react";
import { useMutation, useQuery } from "@tanstack/react-query";
import { motion } from "motion/react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Select, SelectContent, SelectItem, SelectTrigger, SelectValue } from "@/components/ui/select";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { fetchKeyItems, saveKeyItems, searchRegistry } from "@/lib/api";
import { useMotionPresets } from "@/lib/motion";
import { statusBadgeClass } from "@/lib/status-badge";
import { useSettings } from "@/lib/settings";
import type { ItemRegistryEntry, KeyItemRule, RiskLevel } from "@/lib/types";

const riskOptions: RiskLevel[] = ["HIGH", "MEDIUM", "LOW"];

export function Policy() {
  const { settings } = useSettings();
  const { variants } = useMotionPresets();
  const [rules, setRules] = React.useState<KeyItemRule[]>([]);
  const [searchInput, setSearchInput] = React.useState("");
  const [search, setSearch] = React.useState("");
  const [defaultThreshold, setDefaultThreshold] = React.useState(1);
  const [defaultRisk, setDefaultRisk] = React.useState<RiskLevel>("HIGH");

  const rulesQuery = useQuery({
    queryKey: ["key-items", settings.baseUrl, settings.apiToken],
    queryFn: () => fetchKeyItems(settings.baseUrl, settings.apiToken),
  });

  React.useEffect(() => {
    if (rulesQuery.data) {
      setRules(rulesQuery.data);
    }
  }, [rulesQuery.data]);

  const saveMutation = useMutation({
    mutationFn: (payload: KeyItemRule[]) => saveKeyItems(settings.baseUrl, settings.apiToken, payload),
    onSuccess: () => {
      toast.success("规则已保存");
      rulesQuery.refetch();
    },
    onError: (error: Error) => {
      toast.error(error.message || "保存失败");
    },
  });

  React.useEffect(() => {
    const handle = window.setTimeout(() => {
      setSearch(searchInput.trim());
    }, 250);
    return () => window.clearTimeout(handle);
  }, [searchInput]);

  const ruleSet = React.useMemo(() => new Set(rules.map((rule) => rule.item_id)), [rules]);

  const registryQuery = useQuery({
    queryKey: ["registry", settings.baseUrl, settings.apiToken, settings.lang, search],
    queryFn: () => searchRegistry(settings.baseUrl, settings.apiToken, search, settings.lang, 30),
    enabled: search.trim().length > 0,
  });

  function updateRule(index: number, patch: Partial<KeyItemRule>) {
    setRules((prev) => {
      const next = [...prev];
      next[index] = { ...next[index], ...patch };
      return next;
    });
  }

  function addRule(itemId?: string) {
    if (itemId && rules.some((rule) => rule.item_id === itemId)) {
      toast.error("该物品已存在规则");
      return;
    }
    setRules((prev) => [
      ...prev,
      {
        item_id: itemId || "",
        threshold: defaultThreshold,
        risk_level: defaultRisk,
      },
    ]);
  }

  function addRulesFromEntries(entries: ItemRegistryEntry[]) {
    const newRules = entries
      .map((entry) => entry.item_id)
      .filter((itemId) => itemId && !ruleSet.has(itemId))
      .map((itemId) => ({
        item_id: itemId,
        threshold: defaultThreshold,
        risk_level: defaultRisk,
      }));
    if (newRules.length === 0) {
      toast.error("没有可新增的规则");
      return;
    }
    setRules((prev) => [...prev, ...newRules]);
    toast.success(`已添加 ${newRules.length} 条规则`);
  }

  function removeRule(index: number) {
    setRules((prev) => prev.filter((_, i) => i !== index));
  }

  function renderRegistryItem(entry: ItemRegistryEntry) {
    const name = entry.names?.[settings.lang] || entry.name || entry.item_id;
    const exists = ruleSet.has(entry.item_id);
    return (
      <motion.div
        key={entry.item_id}
        className="border-b border-border/55 py-3 last:border-b-0"
        variants={variants.itemReveal}
      >
        <div className="flex flex-wrap items-start justify-between gap-3">
          <div className="min-w-0 space-y-1">
            <div className="text-sm font-semibold text-foreground">{name}</div>
            <div className="break-all text-xs text-muted-foreground">{entry.item_id}</div>
            <div className="flex flex-wrap gap-2 text-[11px] text-muted-foreground">
              {entry.namespace && <span>命名空间: {entry.namespace}</span>}
              {entry.path && <span>路径: {entry.path}</span>}
              {exists && <Badge className={statusBadgeClass.info}>已在规则</Badge>}
            </div>
          </div>
          <Button size="sm" variant="secondary" disabled={exists} onClick={() => addRule(entry.item_id)}>
            {exists ? "已添加" : "添加为规则"}
          </Button>
        </div>
      </motion.div>
    );
  }

  return (
    <motion.div className="section-stack" variants={variants.listStagger} initial="initial" animate="enter">
      <motion.section className="section" variants={variants.sectionReveal}>
        <div className="section-header">
          <div>
            <div className="section-title">规则策略</div>
            <div className="section-meta">搜索物品并批量生成稀有物资规则</div>
          </div>
        </div>

        <div className="grid gap-4 lg:grid-cols-3">
          <div className="grid gap-2">
            <Label>物品搜索</Label>
            <Input placeholder="输入名称或 ID" value={searchInput} onChange={(event) => setSearchInput(event.target.value)} />
          </div>
          <div className="grid gap-2">
            <Label>默认阈值</Label>
            <Input
              type="number"
              min={1}
              value={defaultThreshold}
              onChange={(event) => setDefaultThreshold(Number(event.target.value || 1))}
            />
          </div>
          <div className="grid gap-2">
            <Label>默认风险</Label>
            <Select value={defaultRisk} onValueChange={(value) => setDefaultRisk(value as RiskLevel)}>
              <SelectTrigger>
                <SelectValue />
              </SelectTrigger>
              <SelectContent>
                {riskOptions.map((risk) => (
                  <SelectItem key={risk} value={risk}>
                    {risk}
                  </SelectItem>
                ))}
              </SelectContent>
            </Select>
          </div>
        </div>

        {search.trim().length > 0 && (
          <div className="mt-5 section-stack">
            <div className="flex flex-wrap items-center justify-between gap-2 text-xs text-muted-foreground">
              <div>匹配结果: {registryQuery.data?.length ?? 0} 条</div>
              <Button
                variant="secondary"
                size="sm"
                disabled={!registryQuery.data || registryQuery.data.length === 0}
                onClick={() => addRulesFromEntries(registryQuery.data ?? [])}
              >
                批量添加
              </Button>
            </div>

            {registryQuery.isError && (
              <div className="rounded-md border border-foreground/28 bg-foreground/12 px-3 py-2 text-sm text-foreground">
                搜索失败：{(registryQuery.error as Error).message || "未知错误"}
                <div className="mt-2 text-xs">
                  请确认后端已启动，并在服务器执行命令上传物品注册表。
                </div>
                <div className="mt-2 flex flex-wrap gap-2">
                  <Button
                    variant="ghost"
                    size="sm"
                    onClick={async () => {
                      try {
                        await navigator.clipboard.writeText("/lattice registry");
                        toast.success("命令已复制");
                      } catch {
                        toast.error("复制失败，请手动复制");
                      }
                    }}
                  >
                    复制 /lattice registry
                  </Button>
                </div>
              </div>
            )}

            <motion.div className="space-y-0" variants={variants.listStagger} initial="initial" animate="enter">
              {registryQuery.isLoading && <div className="py-3 text-sm text-muted-foreground">搜索中...</div>}
              {registryQuery.data?.map(renderRegistryItem)}
              {!registryQuery.isLoading && registryQuery.data?.length === 0 && !registryQuery.isError && (
                <div className="py-3 text-sm text-muted-foreground">暂无匹配结果</div>
              )}
            </motion.div>
          </div>
        )}
      </motion.section>

      <motion.section className="section" variants={variants.sectionReveal}>
        <div className="section-header">
          <div>
            <div className="section-title">规则列表</div>
            <div className="section-meta">支持手动编辑、删除与批量保存</div>
          </div>
          <div className="flex gap-2">
            <Button variant="secondary" onClick={() => addRule()}>
              新增规则
            </Button>
            <Button
              onClick={() => {
                const cleaned = rules
                  .map((rule) => ({
                    ...rule,
                    item_id: rule.item_id.trim().toLowerCase(),
                    threshold: Number(rule.threshold) || 1,
                  }))
                  .filter((rule) => rule.item_id.length > 0);
                saveMutation.mutate(cleaned);
              }}
              disabled={saveMutation.isPending}
            >
              {saveMutation.isPending ? "保存中..." : "保存"}
            </Button>
          </div>
        </div>

        <Table>
          <TableHeader>
            <TableRow>
              <TableHead>Item ID</TableHead>
              <TableHead>阈值</TableHead>
              <TableHead>风险</TableHead>
              <TableHead>操作</TableHead>
            </TableRow>
          </TableHeader>
          <TableBody>
            {rules.map((rule, index) => (
              <TableRow key={`${rule.item_id}-${index}`}>
                <TableCell className="w-[45%]">
                  <Input value={rule.item_id} onChange={(event) => updateRule(index, { item_id: event.target.value })} />
                </TableCell>
                <TableCell className="w-[20%]">
                  <Input
                    type="number"
                    min={1}
                    value={rule.threshold}
                    onChange={(event) => updateRule(index, { threshold: Number(event.target.value || 1) })}
                  />
                </TableCell>
                <TableCell className="w-[20%]">
                  <Select
                    value={rule.risk_level}
                    onValueChange={(value) => updateRule(index, { risk_level: value as RiskLevel })}
                  >
                    <SelectTrigger>
                      <SelectValue />
                    </SelectTrigger>
                    <SelectContent>
                      {riskOptions.map((risk) => (
                        <SelectItem key={risk} value={risk}>
                          {risk}
                        </SelectItem>
                      ))}
                    </SelectContent>
                  </Select>
                </TableCell>
                <TableCell>
                  <Button variant="ghost" onClick={() => removeRule(index)}>
                    删除
                  </Button>
                </TableCell>
              </TableRow>
            ))}
            {rules.length === 0 && (
              <TableRow>
                <TableCell colSpan={4} className="text-center text-muted-foreground">
                  暂无规则
                </TableCell>
              </TableRow>
            )}
          </TableBody>
        </Table>

        {rulesQuery.isError && <div className="mt-3 text-sm text-destructive">{(rulesQuery.error as Error).message}</div>}

        <div className="mt-4 flex flex-wrap gap-2">
          <Badge className={statusBadgeClass.info}>窗口告警</Badge>
          <Badge className={statusBadgeClass.info}>支持搜索一键加入</Badge>
        </div>
      </motion.section>
    </motion.div>
  );
}
