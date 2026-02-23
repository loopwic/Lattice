import { cn } from "@/lib/utils";

type StateProps = {
  className?: string;
  message: string;
};

export function LoadingState({ className, message }: StateProps) {
  return (
    <div className={cn("rounded-md border border-border/60 bg-muted/35 px-3 py-2 text-sm text-muted-foreground", className)}>
      {message}
    </div>
  );
}

export function EmptyState({ className, message }: StateProps) {
  return (
    <div className={cn("rounded-md border border-border/60 bg-background px-3 py-2 text-sm text-muted-foreground", className)}>
      {message}
    </div>
  );
}

export function ErrorState({ className, message }: StateProps) {
  return (
    <div className={cn("rounded-md border border-destructive/45 bg-destructive/10 px-3 py-2 text-sm text-destructive", className)}>
      {message}
    </div>
  );
}

export function TableStateBanner({ className, message }: StateProps) {
  return (
    <div className={cn("mb-2 rounded-md border border-border/55 bg-muted/28 px-3 py-1.5 text-xs text-muted-foreground", className)}>
      {message}
    </div>
  );
}
