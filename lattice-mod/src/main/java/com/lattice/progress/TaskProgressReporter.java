package com.lattice.progress;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.lattice.Lattice;
import com.lattice.config.LatticeConfig;
import com.lattice.http.BackendClient;
import org.slf4j.Logger;

import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

public final class TaskProgressReporter {
    private static final Logger LOGGER = Lattice.LOGGER;
    private static final Gson GSON = new GsonBuilder().create();
    private static final String TASK_PROGRESS_PATH = "/v2/ops/task-progress";

    private TaskProgressReporter() {
    }

    public static void report(LatticeConfig config, String task, boolean running, int total, int done) {
        report(config, task, running, total, done, null, null, null, null, null, null, null);
    }

    public static void report(
        LatticeConfig config,
        String task,
        boolean running,
        int total,
        int done,
        String reasonCode,
        String reasonMessage,
        SourceTotalsPayload targetsTotalBySource
    ) {
        report(
            config,
            task,
            running,
            total,
            done,
            reasonCode,
            reasonMessage,
            targetsTotalBySource,
            null,
            null,
            null,
            null
        );
    }

    public static void report(
        LatticeConfig config,
        String task,
        boolean running,
        int total,
        int done,
        String reasonCode,
        String reasonMessage,
        SourceTotalsPayload targetsTotalBySource,
        String phase,
        DoneBySourcePayload doneBySource,
        String traceId,
        Double throughputPerSec
    ) {
        String normalizedCode = reasonCode == null ? null : reasonCode.trim();
        String normalizedMessage = reasonMessage == null ? null : reasonMessage.trim();
        String state;
        if (running) {
            state = "RUNNING";
        } else if (normalizedCode != null && !normalizedCode.isEmpty()) {
            state = "FAILED";
        } else if (total > 0 && done >= total) {
            state = "SUCCEEDED";
        } else {
            state = "IDLE";
        }
        reportState(
            config,
            task,
            state,
            phase,
            total,
            done,
            normalizedCode,
            normalizedMessage,
            targetsTotalBySource,
            doneBySource,
            traceId,
            throughputPerSec
        );
    }

    public static void reportState(
        LatticeConfig config,
        String task,
        String state,
        String stage,
        int total,
        int done,
        String failureCode,
        String failureMessage,
        SourceTotalsPayload targetsTotalBySource,
        DoneBySourcePayload doneBySource,
        String traceId,
        Double throughputPerSec
    ) {
        if (config == null) {
            return;
        }
        SourceTotalsPayload sourceTotals = targetsTotalBySource == null
            ? new SourceTotalsPayload(0, 0, 0, 0)
            : targetsTotalBySource;
        DoneBySourcePayload donePayload = doneBySource == null
            ? new DoneBySourcePayload(0, 0, 0, 0)
            : doneBySource;
        TaskCountersPayload counters = new TaskCountersPayload(total, done, sourceTotals, donePayload);
        TaskFailurePayload failure = (failureCode != null && !failureCode.isEmpty() && failureMessage != null && !failureMessage.isEmpty())
            ? new TaskFailurePayload(failureCode, failureMessage)
            : null;
        TaskProgressPayload payload = new TaskProgressPayload(
            task,
            state == null || state.isBlank() ? "IDLE" : state.trim().toUpperCase(),
            stage == null || stage.isBlank() ? null : stage.trim().toUpperCase(),
            counters,
            failure,
            traceId,
            throughputPerSec
        );
        String body = GSON.toJson(payload);
        HttpRequest.Builder builder;
        try {
            builder = BackendClient.request(config, TASK_PROGRESS_PATH, Duration.ofSeconds(5))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body));
        } catch (Exception e) {
            LOGGER.debug("Task progress report skipped", e);
            return;
        }

        BackendClient.client()
            .sendAsync(builder.build(), HttpResponse.BodyHandlers.discarding())
            .exceptionally(err -> {
                LOGGER.debug("Task progress report failed", err);
                return null;
            });
    }

    private record TaskProgressPayload(
        String task,
        String state,
        String stage,
        TaskCountersPayload counters,
        TaskFailurePayload failure,
        String trace_id,
        Double throughput_per_sec
    ) {
    }

    private record TaskCountersPayload(
        int total,
        int done,
        SourceTotalsPayload targets_total_by_source,
        DoneBySourcePayload done_by_source
    ) {
    }

    private record TaskFailurePayload(
        String code,
        String message
    ) {
    }

    public record SourceTotalsPayload(
        int world_containers,
        int sb_offline,
        int rs2_offline,
        int online_runtime
    ) {
    }

    public record DoneBySourcePayload(
        int world_containers,
        int sb_offline,
        int rs2_offline,
        int online_runtime
    ) {
    }
}
