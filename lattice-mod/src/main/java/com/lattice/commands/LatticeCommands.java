package com.lattice.commands;

import com.lattice.Lattice;
import com.lattice.auth.OpCommandTokenManager;
import com.lattice.audit.AuditSnapshot;
import com.lattice.registry.ItemRegistryReporter;
import com.lattice.scheduler.MonitorScheduler;
import com.mojang.brigadier.arguments.StringArgumentType;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.arguments.EntityArgument;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;

import java.time.Instant;
import java.time.ZoneId;
import java.util.OptionalLong;

public final class LatticeCommands {
    private LatticeCommands() {
    }

    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
            dispatcher.register(
                Commands.literal("lattice")
                    .requires(source -> source.hasPermission(4))
                    .then(
                        Commands.literal("token")
                            .then(
                                Commands.literal("apply")
                                    .then(
                                        Commands.argument("token", StringArgumentType.word())
                                            .executes(
                                                ctx -> applyToken(
                                                    ctx.getSource(),
                                                    StringArgumentType.getString(ctx, "token")
                                                )
                                            )
                                    )
                            )
                            .then(Commands.literal("status").executes(ctx -> tokenStatus(ctx.getSource())))
                    )
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

    private static int applyToken(CommandSourceStack source, String token) {
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendFailure(Component.literal("Lattice: token apply is only available for players"));
            return 0;
        }
        OpCommandTokenManager manager = Lattice.getOpCommandTokenManager();
        if (manager == null) {
            source.sendFailure(Component.literal("Lattice: token manager not initialized"));
            return 0;
        }
        OpCommandTokenManager.TokenApplyResult result = manager.applyToken(player, token, Lattice.getConfig());
        if (!result.success()) {
            source.sendFailure(Component.literal("Lattice: " + result.message()));
            return 0;
        }
        source.sendSuccess(() -> Component.literal("Lattice: " + result.message()), false);
        return 1;
    }

    private static int tokenStatus(CommandSourceStack source) {
        OpCommandTokenManager manager = Lattice.getOpCommandTokenManager();
        if (manager == null) {
            source.sendFailure(Component.literal("Lattice: token manager not initialized"));
            return 0;
        }
        if (!(source.getEntity() instanceof ServerPlayer player)) {
            source.sendSuccess(
                () -> Component.literal("Lattice: token check bypassed for non-player source"),
                false
            );
            return 1;
        }
        if (!Lattice.getConfig().opCommandTokenRequired) {
            source.sendSuccess(() -> Component.literal("Lattice: token check disabled"), false);
            return 1;
        }
        OptionalLong expiry = manager.getGrantExpiry(player);
        if (expiry.isEmpty()) {
            source.sendFailure(Component.literal("Lattice: no active token, run /lattice token apply <token>"));
            return 0;
        }
        source.sendSuccess(
            () -> Component.literal(
                "Lattice: token active until " + Instant.ofEpochMilli(expiry.getAsLong()).atZone(ZoneId.systemDefault())
            ),
            false
        );
        return 1;
    }

    private static boolean ensureTokenAuthorized(CommandSourceStack source) {
        OpCommandTokenManager manager = Lattice.getOpCommandTokenManager();
        if (manager == null) {
            source.sendFailure(Component.literal("Lattice: token manager not initialized"));
            return false;
        }
        OpCommandTokenManager.AccessDecision decision = manager.checkAccess(source, Lattice.getConfig());
        if (!decision.allowed()) {
            source.sendFailure(Component.literal("Lattice: " + decision.reason()));
            return false;
        }
        return true;
    }

    private static int auditAll(CommandSourceStack source) {
        if (!ensureTokenAuthorized(source)) {
            return 0;
        }
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
        if (!ensureTokenAuthorized(source)) {
            return 0;
        }
        int sent = AuditSnapshot.enqueuePlayerSnapshot(player, Lattice.getConfig().scanItemFilter);
        source.sendSuccess(
            () -> Component.literal("Lattice: audit queued for " + player.getGameProfile().getName() + ", events=" + sent),
            true
        );
        return sent;
    }

    private static int scanNow(CommandSourceStack source) {
        if (!ensureTokenAuthorized(source)) {
            return 0;
        }
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
        if (!ensureTokenAuthorized(source)) {
            return 0;
        }
        ItemRegistryReporter.reportAsync(source.getServer());
        source.sendSuccess(() -> Component.literal("Lattice: registry upload scheduled"), true);
        return 1;
    }
}
