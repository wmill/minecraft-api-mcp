package ca.waltermiller.mcpapi.operation;

import ca.waltermiller.mcpapi.buildtask.Json;
import io.javalin.Javalin;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.net.http.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class OperationHttpTest {
    @Test void multipartFingerprintDoesNotConsumeUploadAndIgnoresBoundary() throws Exception {
        var app = Javalin.create();
        try (var registry = new OperationRegistry(); var client = HttpClient.newHttpClient()) {
            OperationHttp.install(app, registry);
            AtomicInteger writes = new AtomicInteger();
            app.post("/api/world/structure/place", ctx -> {
                String file;
                try (var content = ctx.uploadedFile("nbt_file").content()) {
                    file = new String(content.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
                }
                writes.incrementAndGet();
                ctx.json(Map.of("success", true, "file", file));
            });
            app.start(0);
            String base = "http://localhost:" + app.port();
            for (String boundary : List.of("first-boundary", "retry-boundary")) {
                String multipart = "--" + boundary + "\r\nContent-Disposition: form-data; name=\"x\"\r\n\r\n10\r\n--" + boundary
                    + "\r\nContent-Disposition: form-data; name=\"nbt_file\"; filename=\"test.nbt\"\r\nContent-Type: application/octet-stream\r\n\r\nnbt-content\r\n--" + boundary + "--\r\n";
                var response = client.send(HttpRequest.newBuilder(URI.create(base + "/api/world/structure/place"))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary).header("Idempotency-Key", "multipart-key")
                    .POST(HttpRequest.BodyPublishers.ofString(multipart)).build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
                assertThat(Json.MAPPER.readTree(response.body()).path("file").asText()).isEqualTo("nbt-content");
            }
            assertThat(writes).hasValue(1);
        } finally { app.stop(); }
    }

    record Fill(int x1, String block_type) {}

    @Test void unknownJsonFieldIsBadRequestAndRecordedAsSuch() throws Exception {
        var app = Javalin.create();
        try (var registry = new OperationRegistry(); var client = HttpClient.newHttpClient()) {
            OperationHttp.install(app, registry);
            app.post("/api/world/blocks/set", ctx -> ctx.json(ctx.bodyAsClass(Fill.class)));
            app.start(0);
            String base = "http://localhost:" + app.port();
            var response = post(client, base, "{\"x1\":1,\"blockType\":\"minecraft:stone\"}", "bad-json", "owner");
            assertThat(response.statusCode()).as(response.body()).isEqualTo(400);
            assertThat(Json.MAPPER.readTree(response.body()).path("error").asText())
                .startsWith("Invalid JSON body: ").contains("blockType").doesNotContain("line:");
            String id = response.headers().firstValue("X-Operation-Id").orElseThrow();
            var operation = client.send(HttpRequest.newBuilder(URI.create(base + "/api/operations/" + id)).build(),
                HttpResponse.BodyHandlers.ofString());
            assertThat(Json.MAPPER.readTree(operation.body()).path("result_status").asInt()).isEqualTo(400);
        } finally { app.stop(); }
    }

    @Test void delayedEditRetriesPollsAndPreservesFailurePayload() throws Exception {
        var app = Javalin.create();
        try (var registry = new OperationRegistry(); var client = HttpClient.newHttpClient()) {
            OperationHttp.install(app, registry);
            var callbacks = new LinkedBlockingQueue<Runnable>();
            AtomicInteger writes = new AtomicInteger();
            app.post("/api/world/blocks/set", ctx -> {
                var future = new CompletableFuture<OperationRegistry.Response>();
                OperationHttp.dispatch(callbacks::add, () -> {
                    writes.incrementAndGet();
                    future.complete(new OperationRegistry.Response(409, Map.of("code", "area_locked", "error", "reserved")));
                }, future);
                OperationHttp.respond(ctx, future, 0, result -> result);
            });
            app.start(0);
            String base = "http://localhost:" + app.port();
            var first = post(client, base, "{\"a\":1,\"b\":2}", "same", "owner");
            assertThat(first.statusCode()).isEqualTo(202);
            String id = Json.MAPPER.readTree(first.body()).path("operation_id").asText();
            assertThat(first.headers().firstValue("X-Operation-Id")).contains(id);
            assertThat(post(client, base, "{\"b\":2,\"a\":1}", "same", "owner").statusCode()).isEqualTo(202);
            assertThat(registry.get(id).status()).isEqualTo("queued");
            assertThat(post(client, base, "{\"a\":1,\"b\":2}", "same", "other-owner").statusCode()).isEqualTo(409);
            assertThat(callbacks).hasSize(1);
            callbacks.take().run();
            assertThat(writes).hasValue(1);
            var poll = client.send(HttpRequest.newBuilder(URI.create(base + "/api/operations/" + id)).GET().build(), HttpResponse.BodyHandlers.ofString());
            var body = Json.MAPPER.readTree(poll.body());
            assertThat(body.path("status").asText()).isEqualTo("failed");
            assertThat(body.path("result_status").asInt()).isEqualTo(409);
            assertThat(body.path("result").path("code").asText()).isEqualTo("area_locked");
            var retry = post(client, base, "{\"a\":1,\"b\":2}", "same", "owner");
            assertThat(retry.statusCode()).isEqualTo(409);
            assertThat(Json.MAPPER.readTree(retry.body())).isEqualTo(body.path("result"));
            assertThat(callbacks).isEmpty();
        } finally { app.stop(); }
    }
    @Test void delayedReservationTokenIsOnlyVisibleToKeyHolder() throws Exception {
        var app = Javalin.create();
        try (var registry = new OperationRegistry(); var client = HttpClient.newHttpClient()) {
            OperationHttp.install(app, registry);
            var callbacks = new LinkedBlockingQueue<Runnable>();
            app.post("/api/area-locks", ctx -> {
                var future = new CompletableFuture<OperationRegistry.Response>();
                OperationHttp.dispatch(callbacks::add,
                    () -> future.complete(new OperationRegistry.Response(200, Map.of("lock_id", "secret-token"))), future);
                OperationHttp.respond(ctx, future, 0, result -> result);
            });
            app.start(0);
            String base = "http://localhost:" + app.port();
            var accepted = client.send(HttpRequest.newBuilder(URI.create(base + "/api/area-locks"))
                .header("Content-Type", "application/json").header("Idempotency-Key", "owner-key")
                .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(accepted.statusCode()).isEqualTo(202);
            String id = Json.MAPPER.readTree(accepted.body()).path("operation_id").asText();
            callbacks.take().run();
            for (String key : new String[] {null, "wrong-key", "owner-key"}) {
                var request = HttpRequest.newBuilder(URI.create(base + "/api/operations/" + id)).GET();
                if (key != null) request.header("Idempotency-Key", key);
                var poll = client.send(request.build(), HttpResponse.BodyHandlers.ofString());
                var result = Json.MAPPER.readTree(poll.body()).path("result");
                if ("owner-key".equals(key)) assertThat(result.path("lock_id").asText()).isEqualTo("secret-token");
                else assertThat(result.has("lock_id")).as("key %s", key).isFalse();
            }
        } finally { app.stop(); }
    }
    private HttpResponse<String> post(HttpClient client, String base, String body, String key, String lock) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(base + "/api/world/blocks/set"))
            .header("Content-Type", "application/json").header("Idempotency-Key", key).header("X-Area-Lock-Id", lock)
            .POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}
