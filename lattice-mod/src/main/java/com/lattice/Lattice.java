package com.lattice;

import com.lattice.config.LatticeConfig;
import com.lattice.config.RuntimeConfigStore;
import com.lattice.config.sync.ConfigSyncManager;
import com.lattice.commands.LatticeCommands;
import com.lattice.events.EventQueue;
import com.lattice.integrations.IntegrationManager;
import com.lattice.logging.StructuredOpsLogger;
import com.lattice.scheduler.MonitorScheduler;
import com.lattice.registry.ItemRegistryReporter;
import com.lattice.rcon.RconConfigReporter;
import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.server.MinecraftServer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

public class Lattice implements ModInitializer {
    public static final String MOD_ID = "lattice";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static LatticeConfig config;
    private static RuntimeConfigStore configStore;
    private static EventQueue eventQueue;
    private static MonitorScheduler scheduler;
    private static ConfigSyncManager configSyncManager;

    @Override
    public void onInitialize() {
        config = LatticeConfig.load();
        configStore = new RuntimeConfigStore(config);
        eventQueue = new EventQueue(config);
        IntegrationManager.initialize(config, eventQueue);
        LatticeCommands.register();
        scheduler = new MonitorScheduler(config);
        configSyncManager = new ConfigSyncManager(configStore);
        ServerTickEvents.END_SERVER_TICK.register(server -> scheduler.tick(server));

        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            if (configSyncManager != null) {
                configSyncManager.stop();
            }
            if (scheduler != null) {
                scheduler.shutdown();
            }
            eventQueue.shutdown();
        });

        LOGGER.info("Lattice initialized. integrations loaded: {}", IntegrationManager.summary());
    }

    private void onServerStarted(MinecraftServer server) {
        eventQueue.start(server);
        ItemRegistryReporter.reportAsync(server);
        RconConfigReporter.reportAsync(server);
        if (configSyncManager != null) {
            configSyncManager.start();
        }
        LOGGER.info("Lattice started on server.");
    }

    public static LatticeConfig getConfig() {
        if (configStore != null) {
            return configStore.current();
        }
        return config;
    }

    public static EventQueue getEventQueue() {
        return eventQueue;
    }

    public static MonitorScheduler getScheduler() {
        return scheduler;
    }

    public static synchronized void applyConfig(LatticeConfig next, String source) {
        if (next == null) {
            return;
        }
        LatticeConfig previous = getConfig();
        config = next;
        if (configStore != null) {
            configStore.swap(next);
        }
        if (eventQueue != null) {
            eventQueue.updateConfig(next);
        }
        if (scheduler != null) {
            scheduler.updateConfig(next);
        }
        StructuredOpsLogger.info(
            "config_applied",
            Map.of(
                "source", source == null ? "unknown" : source,
                "server_id", next.serverId,
                "backend_url", next.backendUrl,
                "scan_enabled", next.scanEnabled,
                "previous_scan_enabled", previous != null && previous.scanEnabled
            )
        );
        LOGGER.info("Lattice config applied from {}", source == null ? "unknown" : source);
    }

    public static boolean isModLoaded(String id) {
        return FabricLoader.getInstance().isModLoaded(id);
    }
}
