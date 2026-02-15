import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";

import { cn } from "@/lib/utils";

const badgeVariants = cva(
  "inline-flex items-center rounded-full px-2.5 py-1 text-[11px] font-medium tracking-[0.03em] transition-colors focus:outline-none focus:ring-1 focus:ring-ring/60",
  {
    variants: {
      variant: {
        default: "bg-muted/68 text-foreground",
        secondary: "bg-secondary/80 text-secondary-foreground",
        destructive: "bg-foreground/85 text-background",
        outline: "border border-border/65 bg-transparent text-foreground/90",
      },
    },
    defaultVariants: {
      variant: "default",
    },
  },
);

export interface BadgeProps
  extends
    React.HTMLAttributes<HTMLDivElement>,
    VariantProps<typeof badgeVariants> {}

function Badge({ className, variant, ...props }: BadgeProps) {
  return (
    <div className={cn(badgeVariants({ variant }), className)} {...props} />
  );
}

export { Badge, badgeVariants };
