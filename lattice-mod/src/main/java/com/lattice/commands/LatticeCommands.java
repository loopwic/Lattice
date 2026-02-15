package com.lattice.commands;

import com.lattice.Lattice;
import com.lattice.audit.AuditSnapshot;
import com.lattice.registry.ItemRegistryReporter;
import com.lattice.scheduler.MonitorScheduler;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

public final class LatticeCommands {
    private LatticeCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(
                Commands.literal("lattice")
                    .requires(source -> source.hasPermission(2))
                    .then(
                        Commands.literal("audit")
                            .executes(ctx -> auditPlayer(ctx.getSource(), ctx.getSource().getPlayerOrException()))
                            .then(Commands.literal("all").executes(ctx -> auditAll(ctx.getSource())))
                            .then(
                                Commands.argument("player", EntityArgument.player())
                                    .executes(ctx -> auditPlayer(ctx.getSource(), EntityArgument.getPlayer(ctx, "player")))
                            )
                    )
                    .then(
                        Commands.literal("scan")
                            .executes(ctx -> scanNow(ctx.getSource()))
                    )
                    .then(
                        Commands.literal("registry")
                            .executes(ctx -> uploadRegistry(ctx.getSource()))
                    )
            );
        });
    }

    private static int auditAll(CommandSourceStack source) {
        MonitorScheduler scheduler = Lattice.getScheduler();
        if (scheduler == null) {
            source.sendFailure(Component.literal("Lattice: scheduler not initialized"));
            return 0;
        }
        boolean accepted = scheduler.requestAuditNow();
        if (!accepted) {
            MonitorScheduler.TaskProgressSnapshot progress = scheduler.getAuditProgress();
            source.sendFailure(Component.literal(
                "Lattice: audit already running (" + progress.done + "/" + progress.total + ")"
            ));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Lattice: audit scheduled for all players"), true);
        return 1;
    }

    private static int auditPlayer(CommandSourceStack source, ServerPlayer player) {
        int sent = AuditSnapshot.enqueuePlayerSnapshot(player, Lattice.getConfig().scanItemFilter);
        source.sendSuccess(
            () -> Component.literal("Lattice: audit queued for " + player.getGameProfile().getName() + ", events=" + sent),
            true
        );
        return sent;
    }

    private static int scanNow(CommandSourceStack source) {
        MonitorScheduler scheduler = Lattice.getScheduler();
        if (scheduler == null) {
            source.sendFailure(Component.literal("Lattice: scanner not initialized"));
            return 0;
        }
        boolean accepted = scheduler.requestScanNow();
        if (!accepted) {
            MonitorScheduler.TaskProgressSnapshot progress = scheduler.getScanProgress();
            source.sendFailure(Component.literal(
                "Lattice: scan already running (" + progress.done + "/" + progress.total + ")"
            ));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Lattice: storage scan scheduled"), true);
        return 1;
    }

    private static int uploadRegistry(CommandSourceStack source) {
        ItemRegistryReporter.reportAsync(source.getServer());
        source.sendSuccess(() -> Component.literal("Lattice: registry upload scheduled"), true);
        return 1;
    }
}
