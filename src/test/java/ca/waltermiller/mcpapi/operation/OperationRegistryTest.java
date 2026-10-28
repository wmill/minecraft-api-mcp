package ca.waltermiller.mcpapi.operation;

import org.junit.jupiter.api.Test;
import java.time.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.assertj.core.api.Assertions.*;

class OperationRegistryTest {
    @Test void concurrentKeysAreAtomicAndConflictsAreRejected() throws Exception {
        try (var registry = new OperationRegistry(); var threads = Executors.newFixedThreadPool(8)) {
            var jobs = new ArrayList<Future<OperationRegistry.Admission>>();
            for (int i = 0; i < 32; i++) jobs.add(threads.submit(() -> registry.register("key", "payload")));
            int created = 0;
            var ids = new HashSet<String>();
            for (var job : jobs) { var admission = job.get(); if (admission.created()) created++; ids.add(admission.operation().id); }
            assertThat(created).isEqualTo(1);
            assertThat(ids).hasSize(1);
            assertThatThrownBy(() -> registry.register("key", "other")).isInstanceOf(OperationRegistry.Failure.class)
                .satisfies(error -> assertThat(((OperationRegistry.Failure) error).status).isEqualTo(409));
        }
    }
    @Test void shutdownCancelsQueuedCallbacksAndRetainsRunningOutcomes() {
        try (var registry = new OperationRegistry()) {
            var callbacks = new ArrayList<Runnable>();
            var queued = registry.register(null, "queued").operation();
            var running = registry.register(null, "running").operation();
            running.start();
            AtomicInteger writes = new AtomicInteger();
            var future = new CompletableFuture<>();
            registry.dispatch(callbacks::add, writes::incrementAndGet, future, queued);
            registry.closeAdmission();
            callbacks.forEach(Runnable::run);
            assertThat(writes).hasValue(0);
            assertThat(future).isCompletedExceptionally();
            assertThat(queued.status()).isEqualTo("cancelled");
            assertThat(running.status()).isEqualTo("running");
            registry.finish(running, new OperationRegistry.Response(409, Map.of("error", "conflict")));
            assertThat(running.view()).containsEntry("result_status", 409).containsEntry("status", "failed");
        }
    }
    @Test void expiryAndCapacityNeverEvictActiveRecordsOrExposeTokens() {
        class MutableClock extends Clock {
            Instant now = Instant.parse("2026-01-01T00:00:00Z");
            public ZoneId getZone() { return ZoneOffset.UTC; }
            public Clock withZone(ZoneId zone) { return this; }
            public Instant instant() { return now; }
        }
        var clock = new MutableClock();
        try (var registry = new OperationRegistry(clock, 2, Duration.ofHours(1))) {
            var active = registry.register(null, "active").operation();
            var done = registry.register("secret-key", "secret-payload").operation();
            registry.finish(done, new OperationRegistry.Response(200, Map.of("success", true, "lock_id", "secret-token")));
            assertThat(done.view().toString()).doesNotContain("secret");
            assertThatThrownBy(() -> registry.register(null, "full")).isInstanceOf(OperationRegistry.Failure.class);
            clock.now = clock.now.plus(Duration.ofHours(1));
            assertThat(registry.get(done.id)).isNull();
            assertThat(registry.get(active.id)).isSameAs(active);
            assertThat(registry.register("secret-key", "new-payload").created()).isTrue();
        }
    }
}
