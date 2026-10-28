package ca.waltermiller.mcpapi.operation;

import ca.waltermiller.mcpapi.arealock.AreaLockException;
import ca.waltermiller.mcpapi.arealock.AreaLockService;
import ca.waltermiller.mcpapi.buildtask.Json;
import com.fasterxml.jackson.databind.JsonNode;
import io.javalin.Javalin;
import io.javalin.http.Context;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/** HTTP waiting is separate from the lifetime of a mutation. */
public final class OperationHttp {
    private static final String ENTRY = OperationHttp.class.getName();
    private static final String ATTACHED = ENTRY + ".attached";
    private record Current(OperationRegistry registry, OperationRegistry.Operation operation) {}
    private static final ThreadLocal<Current> CURRENT = new ThreadLocal<>();
    private OperationHttp() {}

    public static void install(Javalin app, OperationRegistry registry) {
        app.exception(OperationRegistry.Failure.class, (failure, ctx) -> ctx.status(failure.status).json(Map.of("error", failure.getMessage())));
        app.exception(IllegalArgumentException.class, (failure, ctx) -> ctx.status(400).json(Map.of("error", failure.getMessage())));
        app.exception(IllegalStateException.class, (failure, ctx) -> ctx.status(409).json(Map.of("error", failure.getMessage())));
        app.beforeMatched(ctx -> {
            CURRENT.remove();
            if (!mutation(ctx)) return;
            String key = ctx.header("Idempotency-Key");
            if (key != null && (key.isBlank() || key.length() > 256)) throw new IllegalArgumentException("Invalid Idempotency-Key");
            String fingerprint = fingerprint(ctx);
            var admission = registry.register(key == null ? null : digest(key), fingerprint);
            Current current = new Current(registry, admission.operation());
            ctx.attribute(ENTRY, current);
            ctx.header("X-Operation-Id", current.operation.id);
            if (!admission.created()) {
                ctx.attribute(ATTACHED, true);
                waitFor(ctx, current, 0);
                ctx.skipRemainingHandlers();
                return;
            }
            CURRENT.set(current);
        });
        app.after(ctx -> {
            try {
                Current current = ctx.attribute(ENTRY);
                if (current != null && !Boolean.TRUE.equals(ctx.attribute(ATTACHED)) && !current.operation.completion.isDone()) {
                    Object body;
                    try { body = Json.MAPPER.readTree(ctx.result()); }
                    catch (Exception ignored) { body = Map.of("error", "Request did not produce a JSON result"); }
                    registry.finish(current.operation, new OperationRegistry.Response(ctx.statusCode(), body));
                }
            } finally { CURRENT.remove(); }
        });
        app.get("/api/operations/{operation_id}", ctx -> {
            var operation = registry.get(ctx.pathParam("operation_id"));
            if (operation == null) ctx.status(404).json(Map.of("error", "Unknown or expired operation; outcome may be unknown"));
            else {
                String key = ctx.header("Idempotency-Key");
                ctx.json(operation.view(key != null && operation.ownedBy(digest(key))));
            }
        });
    }

