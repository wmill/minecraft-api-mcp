package ca.waltermiller.mcpapi.buildtask;

import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Shared Jackson ObjectMapper for the build task system. ObjectMapper is thread-safe
 * after configuration, so one instance serves all repositories and services.
 */
public final class Json {
    public static final ObjectMapper MAPPER = new ObjectMapper();

    private Json() {
    }
}
