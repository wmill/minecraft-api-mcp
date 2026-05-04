package ca.waltermiller.mcpapi.preview;

import java.util.Locale;

public enum PreviewViewDirection {
    SOUTH,
    WEST,
    NORTH,
    EAST;

    public static PreviewViewDirection fromQueryParam(String raw) {
        if (raw == null || raw.isBlank()) {
            return SOUTH;
        }
        return valueOf(raw.trim().toUpperCase(Locale.ROOT));
    }
}
