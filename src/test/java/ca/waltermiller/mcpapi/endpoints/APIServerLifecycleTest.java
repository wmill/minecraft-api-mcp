package ca.waltermiller.mcpapi.endpoints;

import ca.waltermiller.mcpapi.APIServer;
import net.minecraft.server.MinecraftServer;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import java.net.ServerSocket;
import java.util.concurrent.TimeUnit;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.mock;

class APIServerLifecycleTest {
    @Test void failedStartupAndRepeatedStopReleaseAllOwnedReferences() throws Exception {
        try (ServerSocket occupied = new ServerSocket(0)) {
            System.setProperty("api.port", Integer.toString(occupied.getLocalPort()));
            try {
                assertThatThrownBy(() -> APIServer.start(mock(MinecraftServer.class), LoggerFactory.getLogger(getClass())))
                    .isInstanceOf(RuntimeException.class);
                APIServer.stop();
                APIServer.stopped().get(12, TimeUnit.SECONDS);
                assertThat(APIServer.app).isNull();
                assertThat(APIServer.operations).isNull();
                assertThat(APIServer.minecraftServer).isNull();
                assertThat(APIServer.logger).isNull();
                System.setProperty("api.port", "0");
                APIServer.start(mock(MinecraftServer.class), LoggerFactory.getLogger(getClass()));
                int port = APIServer.app.port();
                APIServer.stop();
                APIServer.stop();
                APIServer.stopped().get(12, TimeUnit.SECONDS);
                try (ServerSocket reusable = new ServerSocket(port)) { assertThat(reusable.isBound()).isTrue(); }
            } finally { System.clearProperty("api.port"); APIServer.stop(); APIServer.stopped().get(12, TimeUnit.SECONDS); }
        }
    }
}
