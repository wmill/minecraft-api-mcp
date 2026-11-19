package ca.waltermiller.mcpapi.access;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** A request that can't be checked; reported as HTTP 400 with a machine-readable code. */
public final class AccessException extends IllegalArgumentException {
    private final String code;
    private final List<Map<String, Integer>> missingChunks;

    public AccessException(String code, String message) { this(code, message, List.of()); }

    public AccessException(String code, String message, List<Map<String, Integer>> missingChunks) {
        super(message);
        this.code = code;
        this.missingChunks = List.copyOf(missingChunks);
    }

    public String code() { return code; }

    public Map<String, Object> payload() {
        var out = new LinkedHashMap<String, Object>();
        out.put("success", false);
        out.put("code", code);
        out.put("error", getMessage());
        if (!missingChunks.isEmpty()) out.put("missing_chunks", missingChunks);
        return out;
    }
}
