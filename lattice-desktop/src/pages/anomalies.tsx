import * as React from "react";
import { useQuery } from "@tanstack/react-query";
import {
  type ColumnDef,
  type PaginationState,
  type SortingState,
  flexRender,
  getCoreRowModel,
  getSortedRowModel,
  useReactTable,
} from "@tanstack/react-table";
import { toast } from "sonner";
import { Button } from "@/components/ui/button";
import { DateFilterField } from "@/components/date-filter-field";
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

function rowKey(row: AnomalyRow) {
  return `${row.event_time}-${row.player_name}-${row.item_id}-${row.count}-${row.risk_level}-${row.rule_id}-${row.reason}`;
}

function isDateValue(value: string) {
  return /^\d{4}-\d{2}-\d{2}$/.test(value);
}

export function Anomalies() {
  const { settings } = useSettings();
  const [date, setDate] = React.useState(today());
  const [player, setPlayer] = React.useState("");
  const [selected, setSelected] = React.useState<AnomalyRow | null>(null);
  const [sorting, setSorting] = React.useState<SortingState>([]);
  const [pagination, setPagination] = React.useState<PaginationState>({
    pageIndex: 0,
    pageSize: 50,
  });

  const handleDateChange = React.useCallback((value: string) => {
    setDate(value);
  }, []);

  const anomaliesQuery = useQuery({
    queryKey: [
      "anomalies",
      settings.baseUrl,
      settings.apiToken,
      date,
      player,
      pagination.pageIndex,
      pagination.pageSize,
    ],
    enabled: isDateValue(date),
    queryFn: () =>
      fetchAnomalies(
        settings.baseUrl,
        settings.apiToken,
        date,
        pagination.pageIndex + 1,
        pagination.pageSize,
        player.trim() || undefined,
      ),
  });

  const data = anomaliesQuery.data?.items || [];

  const columns = React.useMemo<ColumnDef<AnomalyRow>[]>(
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
        cell: ({ row }) => (
          <span className="text-sm text-foreground">{row.original.player_name}</span>
        ),
      },
      {
        accessorKey: "item_id",
        header: "物品",
        cell: ({ row }) => (
          <span className="text-xs text-muted-foreground break-all">
            {row.original.item_id}
          </span>
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
          <Badge
            className={
              riskBadgeClass[row.original.risk_level] || statusBadgeClass.info
            }
          >
            {row.original.risk_level}
          </Badge>
        ),
      },
      {
        accessorKey: "rule_id",
        header: "规则",
        cell: ({ row }) => (
          <span className="text-xs text-muted-foreground">{row.original.rule_id}</span>
        ),
      },
    ],
    [],
  );

  const table = useReactTable({
    data,
    columns,
    state: {
      sorting,
    },
    onSortingChange: setSorting,
    getCoreRowModel: getCoreRowModel(),
    getSortedRowModel: getSortedRowModel(),
  });

  const pageState = pagination;
  const totalRows = anomaliesQuery.data?.total_items ?? 0;
  const pageCount = Math.max(1, anomaliesQuery.data?.total_pages ?? 1);
  const maxPageIndex = Math.max(0, pageCount - 1);
  const safePage = Math.min(pageState.pageIndex + 1, pageCount);

  React.useEffect(() => {
    setPagination((prev) => ({ ...prev, pageIndex: 0 }));
  }, [date, player, settings.baseUrl, settings.apiToken]);

  React.useEffect(() => {
    if (pageState.pageIndex > maxPageIndex) {
      setPagination((prev) => ({ ...prev, pageIndex: maxPageIndex }));
    }
  }, [maxPageIndex, pageState.pageIndex]);

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
          <DateFilterField value={date} onChange={handleDateChange} />
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
              totalPages={pageCount}
              pageSize={pageState.pageSize}
              canPrev={pageState.pageIndex > 0}
              canNext={pageState.pageIndex < maxPageIndex}
              onPageSizeChange={(size) => {
                setPagination({ pageIndex: 0, pageSize: size });
              }}
              onPrev={() =>
                setPagination((prev) => ({
                  ...prev,
                  pageIndex: Math.max(0, prev.pageIndex - 1),
                }))
              }
              onNext={() =>
                setPagination((prev) => ({
                  ...prev,
                  pageIndex: Math.min(maxPageIndex, prev.pageIndex + 1),
                }))
              }
            />
            <Table>
              <TableHeader>
                {table.getHeaderGroups().map((group) => (
                  <TableRow key={group.id}>
                    {group.headers.map((header) => (
                      <TableHead
                        key={header.id}
                        className={cn(
                          header.column.getCanSort() &&
                            "cursor-pointer select-none",
                        )}
                        onClick={header.column.getToggleSortingHandler()}
                      >
                        {header.isPlaceholder
                          ? null
                          : flexRender(
                              header.column.columnDef.header,
                              header.getContext(),
                            )}
                      </TableHead>
                    ))}
                  </TableRow>
                ))}
              </TableHeader>
              <TableBody>
                {table.getRowModel().rows.map((row) => {
                  const identity = rowKey(row.original);
                  const active = selected ? rowKey(selected) === identity : false;
                  return (
                    <TableRow
                      key={row.id}
                      className={cn(
                        "cursor-pointer border-border",
                        active && "bg-muted/40",
                      )}
                      onClick={() => setSelected(row.original)}
                    >
                      {row.getVisibleCells().map((cell) => (
                        <TableCell key={cell.id}>
                          {flexRender(cell.column.columnDef.cell, cell.getContext())}
                        </TableCell>
                      ))}
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
