import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
import { Button } from "@/components/ui/button";
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
import { fetchStorageScan } from "@/lib/api";
import { useSettings } from "@/lib/settings";
import type { StorageScanRow } from "@/lib/types";
import { cn } from "@/lib/utils";

const riskColor: Record<string, string> = {
  HIGH: "status-liquid-high",
  MEDIUM: "status-liquid-medium",
  LOW: "status-liquid-low",
};

function today() {
  return new Date().toISOString().slice(0, 10);
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

export function StorageScan() {
  const { settings } = useSettings();
  const [date, setDate] = React.useState(today());
  const [item, setItem] = React.useState("");
  const [limit, setLimit] = React.useState("200");
  const [selected, setSelected] = React.useState<StorageScanRow | null>(null);

  const limitValue = Number.parseInt(limit, 10);
  const effectiveLimit = Number.isFinite(limitValue)
    ? Math.min(Math.max(limitValue, 1), 2000)
    : 200;

  const scanQuery = useQuery({
    queryKey: ["storage-scan", settings.baseUrl, settings.apiToken, date, item, effectiveLimit],
    queryFn: () =>
      fetchStorageScan(
        settings.baseUrl,
        settings.apiToken,
        date,
        item.trim() || undefined,
        effectiveLimit,
      ),
  });

  const data = scanQuery.data || [];

  function rowKey(row: StorageScanRow) {
    return `${row.event_time}-${row.item_id}-${row.storage_id}-${row.x}-${row.z}`;
  }

  async function handleCopy(row: StorageScanRow) {
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

  return (
    <div className="space-y-4">
      <section className="section">
        <div className="section-header">
          <div className="section-title">区块扫描结果</div>
        </div>
        <div className="grid gap-4 md:grid-cols-3">
          <div className="grid gap-2">
            <Label>日期</Label>
            <Input
              type="date"
              value={date}
              onChange={(event) => setDate(event.target.value)}
            />
          </div>
          <div className="grid gap-2">
            <Label>物品 ID（可选）</Label>
            <Input
              value={item}
              onChange={(event) => setItem(event.target.value)}
              placeholder="minecraft:diamond"
            />
          </div>
          <div className="grid gap-2">
            <Label>最大返回条数</Label>
            <Input
              value={limit}
              onChange={(event) => setLimit(event.target.value)}
              placeholder="200"
            />
          </div>
        </div>
        <div className="mt-3 text-xs text-muted-foreground">
          仅展示超过阈值的存储快照，默认最多返回 200 条（上限 2000）。
        </div>
      </section>

      <section className="section">
        <div className="section-header">
          <div className="section-title">问题列表</div>
        </div>
        <div className="grid gap-6 lg:grid-cols-[1.6fr_1fr]">
          <div className="min-w-0">
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
                {data.map((row) => {
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
                        {row.event_time}
                      </TableCell>
                      <TableCell className="text-xs text-foreground break-all">
                        {row.item_id}
                      </TableCell>
                      <TableCell>{row.count}</TableCell>
                      <TableCell>
                        <Badge
                          className={
                            riskColor[row.risk_level] ||
                            "bg-muted text-muted-foreground"
                          }
                        >
                          {row.risk_level}
                        </Badge>
                      </TableCell>
                      <TableCell className="text-xs text-muted-foreground">
                        {formatCoords(row)}
                      </TableCell>
                    </TableRow>
                  );
                })}
                {data.length === 0 && !scanQuery.isLoading && (
                  <TableRow>
                    <TableCell
                      colSpan={5}
                      className="text-center text-muted-foreground"
                    >
                      暂无扫描结果
                    </TableCell>
                  </TableRow>
                )}
              </TableBody>
            </Table>
            {scanQuery.isError && (
              <div className="mt-4 text-sm text-primary">
                {(scanQuery.error as Error).message}
              </div>
            )}
          </div>
          <div className="min-w-0">
            <div className="section-subtitle">问题详情</div>
            {!selected ? (
              <div className="mt-3 text-xs text-muted-foreground">
                选择一条记录查看详情
              </div>
            ) : (
              <div className="mt-3 divide-y divide-border/60 text-sm text-foreground">
                <div className="py-3">
                  <div className="text-xs text-muted-foreground">物品</div>
                  <div className="mt-1 break-all">{selected.item_id}</div>
                </div>
                <div className="py-3">
                  <div className="text-xs text-muted-foreground">
                    数量 / 阈值
                  </div>
                  <div className="mt-1">
                    {selected.count} / {selected.threshold}
                  </div>
                </div>
                <div className="py-3">
                  <div className="text-xs text-muted-foreground">风险</div>
                  <div className="mt-1">{selected.risk_level}</div>
                </div>
                <div className="py-3">
                  <div className="text-xs text-muted-foreground">容器</div>
                  <div className="mt-1 break-all">
                    {formatStorage(selected)}
                  </div>
                </div>
                <div className="py-3">
                  <div className="text-xs text-muted-foreground">
                    维度 / 坐标
                  </div>
                  <div className="mt-1">
                    {selected.dim || "-"} · {formatCoords(selected)}
                  </div>
                  <div className="mt-2">
                    <Button
                      size="sm"
                      variant="secondary"
                      disabled={!hasCoords(selected)}
                      onClick={() => handleCopy(selected)}
                    >
                      复制坐标
                    </Button>
                  </div>
                </div>
                <div className="py-3">
                  <div className="text-xs text-muted-foreground">原因</div>
                  <div className="mt-2 whitespace-pre-wrap text-sm text-foreground">
                    {selected.reason}
                  </div>
                </div>
              </div>
            )}
          </div>
        </div>
      </section>
    </div>
  );
}
