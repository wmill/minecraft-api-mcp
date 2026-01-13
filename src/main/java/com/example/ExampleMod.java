package com.example;

import com.example.database.DatabaseManager;
import net.fabricmc.api.ModInitializer;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.sql.SQLException;

public class ExampleMod implements ModInitializer {
	public static final String MOD_ID = "modid";

	// This logger is used to write text to the console and the log file.
	// It is considered best practice to use your mod id as the logger's name.
	// That way, it's clear which mod wrote info, warnings, and errors.
	public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

	@Override
	public void onInitialize() {
		// This code runs as soon as Minecraft is in a mod-load-ready state.
		// However, some things (like resources) may still be uninitialized.
		// Proceed with mild caution.

		LOGGER.info("Hello Fabric world!");

		ServerLifecycleEvents.SERVER_STARTED.register(server -> {
			LOGGER.info("Initializing database...");
			try {
				DatabaseManager.getInstance().initialize();
				LOGGER.info("Database initialized successfully");
			} catch (SQLException e) {
				LOGGER.error("Failed to initialize database", e);
				// Continue startup even if database fails - allows for graceful degradation
			}
			
			LOGGER.info("Starting web server...");
			APIServer.start(server, LOGGER);
			LOGGER.info("Web server started on port 7070");
		});

		ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
			LOGGER.info("Shutting down database...");
			DatabaseManager.getInstance().shutdown();
		});
	}
}