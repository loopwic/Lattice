import { Badge } from "@/components/ui/badge";
import { cn } from "@/lib/utils";

export function StatusPill({ label, ok }: { label: string; ok: boolean }) {
  return (
    <Badge
      className={cn(
        "rounded-full px-2.5 py-1 text-[11px] font-medium tracking-[0.03em]",
        ok ? "status-liquid-ok" : "status-liquid-down",
      )}
    >
      {label}: {ok ? "OK" : "DOWN"}
    </Badge>
  );
}
