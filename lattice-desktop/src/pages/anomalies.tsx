import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { TablePager } from "@/components/table-pager";
import { Input } from "@/components/ui/input";
import { Label } from "@/components/ui/label";
import {
  Table,
  TableBody,
  TableCell,
  TableHead,
  TableHeader,
  TableRow,
} from "@/components/ui/table";
import { Badge } from "@/components/ui/badge";
import { fetchAnomalies } from "@/lib/api";
import { formatDateTime } from "@/lib/datetime";
import { riskBadgeClass, statusBadgeClass } from "@/lib/status-badge";
import { useSettings } from "@/lib/settings";
import type { AnomalyRow } from "@/lib/types";
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

export function Anomalies() {
  const { settings } = useSettings();
  const [date, setDate] = React.useState(today());
  const [player, setPlayer] = React.useState("");
  const [selected, setSelected] = React.useState<AnomalyRow | null>(null);
  const [page, setPage] = React.useState(1);
  const [pageSize, setPageSize] = React.useState(50);

  const anomaliesQuery = useQuery({
    queryKey: ["anomalies", settings.baseUrl, settings.apiToken, date, player],
    queryFn: () =>
      fetchAnomalies(settings.baseUrl, settings.apiToken, date, player.trim() || undefined),
  });

  const data = anomaliesQuery.data || [];
  const totalRows = data.length;
  const totalPages = Math.max(1, Math.ceil(totalRows / pageSize));
  const safePage = Math.min(page, totalPages);
  const pageStart = (safePage - 1) * pageSize;
  const pageRows = data.slice(pageStart, pageStart + pageSize);

  React.useEffect(() => {
    setPage(1);
  }, [date, player, settings.baseUrl, settings.apiToken]);

  React.useEffect(() => {
    if (page > totalPages) {
      setPage(totalPages);
    }
  }, [page, totalPages]);

  function exportCsv() {
    if (!data.length) {
      toast.error("暂无可导出的记录");
      return;
    }
    const header = [
      "event_time",
      "player_name",
      "item_id",
      "count",
      "risk_level",
      "rule_id",
      "reason",
    ];
    const rows = data.map((row) => [
      row.event_time,
      row.player_name,
      row.item_id,
      row.count,
      row.risk_level,
      row.rule_id,
      row.reason,
    ]);
    const csv = [header, ...rows]
      .map((row) => row.map(escapeCsv).join(","))
      .join("\n");
    const blob = new Blob([csv], { type: "text/csv;charset=utf-8;" });
    const url = URL.createObjectURL(blob);
    const anchor = document.createElement("a");
    anchor.href = url;
    anchor.download = `anomalies_${date}.csv`;
    anchor.click();
    URL.revokeObjectURL(url);
    toast.success("已导出 CSV");
  }

  function rowKey(row: AnomalyRow) {
    return `${row.event_time}-${row.player_name}-${row.item_id}`;
  }

  return (
    <div className="space-y-4">
      <section className="section">
        <div className="section-header">
          <div className="section-title">异常列表</div>
          <Button variant="secondary" onClick={exportCsv}>
            导出 CSV
          </Button>
        </div>
        <div className="grid gap-4 md:grid-cols-2">
          <div className="grid gap-2">
            <Label>日期</Label>
            <Input
              type="date"
              value={date}
              onChange={(event) => setDate(event.target.value)}
            />
          </div>
          <div className="grid gap-2">
            <Label>玩家名（可选）</Label>
            <Input
              value={player}
              onChange={(event) => setPlayer(event.target.value)}
            />
          </div>
        </div>
      </section>

      <section className="section">
        <div className="section-header">
          <div className="section-title">结果</div>
        </div>
        <div className="grid gap-6 lg:grid-cols-[1.6fr_1fr]">
          <div className="min-w-0">
            <TablePager
              totalRows={totalRows}
              page={safePage}
              totalPages={totalPages}
              pageSize={pageSize}
              onPageSizeChange={setPageSize}
              onPrev={() => setPage((prev) => Math.max(1, prev - 1))}
              onNext={() =>
                setPage((prev) => Math.min(totalPages, prev + 1))
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
                {pageRows.map((row: AnomalyRow) => {
                  const key = rowKey(row);
                  const active = selected ? rowKey(selected) === key : false;
                  return (
                    <TableRow
                      key={key}
                      className={cn(
                        "cursor-pointer border-border",
                        active && "bg-muted/40",
                      )}
                      onClick={() => setSelected(row)}
                    >
                      <TableCell className="text-xs text-muted-foreground">
                        {formatDateTime(row.event_time)}
                      </TableCell>
                      <TableCell className="text-sm text-foreground">
                        {row.player_name}
                      </TableCell>
                      <TableCell className="text-xs text-muted-foreground break-all">
                        {row.item_id}
                      </TableCell>
                      <TableCell>{row.count}</TableCell>
                      <TableCell>
                        <Badge
                          className={
                            riskBadgeClass[row.risk_level] || statusBadgeClass.info
                          }
                        >
                          {row.risk_level}
                        </Badge>
                      </TableCell>
                      <TableCell className="text-xs text-muted-foreground">
                        {row.rule_id}
                      </TableCell>
                    </TableRow>
                  );
                })}
                {totalRows === 0 && !anomaliesQuery.isLoading && (
                  <TableRow>
                    <TableCell
                      colSpan={6}
                      className="text-center text-muted-foreground"
                    >
                      暂无异常记录
                    </TableCell>
                  </TableRow>
                )}
              </TableBody>
            </Table>
            {anomaliesQuery.isError && (
              <div className="mt-4 text-sm text-primary">
                {(anomaliesQuery.error as Error).message}
              </div>
            )}
          </div>
          <div className="min-w-0">
            <div className="section-subtitle">异常详情</div>
            {!selected ? (
              <div className="mt-3 text-xs text-muted-foreground">
                选择一条记录查看详情
              </div>
            ) : (
              <div className="mt-3 divide-y divide-border/60 text-sm text-foreground">
                <div className="py-3">
                  <div className="text-xs text-muted-foreground">玩家</div>
                  <div className="mt-1">{selected.player_name}</div>
                </div>
                <div className="py-3">
                  <div className="text-xs text-muted-foreground">时间</div>
                  <div className="mt-1">{formatDateTime(selected.event_time)}</div>
                </div>
                <div className="py-3">
                  <div className="text-xs text-muted-foreground">物品</div>
                  <div className="mt-1 break-all">{selected.item_id}</div>
                </div>
                <div className="py-3">
                  <div className="text-xs text-muted-foreground">数量</div>
                  <div className="mt-1">{selected.count}</div>
                </div>
                <div className="py-3">
                  <div className="text-xs text-muted-foreground">风险</div>
                  <div className="mt-1">{selected.risk_level}</div>
                </div>
                <div className="py-3">
                  <div className="text-xs text-muted-foreground">规则</div>
                  <div className="mt-1">{selected.rule_id}</div>
                </div>
                <div className="py-3">
                  <div className="text-xs text-muted-foreground">原因</div>
                  <div className="mt-2 whitespace-pre-wrap text-sm text-foreground">
                    {selected.reason}
                  </div>
                </div>
                <div className="py-3">
                  <div className="text-xs text-muted-foreground">证据</div>
                  <pre className="mt-2 max-h-72 overflow-auto rounded-md bg-muted/40 p-3 text-xs text-foreground">
                    {formatEvidence(selected.evidence_json)}
                  </pre>
                </div>
              </div>
            )}
          </div>
        </div>
      </section>
    </div>
  );
}
