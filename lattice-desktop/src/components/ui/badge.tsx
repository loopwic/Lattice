import * as React from "react";
import { cva, type VariantProps } from "class-variance-authority";

import { cn } from "@/lib/utils";

const badgeVariants = cva(
  "inline-flex items-center rounded-full border px-2.5 py-1 text-[11px] font-medium tracking-[0.03em] transition-colors duration-150 focus:outline-none focus:ring-1 focus:ring-ring/50",
  {
    variants: {
      variant: {
        default:
          "border-border/55 bg-muted/42 text-foreground",
        secondary:
          "border-border/55 bg-secondary/42 text-secondary-foreground",
        destructive:
          "border-border/60 bg-foreground/16 text-foreground",
        outline:
          "border-border/55 bg-transparent text-foreground/86",
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
