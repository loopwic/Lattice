import * as React from "react";
import { cn } from "@/lib/utils";

type ItemMarkIconProps = React.SVGProps<SVGSVGElement>;

export function ItemMarkIcon({ className, ...props }: ItemMarkIconProps) {
  return (
    <svg
      viewBox="0 0 64 64"
      fill="none"
      xmlns="http://www.w3.org/2000/svg"
      className={cn("size-5", className)}
      aria-hidden="true"
      {...props}
    >
      <rect x="4" y="4" width="56" height="56" rx="16" fill="#101113" />
      <g fill="#E8EDF4">
        <path opacity="0.96" d="M32 14L46 28L32 42L18 28L32 14Z" />
        <rect opacity="0.88" x="20.5" y="45" width="23" height="4" rx="2" />
      </g>
    </svg>
  );
}
