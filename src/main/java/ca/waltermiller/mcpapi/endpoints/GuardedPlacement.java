package ca.waltermiller.mcpapi.endpoints;

import ca.waltermiller.mcpapi.arealock.AreaBounds;
import ca.waltermiller.mcpapi.arealock.AreaLockService;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Predicate;
import java.util.function.Supplier;

final class GuardedPlacement {
    private GuardedPlacement() {}

    static <T> void submit(Executor executor, AreaLockService locks, String world, String token,
                           Supplier<AreaBounds> bounds, Supplier<T> operation, Predicate<T> success,
                           CompletableFuture<T> future) {
        executor.execute(() -> {
            try {
                future.complete(locks.execute(world, bounds.get(), token, operation, success));
            } catch (Exception e) {
                future.completeExceptionally(e);
            }
        });
    }
}
