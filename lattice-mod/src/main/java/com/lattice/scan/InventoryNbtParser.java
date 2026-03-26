package com.lattice.scan;

import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.Tag;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class InventoryNbtParser {
    private static final Set<String> INVENTORY_LIST_KEYS = Set.of(
        "items",
        "inventory",
        "contents"
    );

    private InventoryNbtParser() {
    }

    static TargetParseResult parse(
        Tag root,
        Set<String> itemFilter,
        String source,
        String pathOrStorageId,
        int maxDepth
    ) {
        if (root == null) {
            return TargetParseResult.skip();
        }
        ParseState state = new ParseState(itemFilter, source, pathOrStorageId, maxDepth);
        try {
            state.collect(root, 0);
        } catch (Throwable error) {
            return TargetParseResult.failed(
                List.of(
                    new TargetParseError(
                        ScanErrorClass.TARGET_RECOVERABLE,
                        "NBT_PARSE_FAILED",
                        "NBT 解析异常",
                        source,
                        pathOrStorageId
                    )
                )
            );
        }

        if (state.depthTruncated) {
            return TargetParseResult.failed(state.errors);
        }
        if (state.counts.isEmpty()) {
            return TargetParseResult.skip();
        }
        return TargetParseResult.success(state.counts, state.errors);
    }

    private static final class ParseState {
        private final Map<String, Integer> counts = new HashMap<>();
        private final List<TargetParseError> errors = new ArrayList<>();
        private final Set<String> itemFilter;
        private final String source;
        private final String pathOrStorageId;
        private final int maxDepth;
        private boolean depthTruncated;

        private ParseState(Set<String> itemFilter, String source, String pathOrStorageId, int maxDepth) {
            this.itemFilter = itemFilter == null ? Set.of() : itemFilter;
            this.source = source == null ? "" : source;
            this.pathOrStorageId = pathOrStorageId == null ? "" : pathOrStorageId;
            this.maxDepth = Math.max(1, maxDepth);
        }

        private void collect(Tag tag, int depth) {
            if (tag == null) {
                return;
            }
            if (depth > maxDepth) {
                markDepthTruncated();
                return;
            }
            if (tag instanceof CompoundTag compound) {
                for (String key : compound.getAllKeys()) {
                    Tag child = compound.get(key);
                    if (child == null) {
                        continue;
                    }
                    String normalizedKey = key == null ? "" : key.toLowerCase(Locale.ROOT);
                    if (child instanceof ListTag list && INVENTORY_LIST_KEYS.contains(normalizedKey)) {
                        collectInventoryList(list, depth + 1);
                        continue;
                    }
                    collect(child, depth + 1);
                }
                return;
            }
            if (tag instanceof ListTag list) {
                for (int i = 0; i < list.size(); i++) {
                    collect(list.get(i), depth + 1);
                }
            }
        }

        private void collectInventoryList(ListTag list, int depth) {
            if (depth > maxDepth) {
                markDepthTruncated();
                return;
            }
            for (int i = 0; i < list.size(); i++) {
                Tag entry = list.get(i);
                if (entry instanceof CompoundTag stack) {
                    collectStackLikeCount(stack);
                    collect(stack, depth + 1);
                    continue;
                }
                collect(entry, depth + 1);
            }
        }

        private void collectStackLikeCount(CompoundTag stackTag) {
            String itemId = stackTag.getString("id");
            if (itemId == null || itemId.isBlank() || !itemId.contains(":")) {
                return;
            }

            int count = stackTag.getInt("Count");
            if (count <= 0) {
                count = stackTag.getInt("count");
            }
            if (count <= 0) {
                count = stackTag.getInt("amount");
            }
            if (count <= 0) {
                return;
            }

            String normalizedId = itemId.trim().toLowerCase(Locale.ROOT);
            if (!itemFilter.isEmpty() && !itemFilter.contains(normalizedId)) {
                return;
            }
            counts.merge(normalizedId, count, Integer::sum);
        }

        private void markDepthTruncated() {
            if (depthTruncated) {
                return;
            }
            depthTruncated = true;
            counts.clear();
            errors.add(
                new TargetParseError(
                    ScanErrorClass.TARGET_RECOVERABLE,
                    "NBT_DEPTH_TRUNCATED",
                    "检测到深层嵌套容器，当前目标已跳过",
                    source,
                    pathOrStorageId
                )
            );
        }
    }
}

enum ScanErrorClass {
    FATAL,
    TARGET_RECOVERABLE
}

record TargetParseError(
    ScanErrorClass errorClass,
    String code,
    String message,
    String source,
    String pathOrStorageId
) {
}

record TargetParseResult(
    Map<String, Integer> counts,
    boolean failed,
    boolean skipped,
    List<TargetParseError> errors
) {
    static TargetParseResult success(Map<String, Integer> counts, List<TargetParseError> errors) {
        return new TargetParseResult(
            Collections.unmodifiableMap(new LinkedHashMap<>(counts)),
            false,
            false,
            List.copyOf(errors)
        );
    }

    static TargetParseResult failed(List<TargetParseError> errors) {
        return new TargetParseResult(Map.of(), true, false, List.copyOf(errors));
    }

    static TargetParseResult skip() {
        return new TargetParseResult(Map.of(), false, true, List.of());
    }
}
