package com.lattice.registry;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;
import com.lattice.Lattice;
import com.lattice.config.LatticeConfig;
import com.lattice.http.BackendClient;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.server.packs.resources.ResourceManager;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import org.slf4j.Logger;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class ItemRegistryReporter {
    private static final Logger LOGGER = Lattice.LOGGER;
    private static final Gson GSON = new GsonBuilder().serializeNulls().create();
    private static final String ITEM_REGISTRY_PATH = "/v2/query/item-registry";

    private ItemRegistryReporter() {
    }

    public static void reportAsync(MinecraftServer server) {
        LatticeConfig config = Lattice.getConfig();
        if (!config.registryUploadEnabled) {
            return;
        }
        Thread thread = new Thread(() -> report(server, config), "lattice-registry");
        thread.setDaemon(true);
        thread.start();
    }

    public static void report(MinecraftServer server, LatticeConfig config) {
        try {
            Map<String, Map<String, String>> translations = loadTranslations(server, config.registryUploadLanguages);
            List<ItemEntry> items = new ArrayList<>();
            for (Item item : BuiltInRegistries.ITEM) {
                ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
                if (key == null) {
                    continue;
                }
                String descriptionId = item.getDescriptionId();
                Map<String, String> names = new HashMap<>();
                for (String lang : config.registryUploadLanguages) {
                    Map<String, String> map = translations.get(lang);
                    if (map == null) {
                        continue;
                    }
                    String localized = map.get(descriptionId);
                    if (localized != null && !localized.trim().isEmpty()) {
                        names.put(lang, localized);
                    }
                }
                String name = names.getOrDefault("zh_cn", names.get("en_us"));
                if (name == null) {
                    try {
                        ItemStack stack = new ItemStack(item);
                        name = stack.getHoverName().getString();
                    } catch (Exception ignored) {
                        // ignore
                    }
                }
                ItemEntry entry = new ItemEntry(
                    key.toString(),
                    name,
                    names.isEmpty() ? null : names,
                    key.getNamespace(),
                    key.getPath()
                );
                items.add(entry);
            }
            int chunkSize = config.registryUploadChunkSize > 0 ? config.registryUploadChunkSize : items.size();
            int total = items.size();
            int offset = 0;
            boolean first = true;
            while (offset < total) {
                int end = Math.min(total, offset + chunkSize);
                List<ItemEntry> chunk = items.subList(offset, end);
                RegistryPayload payload = new RegistryPayload(chunk);
                String body = GSON.toJson(payload);
                String mode = first ? "replace" : "append";

                HttpRequest.Builder builder = BackendClient.request(
                    config,
                    ITEM_REGISTRY_PATH + "?mode=" + mode,
                    Duration.ofSeconds(15)
                )
                    .header("Content-Type", "application/json")
                    .PUT(HttpRequest.BodyPublishers.ofString(body));

                HttpResponse<String> response = BackendClient.client().send(builder.build(), HttpResponse.BodyHandlers.ofString());
                if (response.statusCode() < 200 || response.statusCode() >= 300) {
                    LOGGER.warn("Item registry upload failed ({}-{}): {}", offset, end, response.statusCode());
                    return;
                }
                first = false;
                offset = end;
            }
            LOGGER.info("Item registry uploaded: {} items", items.size());
        } catch (Exception e) {
            LOGGER.warn("Item registry upload failed", e);
        }
    }

    private static Map<String, Map<String, String>> loadTranslations(MinecraftServer server, List<String> languages) {
        Map<String, Map<String, String>> result = new HashMap<>();
        ResourceManager resourceManager = resolveResourceManager(server);
        Set<String> namespaces = new HashSet<>();
        for (Item item : BuiltInRegistries.ITEM) {
            ResourceLocation key = BuiltInRegistries.ITEM.getKey(item);
            if (key != null) {
                namespaces.add(key.getNamespace());
            }
        }
        namespaces.add("minecraft");
        if (resourceManager == null) {
            return loadTranslationsFromClasspath(result, languages, namespaces);
        }
        for (String lang : languages) {
            Map<String, String> merged = new HashMap<>();
            try {
                Map<ResourceLocation, Resource> resources = resourceManager.listResources(
                    "lang",
                    location -> location.getPath().endsWith(lang + ".json")
                );
                for (Resource resource : resources.values()) {
                    try (InputStreamReader reader = new InputStreamReader(resource.open(), StandardCharsets.UTF_8)) {
                        Map<String, String> entries = GSON.fromJson(reader, new TypeToken<Map<String, String>>() {}.getType());
                        if (entries != null) {
                            merged.putAll(entries);
                        }
                    } catch (Exception ignored) {
                        // ignore
                    }
                }
            } catch (Exception e) {
                LOGGER.debug("Failed to load lang {}", lang, e);
            }
            if (!namespaces.isEmpty()) {
                mergeClasspathTranslations(merged, lang, namespaces);
            }
            if (!merged.isEmpty()) {
                result.put(lang, merged);
            }
        }
        return result;
    }

    private static Map<String, Map<String, String>> loadTranslationsFromClasspath(
        Map<String, Map<String, String>> result,
        List<String> languages,
        Set<String> namespaces
    ) {
        for (String lang : languages) {
            Map<String, String> merged = new HashMap<>();
            mergeClasspathTranslations(merged, lang, namespaces);
            if (!merged.isEmpty()) {
                result.put(lang, merged);
            }
        }
        return result;
    }

    private static void mergeClasspathTranslations(Map<String, String> merged, String lang, Set<String> namespaces) {
        ClassLoader loader = ItemRegistryReporter.class.getClassLoader();
        for (String namespace : namespaces) {
            String path = "assets/" + namespace + "/lang/" + lang + ".json";
            try (InputStream stream = loader.getResourceAsStream(path)) {
                if (stream == null) {
                    continue;
                }
                try (InputStreamReader reader = new InputStreamReader(stream, StandardCharsets.UTF_8)) {
                    Map<String, String> entries = GSON.fromJson(reader, new TypeToken<Map<String, String>>() {}.getType());
                    if (entries != null) {
                        for (Map.Entry<String, String> entry : entries.entrySet()) {
                            merged.putIfAbsent(entry.getKey(), entry.getValue());
                        }
                    }
                }
            } catch (Exception ignored) {
                // ignore
            }
        }
    }

    private static ResourceManager resolveResourceManager(MinecraftServer server) {
        try {
            java.lang.reflect.Method method = server.getClass().getMethod("getResourceManager");
            Object value = method.invoke(server);
            if (value instanceof ResourceManager manager) {
                return manager;
            }
        } catch (Exception ignored) {
            // fallback below
        }
        try {
            java.lang.reflect.Method method = server.getClass().getMethod("getServerResources");
            Object serverResources = method.invoke(server);
            if (serverResources != null) {
                java.lang.reflect.Method rmMethod = serverResources.getClass().getMethod("resourceManager");
                Object value = rmMethod.invoke(serverResources);
                if (value instanceof ResourceManager manager) {
                    return manager;
                }
            }
        } catch (Exception ignored) {
            // ignore
        }
        return null;
    }

    private record ItemEntry(String item_id, String name, Map<String, String> names, String namespace, String path) {
    }

    private record RegistryPayload(List<ItemEntry> items) {
    }
}
