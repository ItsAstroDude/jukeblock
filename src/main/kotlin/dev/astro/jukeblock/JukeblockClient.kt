package dev.astro.jukeblock

import dev.astro.jukeblock.media.MediaService
import net.fabricmc.api.ClientModInitializer

class JukeblockClient : ClientModInitializer {
	override fun onInitializeClient() {
		MediaService.start(Jukeblock.nativeDir)

		if (MediaService.isAvailable) {
			Jukeblock.LOGGER.info("Jukeblock ready — media source: {}", MediaService.sourceName)
		} else {
			Jukeblock.LOGGER.info("Jukeblock ready — no system media source on this platform.")
		}
	}
}
