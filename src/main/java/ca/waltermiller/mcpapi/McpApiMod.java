package ca.waltermiller.mcpapi;

import ca.waltermiller.mcpapi.database.DatabaseManager;
import net.fabricmc.api.ModInitializer;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class McpApiMod implements ModInitializer {
	public static final String MOD_ID = "mcpapi";

	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			LOGGER.info("Starting web server...");
			APIServer.start(server, LOGGER);
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			LOGGER.info("Shutting down database...");
			DatabaseManager.getInstance().shutdown();
		});
	}
}
