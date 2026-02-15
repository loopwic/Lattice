import * as React from "react";
import {
  Link,
  Navigate,
  Outlet,
  createRootRoute,
  createRoute,
  createRouter,
  useRouter,
  useRouterState,
} from "@tanstack/react-router";
import { isTauri } from "@tauri-apps/api/core";
import { AnimatePresence, motion } from "motion/react";
import {
  Compass,
  FolderSearch,
  Search,
  ShieldCheck,
  SlidersHorizontal,
  Wrench,
} from "lucide-react";
import { AboutDialog } from "@/components/about-dialog";
import { ItemMarkIcon } from "@/components/item-mark";
import { useMotionPresets } from "@/lib/motion";
import { cn } from "@/lib/utils";
import { Investigate } from "@/pages/investigate";
import { Operations } from "@/pages/operations";
import { Overview } from "@/pages/overview";
import { Policy } from "@/pages/policy";
import { System } from "@/pages/system";

type NavItem = {
  to: string;
  label: string;
  meta: string;
  icon: React.ComponentType<{ className?: string }>;
};

type CommandItem = {
  id: string;
  label: string;
  search: string;
  trail: string;
  icon: React.ComponentType<{ className?: string }>;
  to?: string;
  action?: "about";
};

const navItems: NavItem[] = [
  { to: "/overview", label: "总览", meta: "Overview", icon: Compass },
  {
    to: "/investigate",
    label: "监控调查",
    meta: "Investigate",
    icon: FolderSearch,
  },
  { to: "/policy", label: "规则策略", meta: "Policy", icon: ShieldCheck },
  { to: "/operations", label: "运维执行", meta: "Operations", icon: Wrench },
  { to: "/system", label: "系统配置", meta: "System", icon: SlidersHorizontal },
];

const commandItemsSeed: CommandItem[] = [
  ...navItems.map((item) => ({
    id: item.to,
    to: item.to,
    label: item.label,
    trail: item.to,
    icon: item.icon,
    search: `${item.meta} ${item.label} ${item.to}`,
  })),
  {
    id: "about-dialog",
    label: "关于应用",
    trail: "弹窗",
    icon: ItemMarkIcon,
    search: "about app version 关于应用 弹窗",
    action: "about",
  },
];

const tauriReady = isTauri();

function NavButton({ item }: { item: NavItem }) {
  const pathname = useRouterState({
    select: (state) => state.location.pathname,
  });
  const active = item.to === pathname;
  const Icon = item.icon;

  return (
    <Link
      to={item.to}
      preload="intent"
      className={cn(
        "group relative block rounded-md px-3 py-2 text-sm text-muted-foreground no-underline transition-colors",
        "hover:text-foreground",
      )}
    >
      {active && (
        <motion.span
          layoutId="nav-active"
          className="pointer-events-none absolute inset-0 rounded-md border hairline panel-muted"
          transition={{ duration: 0.16, ease: [0.22, 1, 0.36, 1] }}
        />
      )}
      <span className="relative z-10 flex items-center gap-3">
        <Icon
          className={cn(
            "h-4 w-4",
            active ? "text-foreground" : "text-muted-foreground",
          )}
        />
        <span
          className={cn("text-sm font-medium", active && "text-foreground")}
        >
          {item.label}
        </span>
      </span>
      <span className="relative z-10 mt-1 block pl-7 text-[10px] tracking-[0.06em] text-muted-foreground/90">
        {item.meta}
      </span>
    </Link>
  );
}

