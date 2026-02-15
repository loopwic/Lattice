import * as React from "react";
import { getIdentifier, getName, getTauriVersion, getVersion } from "@tauri-apps/api/app";
import { isTauri } from "@tauri-apps/api/core";
import { Copy } from "lucide-react";
import { Button } from "@/components/ui/button";
import {
  Dialog,
  DialogClose,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from "@/components/ui/dialog";
import { ItemMarkIcon } from "@/components/item-mark";

type AppMeta = {
  name: string;
  version: string;
  identifier: string;
  tauriVersion: string;
};

const fallbackMeta: AppMeta = {
  name: "Lattice",
  version: "0.1.0",
  identifier: "com.lattice.desktop",
  tauriVersion: "-",
};

type AboutDialogProps = {
  open: boolean;
  onOpenChange: (open: boolean) => void;
};

export function AboutDialog({ open, onOpenChange }: AboutDialogProps) {
  const [meta, setMeta] = React.useState<AppMeta>(fallbackMeta);

  React.useEffect(() => {
    let cancelled = false;

    if (!open || !isTauri()) {
      return;
    }

    Promise.all([getName(), getVersion(), getIdentifier(), getTauriVersion()])
      .then(([name, version, identifier, tauriVersion]) => {
        if (cancelled) {
          return;
        }
        setMeta({ name, version, identifier, tauriVersion });
      })
      .catch(() => {
        // Keep fallback values when runtime metadata is unavailable.
      });

    return () => {
      cancelled = true;
    };
  }, [open]);

  function copyMeta() {
    const runtime = isTauri() ? "tauri" : "browser";
    const text = [
      `name=${meta.name}`,
      `version=${meta.version}`,
      `identifier=${meta.identifier}`,
      `tauri=${meta.tauriVersion}`,
      `runtime=${runtime}`,
    ].join("\n");
    void navigator.clipboard.writeText(text);
  }

  return (
    <Dialog open={open} onOpenChange={onOpenChange}>
      <DialogContent className="w-[min(92vw,32rem)] gap-5">
        <DialogHeader>
          <div className="mb-1 flex items-center gap-3">
            <div className="rounded-lg border hairline bg-muted/36 p-2 text-foreground">
              <ItemMarkIcon className="size-5" />
            </div>
            <div>
              <DialogTitle>关于应用</DialogTitle>
              <DialogDescription>轻量桌面监控控制台</DialogDescription>
            </div>
          </div>
        </DialogHeader>

        <div className="grid gap-1 text-sm text-muted-foreground">
          <div className="flex items-center justify-between border-b hairline py-2">
            <span>应用名称</span>
            <span className="text-foreground">{meta.name}</span>
          </div>
          <div className="flex items-center justify-between border-b hairline py-2">
            <span>应用版本</span>
            <span className="font-mono text-foreground">{meta.version}</span>
          </div>
          <div className="flex items-center justify-between border-b hairline py-2">
            <span>应用标识</span>
            <span className="font-mono text-foreground">{meta.identifier}</span>
          </div>
          <div className="flex items-center justify-between border-b hairline py-2">
            <span>Tauri 版本</span>
            <span className="font-mono text-foreground">{meta.tauriVersion}</span>
          </div>
        </div>

        <DialogFooter>
          <DialogClose asChild>
            <Button variant="ghost">关闭</Button>
          </DialogClose>
          <Button variant="secondary" onClick={copyMeta}>
            <Copy className="h-4 w-4" />
            复制信息
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