    private static boolean mutation(Context ctx) {
        String path = ctx.path();
        if (ctx.method().name().equals("GET")) return false;
        if (path.startsWith("/api/world/blocks/")) return path.endsWith("/set") || path.endsWith("/fill");
        if (path.startsWith("/api/builds/")) return path.endsWith("/undo") || path.endsWith("/redo");
        if (path.equals("/api/world/structure/place") || path.equals("/api/world/terrain/smooth")) {
            if (ctx.isMultipart()) {
                if (Boolean.parseBoolean(ctx.formParam("dry_run"))) return false;
            } else {
                try { if (Json.MAPPER.readTree(ctx.body()).path("dry_run").asBoolean(false)) return false; }
                catch (Exception ignored) { /* endpoint validates the request */ }
            }
        }
        return path.startsWith("/api/world/prefabs/") || path.startsWith("/api/message/")
            || path.startsWith("/api/area-locks") || path.startsWith("/api/world/markers")
            || path.equals("/api/world/entities/spawn") || path.equals("/api/world/effects/rain-fire")
            || path.equals("/api/players/teleport") || path.equals("/api/world/structure/place")
            || path.equals("/api/world/terrain/smooth");
    }
    private static String fingerprint(Context ctx) throws Exception {
        String payload;
        if (ctx.isMultipart()) {
            var parts = new TreeMap<String, Object>();
            parts.put("form", new TreeMap<>(ctx.formParamMap()));
            var files = new TreeMap<String, Object>();
            for (var entry : ctx.uploadedFileMap().entrySet()) {
                var hashes = new ArrayList<String>();
                for (var file : entry.getValue()) {
                    try (var content = file.content()) {
                        hashes.add(file.filename() + ":" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content.readAllBytes())));
                    }
                }
                files.put(entry.getKey(), hashes);
            }
            parts.put("files", files);
            payload = Json.MAPPER.writeValueAsString(parts);
        } else {
            try { payload = ctx.body().isBlank() ? "" : canonical(Json.MAPPER.readTree(ctx.body())).toString(); }
            catch (com.fasterxml.jackson.core.JsonProcessingException invalid) { throw new IllegalArgumentException("Invalid JSON body"); }
        }
        return digest(ctx.method() + "\n" + ctx.path() + "\n" + payload + "\n" + ctx.header(AreaLockService.HEADER));
    }
    private static JsonNode canonical(JsonNode node) {
        if (node.isObject()) {
            var result = Json.MAPPER.createObjectNode();
            TreeSet<String> names = new TreeSet<>(); node.fieldNames().forEachRemaining(names::add);
            for (String name : names) result.set(name, canonical(node.get(name)));
            return result;
        }
        if (node.isArray()) { var result = Json.MAPPER.createArrayNode(); node.forEach(value -> result.add(canonical(value))); return result; }
        return node;
    }
    private static String digest(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException(e); }
    }
    public static boolean tracked(Context ctx) { return ctx.attribute(ENTRY) != null; }
    public static boolean active() { return CURRENT.get() != null; }
    public static Executor persistenceExecutor(Context ctx) {
        Current current = ctx.attribute(ENTRY);
        return current == null ? Runnable::run : current.registry.workers;
    }
    public static void dispatch(Executor executor, Runnable work, CompletableFuture<?> future) {
        Current current = CURRENT.get();
        OperationRegistry registry = current == null ? ca.waltermiller.mcpapi.APIServer.operations : current.registry;
        if (registry == null) executor.execute(work);
        else registry.dispatch(executor, work, future, current == null ? null : current.operation);
    }
    public static <T> void respond(Context ctx, CompletableFuture<T> future, int seconds,
                                   Function<T, OperationRegistry.Response> response) throws Exception {
        Current current = ctx.attribute(ENTRY);
        if (current == null) {
            var result = response.apply(future.get(seconds, TimeUnit.SECONDS));
            ctx.status(result.status()).json(result.body());
            return;
        }
        ctx.attribute(ATTACHED, true);
        future.whenComplete((value, failure) -> {
            OperationRegistry.Response result;
            try { result = failure == null ? response.apply(value) : failure(failure); }
            catch (Exception mappingFailure) { result = failure(mappingFailure); }
            current.registry.finish(current.operation, result);
        });
        waitFor(ctx, current, seconds);
    }
    private static void waitFor(Context ctx, Current current, int seconds) throws Exception {
        try {
            var result = current.operation.completion.get(seconds, TimeUnit.SECONDS);
            ctx.status(result.status()).json(result.body());
        } catch (TimeoutException timeout) {
            ctx.header("Location", "/api/operations/" + current.operation.id);
            ctx.status(202).json(Map.of("operation_id", current.operation.id, "status", current.operation.status(),
                "status_url", "/api/operations/" + current.operation.id));
        }
    }
    public static OperationRegistry.Response failure(Throwable failure) {
        while ((failure instanceof ExecutionException || failure instanceof CompletionException) && failure.getCause() != null) failure = failure.getCause();
        AreaLockException lock = AreaLockException.find(failure);
        if (lock != null) return new OperationRegistry.Response(409, lock.payload());
        int status = failure instanceof OperationRegistry.Failure typed ? typed.status
            : failure instanceof IllegalArgumentException ? 400 : failure instanceof IllegalStateException ? 409 : 500;
        return new OperationRegistry.Response(status, Map.of("error", Objects.toString(failure.getMessage(), "Operation failed")));
    }
    /** Runs multi-phase edits off the HTTP thread while retaining their dispatch context. */
    public static <T> CompletableFuture<T> background(Context ctx, Callable<T> work) {
        Current current = ctx.attribute(ENTRY);
        return CompletableFuture.supplyAsync(() -> {
            CURRENT.set(current);
            try { return work.call(); }
            catch (Exception failure) { throw new CompletionException(failure); }
            finally { CURRENT.remove(); }
        }, current.registry.workers);
    }
}
