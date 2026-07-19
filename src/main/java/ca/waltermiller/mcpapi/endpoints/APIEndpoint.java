package ca.waltermiller.mcpapi.endpoints;

import io.javalin.Javalin;
import io.javalin.http.Context;
import net.minecraft.server.MinecraftServer;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import java.util.function.Predicate;

public class APIEndpoint {
    protected Javalin app;
    protected MinecraftServer server;
    protected org.slf4j.Logger LOGGER;

    public APIEndpoint(Javalin app, MinecraftServer server, org.slf4j.Logger logger) {
        this.app = app;
        this.server = server;
        this.LOGGER = logger;
    }

    /**
     * Waits for an async operation result and writes the standard JSON response:
     * the success body on success, otherwise {"error": ...} with 400 for validation
    * failures and 500 for everything else (including timeouts).
     */
    protected <T> void respond(Context ctx, CompletableFuture<T> future, int timeoutSeconds,
                               String operationName,
                               Predicate<T> success,
                               Function<T, String> error,
                               Function<T, Object> body) {
        try {
            T result = future.get(timeoutSeconds, TimeUnit.SECONDS);
            if (success.test(result)) {
                ctx.json(body.apply(result));
            } else {
                String message = error.apply(result);
                ctx.status(isClientError(message) ? 400 : 500)
                    .json(Map.of("error", message != null ? message : "Unknown error"));
            }
        } catch (TimeoutException e) {
            ctx.status(500).json(Map.of("error", "Timeout waiting for " + operationName));
        } catch (Exception e) {
            ctx.status(500).json(Map.of("error", "Unexpected error: " + e.getMessage()));
        }
    }

    /**
     * Heuristic for mapping operation error messages to HTTP status: errors caused by
     * bad request input get 400, everything else 500.
     */
    protected static boolean isClientError(String error) {
        if (error == null) {
            return false;
        }
        return error.startsWith("Invalid")
            || error.startsWith("Area too large")
            || error.startsWith("Unknown world");
    }
}
