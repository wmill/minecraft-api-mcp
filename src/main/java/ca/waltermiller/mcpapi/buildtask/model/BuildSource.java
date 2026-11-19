package ca.waltermiller.mcpapi.buildtask.model;

import com.fasterxml.jackson.databind.JsonNode;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Where a placed structure came from, so it can be inspected and revised after the compiler's
 * artifact cache has evicted it. One per recorded NBT placement; absent for raw uploads that
 * sent no provenance.
 *
 * @param target the position the caller asked for, before any ground offset (y_offset) was applied
 */
public record BuildSource(UUID buildId, String kind, String artifactId, String schematicId, String source,
                          String entry, JsonNode props, JsonNode rootSize, Integer yOffset, JsonNode target,
                          JsonNode details, Instant createdAt) {

    public static final Set<String> KINDS = Set.of("starlark", "schematic", "upload");
    /** Larger than the Starlark service's default 256 KiB source cap. */
    public static final int MAX_SOURCE_BYTES = 1 << 20;
    private static final int MAX_JSON_CHARS = 64 * 1024;

    public BuildSource withBuildId(UUID id) {
        return new BuildSource(id, kind, artifactId, schematicId, source, entry, props, rootSize, yOffset, target,
            details, createdAt);
    }

    /**
     * Validates the {@code provenance} JSON object sent with a placement. Unknown keys are rejected
     * so a typo does not silently drop the field a later revision needs.
     */
    public static BuildSource fromJson(JsonNode node) {
        if (node == null || !node.isObject()) throw new IllegalArgumentException("provenance must be a JSON object");
        Set<String> allowed = Set.of("kind", "artifact_id", "schematic_id", "source", "entry", "props", "root_size",
            "y_offset", "target", "details");
        node.fieldNames().forEachRemaining(name -> {
            if (!allowed.contains(name)) throw new IllegalArgumentException("Unknown provenance field: " + name);
        });
        String kind = text(node, "kind", 32);
        if (kind == null || !KINDS.contains(kind)) {
            throw new IllegalArgumentException("provenance.kind must be one of " + KINDS);
        }
        String source = node.hasNonNull("source") ? node.get("source").asText() : null;
        if (node.hasNonNull("source") && !node.get("source").isTextual()) {
            throw new IllegalArgumentException("provenance.source must be a string");
        }
        if (source != null && source.getBytes(StandardCharsets.UTF_8).length > MAX_SOURCE_BYTES) {
            throw new IllegalArgumentException("provenance.source exceeds " + MAX_SOURCE_BYTES + " bytes");
        }
        JsonNode props = node.get("props");
        if (props != null && !props.isNull() && !props.isObject()) throw new IllegalArgumentException("provenance.props must be an object");
        JsonNode rootSize = node.get("root_size");
        if (rootSize != null && !rootSize.isNull()) {
            if (!rootSize.isArray() || rootSize.size() != 3 || !rootSize.get(0).isInt() || !rootSize.get(1).isInt()
                || !rootSize.get(2).isInt()) {
                throw new IllegalArgumentException("provenance.root_size must be three integers");
            }
        }
        JsonNode yOffset = node.get("y_offset");
        if (yOffset != null && !yOffset.isNull() && !yOffset.isInt()) throw new IllegalArgumentException("provenance.y_offset must be an integer");
        JsonNode target = node.get("target");
        if (target != null && !target.isNull()) {
            if (!target.isObject() || !target.path("x").isInt() || !target.path("y").isInt() || !target.path("z").isInt()) {
                throw new IllegalArgumentException("provenance.target must be {x, y, z} integers");
            }
        }
        JsonNode details = node.get("details");
        if (details != null && !details.isNull() && !details.isObject()) throw new IllegalArgumentException("provenance.details must be an object");
        for (String field : new String[] {"props", "details"}) {
            JsonNode value = node.get(field);
            if (value != null && value.toString().length() > MAX_JSON_CHARS) {
                throw new IllegalArgumentException("provenance." + field + " exceeds " + MAX_JSON_CHARS + " characters");
            }
        }
        return new BuildSource(null, kind, text(node, "artifact_id", 64), text(node, "schematic_id", 255), source,
            text(node, "entry", 255), nullIfMissing(props), nullIfMissing(rootSize),
            yOffset == null || yOffset.isNull() ? null : yOffset.intValue(), nullIfMissing(target),
            nullIfMissing(details), null);
    }

    private static JsonNode nullIfMissing(JsonNode node) {
        return node == null || node.isNull() ? null : node;
    }

    private static String text(JsonNode node, String field, int max) {
        JsonNode value = node.get(field);
        if (value == null || value.isNull()) return null;
        if (!value.isTextual()) throw new IllegalArgumentException("provenance." + field + " must be a string");
        String text = value.asText();
        if (text.length() > max) throw new IllegalArgumentException("provenance." + field + " exceeds " + max + " characters");
        return text;
    }

    /** JSON view; {@code includeSource} false omits the script text for list views. */
    public Map<String, Object> toMap(boolean includeSource) {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("kind", kind);
        if (artifactId != null) map.put("artifact_id", artifactId);
        if (schematicId != null) map.put("schematic_id", schematicId);
        if (entry != null) map.put("entry", entry);
        if (props != null) map.put("props", props);
        if (rootSize != null) map.put("root_size", rootSize);
        if (yOffset != null) map.put("y_offset", yOffset);
        if (target != null) map.put("target", target);
        if (details != null) map.put("details", details);
        if (includeSource && source != null) map.put("source", source);
        if (!includeSource) map.put("has_source", source != null);
        if (createdAt != null) map.put("created_at", createdAt.toString());
        return map;
    }
}
