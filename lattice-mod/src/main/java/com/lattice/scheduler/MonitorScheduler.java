package com.lattice.scheduler;

import com.lattice.audit.AuditSnapshot;
import com.lattice.config.LatticeConfig;
import com.lattice.progress.TaskProgressReporter;
import com.lattice.scan.StorageScanner;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerPlayer;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Set;
import java.util.UUID;

public final class MonitorScheduler {
    private final LatticeConfig config;
    private final StorageScanner storageScanner;
    private final Deque<UUID> auditQueue = new ArrayDeque<>();
    private final Set<String> itemFilter;

    private long nextAuditAtMs;
    private boolean auditRunning;
    private boolean auditForceRequested;
    private int auditTotal;
    private int auditDone;
    private long auditLastReportMs;
    private int auditLastReportedDone;

    public MonitorScheduler(LatticeConfig config) {
        this.config = config;
        this.storageScanner = new StorageScanner(config);
        this.itemFilter = config.scanItemFilter;
    }

    public boolean requestAuditNow() {
        if (auditRunning || auditForceRequested) {
            return false;
        }
        auditForceRequested = true;
        nextAuditAtMs = 0;
        return true;
    }

    public boolean requestScanNow() {
        return storageScanner.requestScanNow();
    }

    public void tick(MinecraftServer server) {
        long now = System.currentTimeMillis();
        boolean shouldStartManual = auditForceRequested && !auditRunning;
        boolean shouldStartScheduled = !auditRunning
            && !auditForceRequested
            && config.auditEnabled
            && config.auditIntervalMinutes > 0
            && (nextAuditAtMs == 0 || now >= nextAuditAtMs);
        if (shouldStartManual || shouldStartScheduled) {
            startAudit(server, now);
            auditForceRequested = false;
            if (config.auditEnabled && config.auditIntervalMinutes > 0) {
                nextAuditAtMs = now + (config.auditIntervalMinutes * 60_000L);
            }
        }

        if (auditRunning) {
            int budget = Math.max(1, config.auditPlayersPerTick);
            while (budget-- > 0 && !auditQueue.isEmpty()) {
                UUID id = auditQueue.pollFirst();
                if (id == null) {
                    break;
                }
                ServerPlayer player = server.getPlayerList().getPlayer(id);
                if (player == null) {
                    auditDone++;
                    continue;
                }
                AuditSnapshot.enqueuePlayerSnapshot(player, itemFilter);
                auditDone++;
            }
            reportAuditProgress(now, false);
            if (auditQueue.isEmpty()) {
                auditRunning = false;
                reportAuditProgress(now, true);
            }
        }

        storageScanner.tick(server, now);
    }

    public TaskProgressSnapshot getAuditProgress() {
        return new TaskProgressSnapshot(auditRunning, auditTotal, auditDone);
    }

    public TaskProgressSnapshot getScanProgress() {
        return storageScanner.getProgress();
    }

    private void startAudit(MinecraftServer server, long now) {
        auditQueue.clear();
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            auditQueue.addLast(player.getUUID());
        }
        auditTotal = auditQueue.size();
        auditDone = 0;
        auditRunning = auditTotal > 0;
        reportAuditProgress(now, true);
        if (!auditRunning) {
            auditForceRequested = false;
        }
    }

    private void reportAuditProgress(long now, boolean force) {
        if (!force) {
            if (now - auditLastReportMs < 2000 && auditDone - auditLastReportedDone < 10) {
                return;
            }
        }
        auditLastReportMs = now;
        auditLastReportedDone = auditDone;
        TaskProgressReporter.report(config, "audit", auditRunning, auditTotal, auditDone);
    }

    public static final class TaskProgressSnapshot {
        public final boolean running;
        public final int total;
        public final int done;

        public TaskProgressSnapshot(boolean running, int total, int done) {
            this.running = running;
            this.total = total;
            this.done = done;
        }
    }
}
