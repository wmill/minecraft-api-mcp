package ca.waltermiller.mcpapi.arealock;

import java.util.LinkedHashMap;
import java.util.Map;

public final class AreaLockException extends RuntimeException {
    private final Map<String, Object> payload;

    public AreaLockException(String code, String message, Map<String, Object> details) {
        super(message);
        var body = new LinkedHashMap<String, Object>();
        body.put("success", false);
        body.put("code", code);
        body.put("error", message);
        body.putAll(details);
        payload = Map.copyOf(body);
    }

    public Map<String, Object> payload() {
        return payload;
    }

    public static AreaLockException find(Throwable error) {
        for (Throwable current = error; current != null; current = current.getCause()) {
            if (current instanceof AreaLockException conflict) return conflict;
        }
        return null;
    }
}
