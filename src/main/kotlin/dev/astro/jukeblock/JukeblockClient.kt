package dev.astro.jukeblock

import dev.astro.jukeblock.media.MediaCommand
import dev.astro.jukeblock.media.MediaService
import dev.astro.jukeblock.ui.JukeblockKeys
import dev.astro.jukeblock.ui.PlayerScreen
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents

class JukeblockClient : ClientModInitializer {
	override fun onInitializeClient() {
		MediaService.start(Jukeblock.nativeDir)
		JukeblockKeys.register()

		ClientTickEvents.END_CLIENT_TICK.register { client ->
			while (JukeblockKeys.toggle.consumeClick()) {
				// Only from in-world: opening the rail on top of another screen would
				// fight it for input, and the panel is meant to be a glance, not a mode.
				if (client.gui.screen() == null) {
					client.gui.setScreen(PlayerScreen())
				}
			}

			// Global transport, no panel needed.
			while (JukeblockKeys.playPause.consumeClick()) MediaService.send(MediaCommand.Toggle)
			while (JukeblockKeys.next.consumeClick()) MediaService.send(MediaCommand.Next)
			while (JukeblockKeys.previous.consumeClick()) MediaService.send(MediaCommand.Previous)
		}

		if (MediaService.isAvailable) {
			Jukeblock.LOGGER.info("Jukeblock ready — media source: {}", MediaService.sourceName)
		} else {
			Jukeblock.LOGGER.info("Jukeblock ready — no system media source on this platform.")
		}
	}
}
