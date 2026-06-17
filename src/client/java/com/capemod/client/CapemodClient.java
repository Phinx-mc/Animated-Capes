package com.capemod.client;

import com.capemod.client.cape.GifCapeTexture;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Files;
import java.nio.file.Path;

public class CapemodClient implements ClientModInitializer {

	private static final Logger LOGGER = LoggerFactory.getLogger("gifcape");

	/** Animated cape: <gameDir>/config/capemod/cape.gif */
	private static final String CAPE_GIF_PATH = "config/capemod/cape.gif";

	@Override
	public void onInitializeClient() {
		ClientLifecycleEvents.CLIENT_STARTED.register(client -> {
			Path configDir = client.runDirectory.toPath().resolve("config/capemod");
			Path capePath  = configDir.resolve("cape.gif");

			try { Files.createDirectories(configDir); } catch (Exception ignored) {}

			if (!Files.exists(capePath)) {
				LOGGER.warn("[GifCape] No cape.gif found at {}. Drop a .gif there to use a custom cape!",
						capePath.toAbsolutePath());
			} else {
				LOGGER.info("[GifCape] Loading cape GIF from {}", capePath.toAbsolutePath());
				GifCapeTexture.loadCape(capePath);
			}
		});

		ClientLifecycleEvents.CLIENT_STOPPING.register(client -> {
			GifCapeTexture cape = GifCapeTexture.getCapeInstance();
			if (cape != null) cape.close();
		});
	}
}
