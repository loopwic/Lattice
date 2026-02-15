package com.lattice;

import com.lattice.config.LatticeConfig;
import com.lattice.commands.LatticeCommands;
import com.lattice.events.EventQueue;
import com.lattice.integrations.IntegrationManager;
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

public class Lattice implements ModInitializer {
    public static final String MOD_ID = "lattice";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static LatticeConfig config;
    private static EventQueue eventQueue;
    private static MonitorScheduler scheduler;

    @Override
    public void onInitialize() {
        config = LatticeConfig.load();
        eventQueue = new EventQueue(config);
        IntegrationManager.initialize(config, eventQueue);
        LatticeCommands.register();
        scheduler = new MonitorScheduler(config);
        ServerTickEvents.END_SERVER_TICK.register(server -> scheduler.tick(server));

        ServerLifecycleEvents.SERVER_STARTED.register(this::onServerStarted);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> eventQueue.shutdown());

        LOGGER.info("Lattice initialized. integrations loaded: {}", IntegrationManager.summary());
    }

    private void onServerStarted(MinecraftServer server) {
        eventQueue.start(server);
        ItemRegistryReporter.reportAsync(server);
        RconConfigReporter.reportAsync(server);
        LOGGER.info("Lattice started on server.");
    }

    public static LatticeConfig getConfig() {
        return config;
    }

    public static EventQueue getEventQueue() {
        return eventQueue;
    }

    public static MonitorScheduler getScheduler() {
        return scheduler;
    }

    public static boolean isModLoaded(String id) {
        return FabricLoader.getInstance().isModLoaded(id);
    }
}