function Layout() {
  const router = useRouter();
  const { variants } = useMotionPresets();
  const pathname = useRouterState({
    select: (state) => state.location.pathname,
  });

  const [commandOpen, setCommandOpen] = React.useState(false);
  const [aboutOpen, setAboutOpen] = React.useState(false);
  const [commandQuery, setCommandQuery] = React.useState("");
  const commandPaletteRef = React.useRef<HTMLInputElement | null>(null);

  React.useEffect(() => {
    function handleKey(event: KeyboardEvent) {
      const isMeta = event.metaKey || event.ctrlKey;
      if (isMeta && event.key.toLowerCase() === "k") {
        event.preventDefault();
        setCommandOpen((prev) => !prev);
      }
      if (isMeta && event.key === ",") {
        event.preventDefault();
        router.navigate({ to: "/system" });
      }
      if (event.key === "Escape") {
        setCommandOpen(false);
      }
    }

    document.addEventListener("keydown", handleKey, true);
    return () => document.removeEventListener("keydown", handleKey, true);
  }, [router]);

  React.useEffect(() => {
    if (commandOpen) {
      requestAnimationFrame(() => {
        commandPaletteRef.current?.focus();
      });
    }
  }, [commandOpen]);

  const commandItems = React.useMemo(() => {
    const query = commandQuery.trim().toLowerCase();
    if (!query) {
      return commandItemsSeed;
    }
    return commandItemsSeed.filter(
      (item) =>
        item.search.toLowerCase().includes(query) ||
        item.label.toLowerCase().includes(query),
    );
  }, [commandQuery]);

  function runCommand(item: CommandItem) {
    if (item.to) {
      router.navigate({ to: item.to });
    }
    if (item.action === "about") {
      setAboutOpen(true);
    }
    setCommandOpen(false);
    setCommandQuery("");
  }

  return (
    <div className="shell-bg h-screen text-foreground">
      <div className="grid h-full min-h-0 grid-cols-[220px_1fr] overflow-hidden">
        <aside
          className={cn(
            "flex min-h-0 flex-col border-r hairline px-3 py-4",
            tauriReady ? "pt-11" : "",
          )}
        >
          <div className="mb-6 px-2">
            <div className="inline-flex items-center gap-1.5 rounded-md border hairline bg-muted/32 px-2 py-1 text-[10px] tracking-[0.08em] text-muted-foreground">
              <ItemMarkIcon className="size-3.5" />
              Workflow
            </div>
          </div>

          <nav className="space-y-1">
            {navItems.map((item) => (
              <NavButton key={item.to} item={item} />
            ))}
          </nav>

          <div className="mt-auto border-t hairline px-1 pt-3">
            <div className="space-y-1">
              <button
                type="button"
                onClick={() => setCommandOpen(true)}
                className="flex w-full items-center justify-between rounded-md px-2 py-1 text-[11px] text-muted-foreground transition-colors hover:bg-muted/40 hover:text-foreground"
              >
                <span>搜索命令</span>
                <span className="kbd-chip">⌘K</span>
              </button>
              <button
                type="button"
                onClick={() => setAboutOpen(true)}
                className="flex w-full items-center justify-between rounded-md px-2 py-1 text-[11px] text-muted-foreground transition-colors hover:bg-muted/40 hover:text-foreground"
              >
                <span>关于应用</span>
                <span className="text-[10px] tracking-[0.08em] text-muted-foreground/72">
                  ABOUT
                </span>
              </button>
            </div>
            <div className="mt-2 px-2 text-[10px] tracking-[0.08em] text-muted-foreground/78">
              DESKTOP v0.1.0
            </div>
          </div>
        </aside>

        <div className="flex min-h-0 min-w-0 flex-col overflow-hidden">
          <main className="min-h-0 flex-1 overflow-y-auto px-5 py-5">
            <AnimatePresence mode="wait" initial={false}>
              <motion.div
                key={pathname}
                className="min-h-full"
                variants={variants.pageFade}
                initial="initial"
                animate="enter"
                exit="exit"
              >
                <Outlet />
              </motion.div>
            </AnimatePresence>
          </main>
        </div>
      </div>

      <AnimatePresence>
        {commandOpen && (
          <motion.div
            className="glass-overlay fixed inset-0 z-50 flex items-start justify-center"
            variants={variants.overlayFade}
            initial="initial"
            animate="enter"
            exit="exit"
            onClick={(event) => {
              if (event.currentTarget === event.target) {
                setCommandOpen(false);
              }
            }}
          >
            <motion.div
              className="mt-16 w-[min(92vw,46rem)] overflow-hidden rounded-2xl border hairline bg-card/96"
              variants={variants.popoverScale}
              initial="initial"
              animate="enter"
              exit="exit"
            >
              <div className="border-b hairline px-4 py-3">
                <div className="flex items-center justify-between">
                  <div className="text-[10px] tracking-[0.07em] text-muted-foreground">
                    Command Palette
                  </div>
                  <span className="kbd-chip">⌘K</span>
                </div>
                <div className="mt-2 flex items-center gap-2 rounded-md border hairline bg-muted/30 px-3 py-2.5">
                  <Search className="h-4 w-4 text-muted-foreground" />
                  <input
                    ref={commandPaletteRef}
                    value={commandQuery}
                    onChange={(event) => setCommandQuery(event.target.value)}
                    placeholder="搜索页面或命令，例如 system / 关于"
                    className="h-5 w-full bg-transparent text-sm text-foreground outline-none placeholder:text-muted-foreground/80"
                    onKeyDown={(event) => {
                      if (event.key === "Enter" && commandItems.length > 0) {
                        event.preventDefault();
                        runCommand(commandItems[0]);
                      }
                    }}
                  />
                </div>
              </div>

              <div className="max-h-[52vh] overflow-y-auto px-2 py-2">
                <div className="space-y-1">
                  {commandItems.map((item) => {
                    const Icon = item.icon;
                    return (
                      <button
                        key={item.id}
                        onClick={() => runCommand(item)}
                        className="flex w-full items-center justify-between rounded-lg px-3 py-2.5 text-left transition-colors hover:bg-muted/52"
                      >
                        <span className="flex items-center gap-3">
                          <Icon className="h-4 w-4 text-muted-foreground" />
                          <span className="text-sm text-foreground">{item.label}</span>
                        </span>
                        <span className="text-[11px] tracking-[0.04em] text-muted-foreground/84">
                          {item.trail}
                        </span>
                      </button>
                    );
                  })}

                  {commandItems.length === 0 && (
                    <div className="px-3 py-8 text-center text-xs text-muted-foreground">
                      没有匹配的结果
                    </div>
                  )}
                </div>
              </div>

              <div className="border-t hairline px-4 py-2.5 text-[11px] text-muted-foreground">
                <div className="flex items-center justify-between">
                  <span>Enter 打开 · Esc 关闭</span>
                  <span>Cmd/Ctrl + K 呼出</span>
                </div>
              </div>
            </motion.div>
          </motion.div>
        )}
      </AnimatePresence>

      <AboutDialog open={aboutOpen} onOpenChange={setAboutOpen} />
    </div>
  );
}

function HomeRedirect() {
  return <Navigate to="/overview" replace />;
}

const rootRoute = createRootRoute({
  component: Layout,
});

const homeRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "/",
  component: HomeRedirect,
});

const overviewRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "overview",
  component: Overview,
});

const investigateRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "investigate",
  component: Investigate,
});

const policyRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "policy",
  component: Policy,
});

const operationsRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "operations",
  component: Operations,
});

const systemRoute = createRoute({
  getParentRoute: () => rootRoute,
  path: "system",
  component: System,
});

const routeTree = rootRoute.addChildren([
  homeRoute,
  overviewRoute,
  investigateRoute,
  policyRoute,
  operationsRoute,
  systemRoute,
]);

export const router = createRouter({
  routeTree,
  defaultPreload: "intent",
});

declare module "@tanstack/react-router" {
  interface Register {
    router: typeof router;
  }
}
