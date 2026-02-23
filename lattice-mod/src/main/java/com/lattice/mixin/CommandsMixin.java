package com.lattice.mixin;

import com.lattice.Lattice;
import com.lattice.auth.OpCommandTokenManager;
import com.lattice.config.LatticeConfig;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.ParseResults;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import java.util.Locale;

@Mixin(Commands.class)
public abstract class CommandsMixin {
    @Shadow @Final private CommandDispatcher<CommandSourceStack> dispatcher;

    @Inject(method = "performCommand", at = @At("HEAD"), cancellable = true)
    private void lattice$enforceOpCommandToken(
        ParseResults<CommandSourceStack> parseResults,
        String command,
        CallbackInfo ci
    ) {
        CommandSourceStack source = parseResults.getContext().getSource();
        LatticeConfig config = Lattice.getConfig();
        if (config == null || !config.opCommandTokenRequired) {
            return;
        }

        if (!(source.getEntity() instanceof ServerPlayer player)) {
            return;
        }

        if (lattice$isFtbAutomationInvocation()) {
            return;
        }

        if (lattice$isElevatedProxyPlayerSource(source, player)) {
            return;
        }

        String normalizedCommand = lattice$normalizeCommand(command);
        if (normalizedCommand.isEmpty()) {
            return;
        }
        if (lattice$isTokenBootstrapCommand(normalizedCommand)) {
            return;
        }
        if (!lattice$isPermissionSensitiveCommand(parseResults, source, normalizedCommand)) {
            return;
        }

        OpCommandTokenManager manager = Lattice.getOpCommandTokenManager();
        if (manager == null) {
            source.sendFailure(Component.literal("Lattice: token manager not initialized"));
            ci.cancel();
            return;
        }

        OpCommandTokenManager.AccessDecision decision = manager.checkAccess(source, config);
        if (decision.allowed()) {
            return;
        }

        source.sendFailure(Component.literal("Lattice: " + decision.reason()));
        ci.cancel();
    }

    @Unique
    private String lattice$normalizeCommand(String command) {
        if (command == null) {
            return "";
        }
        String normalized = command.trim();
        if (normalized.startsWith("/")) {
            normalized = normalized.substring(1).trim();
        }
        return normalized;
    }

    @Unique
    private boolean lattice$isTokenBootstrapCommand(String normalizedCommand) {
        String lower = normalizedCommand.toLowerCase(Locale.ROOT);
        String[] parts = lower.split("\\s+");
        return parts.length >= 2 && "lattice".equals(parts[0]) && "token".equals(parts[1]);
    }

    @Unique
    private boolean lattice$isPermissionSensitiveCommand(
        ParseResults<CommandSourceStack> normalParse,
        CommandSourceStack source,
        String command
    ) {
        if (normalParse.getContext().getNodes().isEmpty()) {
            return false;
        }
        ParseResults<CommandSourceStack> levelHighParse = dispatcher.parse(command, source.withPermission(4));
        int levelHighDepth = levelHighParse.getContext().getNodes().size();
        if (levelHighDepth == 0) {
            return false;
        }
        ParseResults<CommandSourceStack> levelOneParse = dispatcher.parse(command, source.withPermission(1));
        int levelOneDepth = levelOneParse.getContext().getNodes().size();
        return levelOneDepth < levelHighDepth;
    }

    @Unique
    private boolean lattice$isElevatedProxyPlayerSource(CommandSourceStack source, ServerPlayer player) {
        int playerPermission = source.getServer().getProfilePermissions(player.getGameProfile());
        boolean elevatedAtLevelTwo = source.hasPermission(2) && playerPermission < 2;
        boolean elevatedAtLevelFour = source.hasPermission(4) && playerPermission < 4;
        return elevatedAtLevelTwo || elevatedAtLevelFour;
    }

    @Unique
    private boolean lattice$isFtbAutomationInvocation() {
        StackTraceElement[] stack = Thread.currentThread().getStackTrace();
        for (StackTraceElement element : stack) {
            String className = element.getClassName();
            if (className.startsWith("dev.ftb.mods.")) {
                return true;
            }
            if (className.startsWith("com.feed_the_beast.")) {
                return true;
            }
        }
        return false;
    }
}
