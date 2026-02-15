import { QueryClient, QueryClientProvider } from "@tanstack/react-query";
import { RouterProvider } from "@tanstack/react-router";
import { ThemeProvider } from "next-themes";
import { FloatingToolBar } from "@/components/floating-tool-bar";
import { Toaster } from "@/components/ui/sonner";
import { router } from "@/router";
import { SettingsProvider } from "@/lib/settings";

const queryClient = new QueryClient();

export default function App() {
  return (
    <QueryClientProvider client={queryClient}>
      <ThemeProvider attribute="class" defaultTheme="system" enableSystem>
        <SettingsProvider>
          <RouterProvider router={router} />
          <FloatingToolBar />
          <Toaster />
        </SettingsProvider>
      </ThemeProvider>
    </QueryClientProvider>
  );
}
