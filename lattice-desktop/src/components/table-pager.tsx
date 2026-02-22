import { ChevronLeft, ChevronRight } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Select,
  SelectContent,
  SelectItem,
  SelectTrigger,
  SelectValue,
} from "@/components/ui/select";
import { cn } from "@/lib/utils";

type TablePagerProps = {
  totalRows: number;
  page: number;
  totalPages: number;
  pageSize: number;
  canPrev?: boolean;
  canNext?: boolean;
  onPageSizeChange: (size: number) => void;
  onPrev: () => void;
  onNext: () => void;
  className?: string;
};

const PAGE_SIZE_OPTIONS = [25, 50, 100, 200] as const;

export function TablePager({
  totalRows,
  page,
  totalPages,
  pageSize,
  canPrev,
  canNext,
  onPageSizeChange,
  onPrev,
  onNext,
  className,
}: TablePagerProps) {
  const prevDisabled = canPrev === undefined ? page <= 1 : !canPrev;
  const nextDisabled = canNext === undefined ? page >= totalPages : !canNext;

  return (
    <div
      className={cn(
        "mb-2 flex flex-wrap items-center justify-between gap-2",
        className,
      )}
    >
      <div className="text-[11px] text-muted-foreground">
        共 {totalRows} 条
      </div>

      <div className="flex items-center gap-1.5">
        <div className="w-[6.25rem]">
          <Select
            value={String(pageSize)}
            onValueChange={(value) => onPageSizeChange(Number(value))}
          >
            <SelectTrigger className="h-7 rounded-md border-border/60 bg-background px-2 text-[11px]">
              <SelectValue placeholder="每页条数" />
            </SelectTrigger>
            <SelectContent>
              {PAGE_SIZE_OPTIONS.map((size) => (
                <SelectItem key={size} value={String(size)}>
                  {size} / 页
                </SelectItem>
              ))}
            </SelectContent>
          </Select>
        </div>

        <span className="px-1 text-[11px] text-muted-foreground tabular-nums">
          {page}/{totalPages}
        </span>

        <Button
          size="sm"
          variant="ghost"
          disabled={prevDisabled}
          onClick={onPrev}
          className="h-7 w-7 rounded-md border border-border/60 bg-background p-0 hover:bg-muted"
          aria-label="上一页"
        >
          <ChevronLeft className="h-3.5 w-3.5" />
        </Button>

        <Button
          size="sm"
          variant="ghost"
          disabled={nextDisabled}
          onClick={onNext}
          className="h-7 w-7 rounded-md border border-border/60 bg-background p-0 hover:bg-muted"
          aria-label="下一页"
        >
          <ChevronRight className="h-3.5 w-3.5" />
        </Button>
      </div>
    </div>
  );
}
