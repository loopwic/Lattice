import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { motion } from "motion/react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
import { TablePager } from "@/components/table-pager";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import { Table, TableBody, TableCell, TableHead, TableHeader, TableRow } from "@/components/ui/table";
import { fetchAnomalies, fetchStorageScan } from "@/lib/api";
import { formatDateTime } from "@/lib/datetime";
import { useMotionPresets } from "@/lib/motion";
import { riskBadgeClass, statusBadgeClass } from "@/lib/status-badge";
import { useSettings } from "@/lib/settings";
import type { AnomalyRow, StorageScanRow } from "@/lib/types";
import { cn } from "@/lib/utils";

function today() {
  return new Date().toISOString().slice(0, 10);
}

function escapeCsv(value: string | number | null | undefined) {
  const text = String(value ?? "");
  if (/[",\n]/.test(text)) {
    return `"${text.replace(/"/g, '""')}"`;
  }
  return text;
}

function formatEvidence(raw: string) {
  try {
    return JSON.stringify(JSON.parse(raw), null, 2);
  } catch {
    return raw;
  }
}

function hasCoords(row: StorageScanRow) {
  return row.x !== null && row.y !== null && row.z !== null;
}

function formatCoords(row: StorageScanRow) {
  if (!hasCoords(row)) {
    return "-";
  }
  return `${row.x}, ${row.y}, ${row.z}`;
}

function formatStorage(row: StorageScanRow) {
  const mod = row.storage_mod || "unknown";
  const id = row.storage_id || "";
  if (!id) {
    return mod;
  }
  if (id.startsWith(`${mod}:`)) {
    return id;
  }
  return `${mod}:${id}`;
}

export function Investigate() {
  const { settings } = useSettings();
  const { variants } = useMotionPresets();

  const anomaliesRef = React.useRef<HTMLElement | null>(null);
  const storageRef = React.useRef<HTMLElement | null>(null);

  const [anomalyDate, setAnomalyDate] = React.useState(today());
  const [player, setPlayer] = React.useState("");
  const [selectedAnomaly, setSelectedAnomaly] = React.useState<AnomalyRow | null>(null);
  const [anomalyPage, setAnomalyPage] = React.useState(1);
  const [anomalyPageSize, setAnomalyPageSize] = React.useState(50);

  const [scanDate, setScanDate] = React.useState(today());
  const [item, setItem] = React.useState("");
  const [limit, setLimit] = React.useState("200");
  const [selectedStorage, setSelectedStorage] = React.useState<StorageScanRow | null>(null);
  const [scanPage, setScanPage] = React.useState(1);
  const [scanPageSize, setScanPageSize] = React.useState(50);

  const anomaliesQuery = useQuery({
    queryKey: ["anomalies", settings.baseUrl, settings.apiToken, anomalyDate, player],
    queryFn: () => fetchAnomalies(settings.baseUrl, settings.apiToken, anomalyDate, player.trim() || undefined),
  });

  const anomaliesData = anomaliesQuery.data || [];
  const anomalyTotalRows = anomaliesData.length;
  const anomalyTotalPages = Math.max(
    1,
    Math.ceil(anomalyTotalRows / anomalyPageSize),
  );
  const anomalySafePage = Math.min(anomalyPage, anomalyTotalPages);
  const anomalyStart = (anomalySafePage - 1) * anomalyPageSize;
  const anomalyRows = anomaliesData.slice(
    anomalyStart,
    anomalyStart + anomalyPageSize,
  );

  const limitValue = Number.parseInt(limit, 10);
  const effectiveLimit = Number.isFinite(limitValue) ? Math.min(Math.max(limitValue, 1), 2000) : 200;

  const scanQuery = useQuery({
    queryKey: ["storage-scan", settings.baseUrl, settings.apiToken, scanDate, item, effectiveLimit],
    queryFn: () => fetchStorageScan(settings.baseUrl, settings.apiToken, scanDate, item.trim() || undefined, effectiveLimit),
  });

  const scanData = scanQuery.data || [];
  const scanTotalRows = scanData.length;
  const scanTotalPages = Math.max(1, Math.ceil(scanTotalRows / scanPageSize));
  const scanSafePage = Math.min(scanPage, scanTotalPages);
  const scanStart = (scanSafePage - 1) * scanPageSize;
  const scanRows = scanData.slice(scanStart, scanStart + scanPageSize);

  React.useEffect(() => {
    setAnomalyPage(1);
  }, [anomalyDate, player, settings.baseUrl, settings.apiToken]);

  React.useEffect(() => {
    if (anomalyPage > anomalyTotalPages) {
      setAnomalyPage(anomalyTotalPages);
    }
  }, [anomalyPage, anomalyTotalPages]);

  React.useEffect(() => {
    setScanPage(1);
  }, [scanDate, item, effectiveLimit, settings.baseUrl, settings.apiToken]);

  React.useEffect(() => {
    if (scanPage > scanTotalPages) {
      setScanPage(scanTotalPages);
    }
  }, [scanPage, scanTotalPages]);

  function jumpTo(target: "anomalies" | "storage") {
    const section = target === "anomalies" ? anomaliesRef.current : storageRef.current;
    section?.scrollIntoView({ behavior: "smooth", block: "start" });
  }

  function anomalyRowKey(row: AnomalyRow) {
    return `${row.event_time}-${row.player_name}-${row.item_id}`;
  }

  function scanRowKey(row: StorageScanRow) {
    return `${row.event_time}-${row.item_id}-${row.storage_id}-${row.x}-${row.z}`;
  }

  async function handleCopyCoords(row: StorageScanRow) {
    if (!hasCoords(row)) {
      toast.error("该记录没有坐标");
      return;
    }
    const prefix = row.dim ? `${row.dim} ` : "";
    const text = `${prefix}${row.x} ${row.y} ${row.z}`;
    try {
      await navigator.clipboard.writeText(text);
      toast.success("坐标已复制");
    } catch {
      toast.error("复制失败，请手动复制");
    }
  }

  function exportAnomaliesCsv() {
    if (!anomaliesData.length) {
      toast.error("暂无可导出的记录");
      return;
    }
    const header = ["event_time", "player_name", "item_id", "count", "risk_level", "rule_id", "reason"];
    const rows = anomaliesData.map((row) => [
      row.event_time,
      row.player_name,
      row.item_id,
      row.count,
      row.risk_level,
      row.rule_id,
      row.reason,
    ]);
    const csv = [header, ...rows].map((row) => row.map(escapeCsv).join(",")).join("\n");
    const blob = new Blob([csv], { type: "text/csv;charset=utf-8;" });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = `anomalies_${anomalyDate}.csv`;
    anchor.click();
    URL.revokeObjectURL(url);
    toast.success("已导出 CSV");
  }

  return (
    <motion.div className="section-stack" variants={variants.listStagger} initial="initial" animate="enter">
      <motion.section className="section" variants={variants.sectionReveal}>
        <div className="section-header">
          <div>
            <div className="section-title">监控调查</div>
            <div className="section-meta">同页完成异常检索与存储扫描排查</div>
          </div>
          <div className="flex flex-wrap gap-2">
            <Button variant="secondary" size="sm" onClick={() => jumpTo("anomalies")}>异常区块</Button>
            <Button variant="secondary" size="sm" onClick={() => jumpTo("storage")}>扫描区块</Button>
          </div>
        </div>
      </motion.section>

      <motion.section ref={anomaliesRef} className="section" variants={variants.sectionReveal} id="anomalies">
        <div className="section-header">
          <div>
            <div className="section-title">异常列表</div>
            <div className="section-meta">按日期和玩家定位可疑背包事件</div>
          </div>
          <Button variant="secondary" onClick={exportAnomaliesCsv}>
            导出 CSV
          </Button>
        </div>

        <div className="mb-5 grid gap-4 md:grid-cols-2">
          <div className="grid gap-2">
            <Label>日期</Label>
            <Input type="date" value={anomalyDate} onChange={(event) => setAnomalyDate(event.target.value)} />
          </div>
          <div className="grid gap-2">
            <Label>玩家名（可选）</Label>
            <Input value={player} onChange={(event) => setPlayer(event.target.value)} />
          </div>
        </div>

        <div className="grid gap-6 lg:grid-cols-[1.65fr_1fr]">
          <div className="min-w-0">
            <TablePager
              totalRows={anomalyTotalRows}
              page={anomalySafePage}
              totalPages={anomalyTotalPages}
              pageSize={anomalyPageSize}
              onPageSizeChange={setAnomalyPageSize}
              onPrev={() => setAnomalyPage((prev) => Math.max(1, prev - 1))}
              onNext={() =>
                setAnomalyPage((prev) =>
                  Math.min(anomalyTotalPages, prev + 1),
                )
              }
            />
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>时间</TableHead>
                  <TableHead>玩家</TableHead>
                  <TableHead>物品</TableHead>
                  <TableHead>数量</TableHead>
                  <TableHead>风险</TableHead>
                  <TableHead>规则</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {anomalyRows.map((row) => {
                  const key = anomalyRowKey(row);
                  const active = selectedAnomaly ? anomalyRowKey(selectedAnomaly) === key : false;
                  return (
                    <TableRow
                      key={key}
                      className={cn("cursor-pointer", active && "bg-muted/58")}
                      onClick={() => setSelectedAnomaly(row)}
                    >
                      <TableCell className="text-xs text-muted-foreground">{formatDateTime(row.event_time)}</TableCell>
                      <TableCell className="text-sm text-foreground">{row.player_name}</TableCell>
                      <TableCell className="break-all text-xs text-muted-foreground">{row.item_id}</TableCell>
                      <TableCell>{row.count}</TableCell>
                      <TableCell>
                        <Badge className={riskBadgeClass[row.risk_level] || statusBadgeClass.info}>{row.risk_level}</Badge>
                      </TableCell>
                      <TableCell className="text-xs text-muted-foreground">{row.rule_id}</TableCell>
                    </TableRow>
                  );
                })}
                {anomalyTotalRows === 0 && !anomaliesQuery.isLoading && (
                  <TableRow>
                    <TableCell colSpan={6} className="text-center text-muted-foreground">
                      暂无异常记录
                    </TableCell>
                  </TableRow>
                )}
              </TableBody>
            </Table>

            {anomaliesQuery.isError && (
              <div className="mt-4 text-sm text-destructive">{(anomaliesQuery.error as Error).message}</div>
            )}
          </div>

          <div className="min-w-0">
            <div className="section-subtitle">异常详情</div>
            {!selectedAnomaly ? (
              <div className="mt-3 text-xs text-muted-foreground">选择一条记录查看详情</div>
            ) : (
              <div className="mt-3 section-stack text-sm text-foreground">
                <div>
                  <div className="text-xs text-muted-foreground">玩家</div>
                  <div className="mt-1">{selectedAnomaly.player_name}</div>
                </div>
                <div>
                  <div className="text-xs text-muted-foreground">时间</div>
                  <div className="mt-1">{formatDateTime(selectedAnomaly.event_time)}</div>
                </div>
                <div>
                  <div className="text-xs text-muted-foreground">物品</div>
                  <div className="mt-1 break-all">{selectedAnomaly.item_id}</div>
                </div>
                <div>
                  <div className="text-xs text-muted-foreground">数量 / 风险</div>
                  <div className="mt-1">
                    {selectedAnomaly.count} / {selectedAnomaly.risk_level}
                  </div>
                </div>
                <div>
                  <div className="text-xs text-muted-foreground">规则 / 原因</div>
                  <div className="mt-1">{selectedAnomaly.rule_id}</div>
                  <div className="mt-2 whitespace-pre-wrap">{selectedAnomaly.reason}</div>
                </div>
                <div>
                  <div className="text-xs text-muted-foreground">证据</div>
                  <pre className="mt-2 max-h-72 overflow-auto rounded-md bg-muted/40 p-3 text-xs text-foreground">
                    {formatEvidence(selectedAnomaly.evidence_json)}
                  </pre>
                </div>
              </div>
            )}
          </div>
        </div>
      </motion.section>

      <motion.section ref={storageRef} className="section" variants={variants.sectionReveal} id="storage">
        <div className="section-header">
          <div>
            <div className="section-title">区块扫描结果</div>
            <div className="section-meta">定位超过阈值的容器快照并联动坐标</div>
          </div>
        </div>

        <div className="mb-5 grid gap-4 md:grid-cols-3">
          <div className="grid gap-2">
            <Label>日期</Label>
            <Input type="date" value={scanDate} onChange={(event) => setScanDate(event.target.value)} />
          </div>
          <div className="grid gap-2">
            <Label>物品 ID（可选）</Label>
            <Input value={item} onChange={(event) => setItem(event.target.value)} placeholder="minecraft:diamond" />
          </div>
          <div className="grid gap-2">
            <Label>最大返回条数</Label>
            <Input value={limit} onChange={(event) => setLimit(event.target.value)} placeholder="200" />
          </div>
        </div>

        <div className="mb-5 text-xs text-muted-foreground">
          仅展示超过阈值的存储快照，默认最多返回 200 条（上限 2000）。
        </div>

        <div className="grid gap-6 lg:grid-cols-[1.65fr_1fr]">
          <div className="min-w-0">
            <TablePager
              totalRows={scanTotalRows}
              page={scanSafePage}
              totalPages={scanTotalPages}
              pageSize={scanPageSize}
              onPageSizeChange={setScanPageSize}
              onPrev={() => setScanPage((prev) => Math.max(1, prev - 1))}
              onNext={() =>
                setScanPage((prev) => Math.min(scanTotalPages, prev + 1))
              }
            />
            <Table>
              <TableHeader>
                <TableRow>
                  <TableHead>时间</TableHead>
                  <TableHead>物品</TableHead>
                  <TableHead>数量</TableHead>
                  <TableHead>风险</TableHead>
                  <TableHead>坐标</TableHead>
                </TableRow>
              </TableHeader>
              <TableBody>
                {scanRows.map((row) => {
                  const key = scanRowKey(row);
                  const active = selectedStorage ? scanRowKey(selectedStorage) === key : false;
                  return (
                    <TableRow
                      key={key}
                      className={cn("cursor-pointer", active && "bg-muted/58")}
                      onClick={() => setSelectedStorage(row)}
                    >
                      <TableCell className="text-xs text-muted-foreground">{formatDateTime(row.event_time)}</TableCell>
                      <TableCell className="break-all text-xs text-foreground">{row.item_id}</TableCell>
                      <TableCell>{row.count}</TableCell>
                      <TableCell>
                        <Badge className={riskBadgeClass[row.risk_level] || statusBadgeClass.info}>{row.risk_level}</Badge>
                      </TableCell>
                      <TableCell className="text-xs text-muted-foreground">{formatCoords(row)}</TableCell>
                    </TableRow>
                  );
                })}
                {scanTotalRows === 0 && !scanQuery.isLoading && (
                  <TableRow>
                    <TableCell colSpan={5} className="text-center text-muted-foreground">
                      暂无扫描结果
                    </TableCell>
                  </TableRow>
                )}
              </TableBody>
            </Table>

            {scanQuery.isError && <div className="mt-4 text-sm text-destructive">{(scanQuery.error as Error).message}</div>}
          </div>

          <div className="min-w-0">
            <div className="section-subtitle">扫描详情</div>
            {!selectedStorage ? (
              <div className="mt-3 text-xs text-muted-foreground">选择一条记录查看详情</div>
            ) : (
              <div className="mt-3 section-stack text-sm text-foreground">
                <div>
                  <div className="text-xs text-muted-foreground">物品</div>
                  <div className="mt-1 break-all">{selectedStorage.item_id}</div>
                </div>
                <div>
                  <div className="text-xs text-muted-foreground">数量 / 阈值</div>
                  <div className="mt-1">
                    {selectedStorage.count} / {selectedStorage.threshold}
                  </div>
                </div>
                <div>
                  <div className="text-xs text-muted-foreground">风险</div>
                  <div className="mt-1">{selectedStorage.risk_level}</div>
                </div>
                <div>
                  <div className="text-xs text-muted-foreground">容器</div>
                  <div className="mt-1 break-all">{formatStorage(selectedStorage)}</div>
                </div>
                <div>
                  <div className="text-xs text-muted-foreground">维度 / 坐标</div>
                  <div className="mt-1">
                    {selectedStorage.dim || "-"} · {formatCoords(selectedStorage)}
                  </div>
                  <div className="mt-2">
                    <Button
                      size="sm"
                      variant="secondary"
                      disabled={!hasCoords(selectedStorage)}
                      onClick={() => handleCopyCoords(selectedStorage)}
                    >
                      复制坐标
                    </Button>
                  </div>
                </div>
                <div>
                  <div className="text-xs text-muted-foreground">原因</div>
                  <div className="mt-2 whitespace-pre-wrap">{selectedStorage.reason}</div>
                </div>
              </div>
            )}
          </div>
        </div>
      </motion.section>
    </motion.div>
  );
}
