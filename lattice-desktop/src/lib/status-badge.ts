export const statusBadgeClass = {
  high: "border-border/60 bg-foreground/14 text-foreground",
  medium: "border-border/60 bg-foreground/10 text-foreground/92",
  low: "border-border/60 bg-foreground/6 text-foreground/86",
  ok: "border-border/60 bg-foreground/8 text-foreground/90",
  down: "border-border/70 bg-foreground/16 text-foreground",
  info: "border-border/55 bg-transparent text-foreground/82",
} as const;

export const riskBadgeClass: Record<string, string> = {
  HIGH: statusBadgeClass.high,
  MEDIUM: statusBadgeClass.medium,
  LOW: statusBadgeClass.low,
};
