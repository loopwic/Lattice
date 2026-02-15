package com.lattice.http;

import com.lattice.config.LatticeConfig;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.time.Duration;

public final class BackendClient {
    private static final HttpClient CLIENT = HttpClient.newBuilder()
        .connectTimeout(Duration.ofSeconds(5))
        .build();

    private BackendClient() {
    }

    public static HttpClient client() {
        return CLIENT;
    }

    public static HttpRequest.Builder request(
        LatticeConfig config,
        String pathWithQuery,
        Duration timeout
    ) {
        HttpRequest.Builder builder = HttpRequest.newBuilder()
            .uri(resolveUri(config.backendUrl, pathWithQuery))
            .timeout(timeout);
        String token = config.apiToken == null ? "" : config.apiToken.trim();
        if (!token.isEmpty()) {
            builder.header("Authorization", "Bearer " + token);
        }
        return builder;
    }

    private static URI resolveUri(String backendUrl, String pathWithQuery) {
        String base = backendUrl == null ? "" : backendUrl.trim();
        if (base.isEmpty()) {
            throw new IllegalArgumentException("backend_url is empty");
        }
        String path = pathWithQuery == null ? "" : pathWithQuery.trim();
        if (path.isEmpty()) {
            return URI.create(base);
        }

        boolean baseHasSlash = base.endsWith("/");
        boolean pathHasSlash = path.startsWith("/");
        if (baseHasSlash && pathHasSlash) {
            return URI.create(base.substring(0, base.length() - 1) + path);
        }
        if (!baseHasSlash && !pathHasSlash) {
            return URI.create(base + "/" + path);
        }
        return URI.create(base + path);
    }
}
