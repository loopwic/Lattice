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
        report(config, task, running, total, done, null, null, null);
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
        if (config == null) {
            return;
        }
        TaskProgressPayload payload = new TaskProgressPayload(
            task,
            running,
            total,
            done,
            reasonCode,
            reasonMessage,
            targetsTotalBySource
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
        boolean running,
        int total,
        int done,
        String reason_code,
        String reason_message,
        SourceTotalsPayload targets_total_by_source
    ) {
    }

    public record SourceTotalsPayload(
        int world_containers,
        int sb_offline,
        int rs2_offline,
        int online_runtime
    ) {
    }
}
