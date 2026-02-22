import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import {
  type ColumnDef,
  type PaginationState,
  type SortingState,
  flexRender,
  getCoreRowModel,
  getPaginationRowModel,
  getSortedRowModel,
  useReactTable,
} from "@tanstack/react-table";
import { motion } from "motion/react";
import { toast } from "sonner";
import { Badge } from "@/components/ui/badge";
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
import { fetchAnomalies, fetchStorageScan } from "@/lib/api";
import { formatDateTime } from "@/lib/datetime";
import { useMotionPresets } from "@/lib/motion";
import { riskBadgeClass, statusBadgeClass } from "@/lib/status-badge";
import { useSettings } from "@/lib/settings";
import type { AnomalyRow, StorageScanRow } from "@/lib/types";
import { cn } from "@/lib/utils";

function today() {
  const now = new Date();
  const year = now.getFullYear();
  const month = String(now.getMonth() + 1).padStart(2, "0");
  const day = String(now.getDate()).padStart(2, "0");
  return `${year}-${month}-${day}`;
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

function anomalyRowKey(row: AnomalyRow) {
  return `${row.event_time}-${row.player_name}-${row.item_id}-${row.count}-${row.risk_level}-${row.rule_id}-${row.reason}`;
}

function scanRowKey(row: StorageScanRow) {
  return `${row.event_time}-${row.item_id}-${row.storage_id}-${row.storage_mod}-${row.count}-${row.threshold}-${row.risk_level}-${row.dim}-${row.x}-${row.y}-${row.z}`;
}

function isDateValue(value: string) {
  return /^\d{4}-\d{2}-\d{2}$/.test(value);
}

export function Investigate() {
  const { settings } = useSettings();
  const { variants } = useMotionPresets();

  const anomaliesRef = React.useRef<HTMLElement | null>(null);
  const storageRef = React.useRef<HTMLElement | null>(null);

  const [anomalyDate, setAnomalyDate] = React.useState(today());
  const [player, setPlayer] = React.useState("");
  const [selectedAnomaly, setSelectedAnomaly] = React.useState<AnomalyRow | null>(null);
  const [anomalySorting, setAnomalySorting] = React.useState<SortingState>([]);
  const [anomalyPagination, setAnomalyPagination] = React.useState<PaginationState>({
    pageIndex: 0,
    pageSize: 50,
  });

  const [scanDate, setScanDate] = React.useState(today());
  const [item, setItem] = React.useState("");
  const [limit, setLimit] = React.useState("200");
  const [selectedStorage, setSelectedStorage] = React.useState<StorageScanRow | null>(null);
  const [scanSorting, setScanSorting] = React.useState<SortingState>([]);
  const [scanPagination, setScanPagination] = React.useState<PaginationState>({
    pageIndex: 0,
    pageSize: 50,
  });

  const handleAnomalyDateChange = React.useCallback((value: string) => {
    setAnomalyDate(value);
  }, []);

  const handleScanDateChange = React.useCallback((value: string) => {
    setScanDate(value);
  }, []);

  const anomaliesQuery = useQuery({
    queryKey: ["anomalies", settings.baseUrl, settings.apiToken, anomalyDate, player],
    enabled: isDateValue(anomalyDate),
    queryFn: () =>
      fetchAnomalies(
        settings.baseUrl,
        settings.apiToken,
        anomalyDate,
        player.trim() || undefined,
      ),
  });

  const anomaliesData = anomaliesQuery.data || [];

  const limitValue = Number.parseInt(limit, 10);
  const effectiveLimit = Number.isFinite(limitValue)
    ? Math.min(Math.max(limitValue, 1), 2000)
    : 200;

  const scanQuery = useQuery({
    queryKey: [
      "storage-scan",
      settings.baseUrl,
      settings.apiToken,
      scanDate,
      item,
      effectiveLimit,
    ],
    enabled: isDateValue(scanDate),
    queryFn: () =>
      fetchStorageScan(
        settings.baseUrl,
        settings.apiToken,
        scanDate,
        item.trim() || undefined,
        effectiveLimit,
      ),
  });

  const scanData = scanQuery.data || [];

  const anomalyColumns = React.useMemo<ColumnDef<AnomalyRow>[]>(
    () => [
      {
        accessorKey: "event_time",
        header: "时间",
        cell: ({ row }) => (
          <span className="text-xs text-muted-foreground">
            {formatDateTime(row.original.event_time)}
          </span>
        ),
      },
      {
        accessorKey: "player_name",
        header: "玩家",
        cell: ({ row }) => <span className="text-sm text-foreground">{row.original.player_name}</span>,
      },
      {
        accessorKey: "item_id",
        header: "物品",
        cell: ({ row }) => (
          <span className="break-all text-xs text-muted-foreground">{row.original.item_id}</span>
        ),
      },
      {
        accessorKey: "count",
        header: "数量",
      },
      {
        accessorKey: "risk_level",
        header: "风险",
        cell: ({ row }) => (
          <Badge className={riskBadgeClass[row.original.risk_level] || statusBadgeClass.info}>
            {row.original.risk_level}
          </Badge>
        ),
      },
      {
        accessorKey: "rule_id",
        header: "规则",
        cell: ({ row }) => <span className="text-xs text-muted-foreground">{row.original.rule_id}</span>,
      },
    ],
    [],
  );

  const scanColumns = React.useMemo<ColumnDef<StorageScanRow>[]>(
    () => [
      {
        accessorKey: "event_time",
        header: "时间",
        cell: ({ row }) => (
          <span className="text-xs text-muted-foreground">
            {formatDateTime(row.original.event_time)}
          </span>
        ),
      },
      {
        accessorKey: "item_id",
        header: "物品",
        cell: ({ row }) => <span className="break-all text-xs text-foreground">{row.original.item_id}</span>,
      },
      {
        accessorKey: "count",
        header: "数量",
      },
      {
        accessorKey: "risk_level",
        header: "风险",
        cell: ({ row }) => (
          <Badge className={riskBadgeClass[row.original.risk_level] || statusBadgeClass.info}>
            {row.original.risk_level}
          </Badge>
        ),
      },
      {
        id: "coords",
        header: "坐标",
        enableSorting: false,
        cell: ({ row }) => <span className="text-xs text-muted-foreground">{formatCoords(row.original)}</span>,
      },
    ],
    [],
  );

  const anomalyTable = useReactTable({
    data: anomaliesData,
    columns: anomalyColumns,
    state: {
      sorting: anomalySorting,
      pagination: anomalyPagination,
    },
    onSortingChange: setAnomalySorting,
    onPaginationChange: setAnomalyPagination,
    getCoreRowModel: getCoreRowModel(),
    getSortedRowModel: getSortedRowModel(),
    getPaginationRowModel: getPaginationRowModel(),
  });

  const scanTable = useReactTable({
    data: scanData,
    columns: scanColumns,
    state: {
      sorting: scanSorting,
      pagination: scanPagination,
    },
    onSortingChange: setScanSorting,
    onPaginationChange: setScanPagination,
    getCoreRowModel: getCoreRowModel(),
    getSortedRowModel: getSortedRowModel(),
    getPaginationRowModel: getPaginationRowModel(),
  });

  const anomalyPageState = anomalyTable.getState().pagination;
  const anomalyTotalRows = anomaliesData.length;
  const anomalyTotalPages = Math.max(1, anomalyTable.getPageCount());
  const anomalyMaxPageIndex = Math.max(0, anomalyTotalPages - 1);
  const anomalySafePage = Math.min(anomalyPageState.pageIndex + 1, anomalyTotalPages);

  const scanPageState = scanTable.getState().pagination;
  const scanTotalRows = scanData.length;
  const scanTotalPages = Math.max(1, scanTable.getPageCount());
  const scanMaxPageIndex = Math.max(0, scanTotalPages - 1);
  const scanSafePage = Math.min(scanPageState.pageIndex + 1, scanTotalPages);

  React.useEffect(() => {
    setAnomalyPagination((prev) => ({ ...prev, pageIndex: 0 }));
  }, [anomalyDate, player, settings.baseUrl, settings.apiToken]);

  React.useEffect(() => {
    setScanPagination((prev) => ({ ...prev, pageIndex: 0 }));
  }, [scanDate, item, effectiveLimit, settings.baseUrl, settings.apiToken]);

  React.useEffect(() => {
    if (anomalyPageState.pageIndex > anomalyMaxPageIndex) {
      anomalyTable.setPageIndex(anomalyMaxPageIndex);
    }
  }, [anomalyMaxPageIndex, anomalyPageState.pageIndex, anomalyTable]);

  React.useEffect(() => {
    if (scanPageState.pageIndex > scanMaxPageIndex) {
      scanTable.setPageIndex(scanMaxPageIndex);
    }
  }, [scanMaxPageIndex, scanPageState.pageIndex, scanTable]);

  React.useEffect(() => {
    if (selectedAnomaly) {
      const exists = anomaliesData.some((row) => anomalyRowKey(row) === anomalyRowKey(selectedAnomaly));
      if (!exists) {
        setSelectedAnomaly(null);
      }
    }
  }, [anomaliesData, selectedAnomaly]);

  React.useEffect(() => {
    if (selectedStorage) {
      const exists = scanData.some((row) => scanRowKey(row) === scanRowKey(selectedStorage));
      if (!exists) {
        setSelectedStorage(null);
      }
    }
  }, [scanData, selectedStorage]);

  function jumpTo(target: "anomalies" | "storage") {
    const section = target === "anomalies" ? anomaliesRef.current : storageRef.current;
    section?.scrollIntoView({ behavior: "smooth", block: "start" });
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
    const header = [
      "event_time",
      "player_name",
      "item_id",
      "count",
      "risk_level",
      "rule_id",
      "reason",
    ];
    const rows = anomaliesData.map((row) => [
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
            <Input
              type="date"
              value={anomalyDate}
              onInput={(event) => handleAnomalyDateChange(event.currentTarget.value)}
              onChange={(event) => handleAnomalyDateChange(event.currentTarget.value)}
            />
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
              pageSize={anomalyPageState.pageSize}
              canPrev={anomalyTable.getCanPreviousPage()}
              canNext={anomalyTable.getCanNextPage()}
              onPageSizeChange={(size) => {
                anomalyTable.setPageSize(size);
                anomalyTable.setPageIndex(0);
              }}
              onPrev={() => anomalyTable.previousPage()}
              onNext={() => anomalyTable.nextPage()}
            />
            <Table>
              <TableHeader>
                {anomalyTable.getHeaderGroups().map((group) => (
                  <TableRow key={group.id}>
                    {group.headers.map((header) => (
                      <TableHead
                        key={header.id}
                        className={cn(header.column.getCanSort() && "cursor-pointer select-none")}
                        onClick={header.column.getToggleSortingHandler()}
                      >
                        {header.isPlaceholder
                          ? null
                          : flexRender(header.column.columnDef.header, header.getContext())}
                      </TableHead>
                    ))}
                  </TableRow>
                ))}
              </TableHeader>
              <TableBody>
                {anomalyTable.getRowModel().rows.map((row) => {
                  const identity = anomalyRowKey(row.original);
                  const active = selectedAnomaly ? anomalyRowKey(selectedAnomaly) === identity : false;
                  return (
                    <TableRow
                      key={row.id}
                      className={cn("cursor-pointer", active && "bg-muted/58")}
                      onClick={() => setSelectedAnomaly(row.original)}
                    >
                      {row.getVisibleCells().map((cell) => (
                        <TableCell key={cell.id}>
                          {flexRender(cell.column.columnDef.cell, cell.getContext())}
                        </TableCell>
                      ))}
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
            <Input
              type="date"
              value={scanDate}
              onInput={(event) => handleScanDateChange(event.currentTarget.value)}
              onChange={(event) => handleScanDateChange(event.currentTarget.value)}
            />
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
              pageSize={scanPageState.pageSize}
              canPrev={scanTable.getCanPreviousPage()}
              canNext={scanTable.getCanNextPage()}
              onPageSizeChange={(size) => {
                scanTable.setPageSize(size);
                scanTable.setPageIndex(0);
              }}
              onPrev={() => scanTable.previousPage()}
              onNext={() => scanTable.nextPage()}
            />
            <Table>
              <TableHeader>
                {scanTable.getHeaderGroups().map((group) => (
                  <TableRow key={group.id}>
                    {group.headers.map((header) => (
                      <TableHead
                        key={header.id}
                        className={cn(header.column.getCanSort() && "cursor-pointer select-none")}
                        onClick={header.column.getToggleSortingHandler()}
                      >
                        {header.isPlaceholder
                          ? null
                          : flexRender(header.column.columnDef.header, header.getContext())}
                      </TableHead>
                    ))}
                  </TableRow>
                ))}
              </TableHeader>
              <TableBody>
                {scanTable.getRowModel().rows.map((row) => {
                  const identity = scanRowKey(row.original);
                  const active = selectedStorage ? scanRowKey(selectedStorage) === identity : false;
                  return (
                    <TableRow
                      key={row.id}
                      className={cn("cursor-pointer", active && "bg-muted/58")}
                      onClick={() => setSelectedStorage(row.original)}
                    >
                      {row.getVisibleCells().map((cell) => (
                        <TableCell key={cell.id}>
                          {flexRender(cell.column.columnDef.cell, cell.getContext())}
                        </TableCell>
                      ))}
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

            {scanQuery.isError && (
              <div className="mt-4 text-sm text-destructive">{(scanQuery.error as Error).message}</div>
            )}
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
