package ca.waltermiller.mcpapi.operation;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

/** Process-local outcomes. Active operations are never evicted. */
public final class OperationRegistry implements AutoCloseable {
    public record Response(int status, Object body) {}
    public static final class Failure extends RuntimeException {
        public final int status;
        public Failure(int status, String message) { super(message); this.status = status; }
    }
    public static final class Operation {
        public final String id = UUID.randomUUID().toString();
        public final CompletableFuture<Response> completion = new CompletableFuture<>();
        private String status = "queued";
        private Instant finished;
        private final String fingerprint;
        private final String key;
        private Operation(String key, String fingerprint) { this.key = key; this.fingerprint = fingerprint; }
        public synchronized String status() { return status; }
        public synchronized boolean start() {
            if (completion.isDone()) return false;
            status = "running";
            return true;
        }
        private synchronized void finish(Response response, Instant now) {
            if (completion.isDone()) return;
            status = response.status < 400 ? "succeeded" : "failed";
            finished = now;
            completion.complete(response);
        }
        private synchronized void cancel(Instant now) {
            if (!status.equals("queued")) return;
            status = "cancelled";
            finished = now;
            completion.complete(new Response(503, Map.of("error", "Operation cancelled during shutdown")));
        }
        public synchronized Map<String, Object> view() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("operation_id", id);
            result.put("status", status);
            result.put("status_url", "/api/operations/" + id);
            if (completion.isDone()) {
                Response response = completion.join();
                result.put("result_status", response.status);
                result.put("result", publicResult(response.body));
            }
            return result;
        }
        private static Object publicResult(Object body) {
            var node = ca.waltermiller.mcpapi.buildtask.Json.MAPPER.valueToTree(body);
            redact(node);
            return node;
        }
        private static void redact(com.fasterxml.jackson.databind.JsonNode node) {
            if (node.isObject()) {
                var object = (com.fasterxml.jackson.databind.node.ObjectNode) node;
                object.remove(List.of("lock_id", "lock_token", "idempotency_key"));
            }
            if (node.isContainerNode()) node.forEach(Operation::redact);
        }
    }
    public record Admission(Operation operation, boolean created) {}
    private final Map<String, Operation> operations = new HashMap<>();
    private final Map<String, Operation> keys = new HashMap<>();
    private final Clock clock;
    private final int capacity;
    private final Duration retention;
    private boolean accepting = true;
    private final Set<CompletableFuture<?>> queued = new HashSet<>();
    public final ExecutorService workers = Executors.newFixedThreadPool(4, runnable -> new Thread(runnable, "mcp-operations"));

    public OperationRegistry() { this(Clock.systemUTC(), 10_000, Duration.ofHours(1)); }
    public OperationRegistry(Clock clock, int capacity, Duration retention) {
        this.clock = clock; this.capacity = capacity; this.retention = retention;
    }
    private void expire() {
        Instant cutoff = clock.instant().minus(retention);
        operations.values().removeIf(operation -> {
            synchronized (operation) {
                if (operation.finished == null || operation.finished.isAfter(cutoff)) return false;
                if (operation.key != null) keys.remove(operation.key, operation);
                return true;
            }
        });
    }
    public synchronized Admission register(String key, String fingerprint) {
        expire();
        if (!accepting) throw new Failure(503, "Server is stopping");
        Operation existing = key == null ? null : keys.get(key);
        if (existing != null) {
            if (!existing.fingerprint.equals(fingerprint)) throw new Failure(409, "Idempotency key conflicts with an earlier request");
            return new Admission(existing, false);
        }
        if (operations.size() >= capacity) throw new Failure(503, "Operation registry is full");
        Operation operation = new Operation(key, fingerprint);
        operations.put(operation.id, operation);
        if (key != null) keys.put(key, operation);
        return new Admission(operation, true);
    }
    public synchronized Operation get(String id) { expire(); return operations.get(id); }
    public void finish(Operation operation, Response response) { operation.finish(response, clock.instant()); }

    /** Atomically close the gate against callbacks which have not started. */
    public void dispatch(Executor executor, Runnable work, CompletableFuture<?> future, Operation operation) {
        synchronized (this) {
            if (!accepting) { future.completeExceptionally(new Failure(503, "Server is stopping")); return; }
            queued.add(future);
        }
        try {
            executor.execute(() -> {
                synchronized (this) {
                    if (!queued.remove(future)) return;
                    if (operation != null && !operation.start()) return;
                }
                work.run();
            });
        } catch (RuntimeException failure) {
            synchronized (this) { queued.remove(future); }
            future.completeExceptionally(failure);
        }
    }
    public synchronized void closeAdmission() {
        accepting = false;
        for (Operation operation : operations.values()) operation.cancel(clock.instant());
        for (CompletableFuture<?> future : queued) future.completeExceptionally(new Failure(503, "Server is stopping"));
        queued.clear();
    }
    @Override public void close() { closeAdmission(); workers.shutdown(); }
}
