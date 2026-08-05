package dev.astro.jukeblock

import com.mojang.brigadier.CommandDispatcher
import dev.astro.jukeblock.media.MediaCommand
import dev.astro.jukeblock.media.MediaService
import dev.astro.jukeblock.ui.JukeblockKeys
import dev.astro.jukeblock.ui.NowPlayingHud
import dev.astro.jukeblock.ui.PlayerScreen
import net.fabricmc.api.ClientModInitializer
import net.fabricmc.fabric.api.client.command.v2.ClientCommandRegistrationCallback
import net.fabricmc.fabric.api.client.command.v2.ClientCommands
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents
import net.minecraft.network.chat.Component

class JukeblockClient : ClientModInitializer {
	override fun onInitializeClient() {
		JukeblockConfig.load()
		MediaService.start(Jukeblock.nativeDir)
		JukeblockKeys.register()
		NowPlayingHud.register()
		registerCommands()

		ClientTickEvents.END_CLIENT_TICK.register { client ->
			// Ticked rather than watched from the renderer, so a track change is noticed
			// even while the HUD is hidden — otherwise the first frame after it becomes
			// visible would look like the change and toast at the wrong moment.
			NowPlayingHud.tick()

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

	/**
	 * `/np` — share what's playing.
	 *
	 * A client command, so it works on any server without one installed. Bare `/np`
	 * prints to your own chat; `/np share` actually says it out loud, because sending
	 * to a public channel shouldn't be the default a typo lands you in.
	 */
	private fun registerCommands() {
		ClientCommandRegistrationCallback.EVENT.register { dispatcher: CommandDispatcher<FabricClientCommandSource>, _ ->
			dispatcher.register(
				ClientCommands.literal("np")
					.executes { ctx ->
						val text = describeNowPlaying()
						ctx.source.sendFeedback(Component.literal(text))
						1
					}
					.then(
						ClientCommands.literal("share").executes { ctx ->
							val track = MediaService.nowPlaying
							if (track == null) {
								ctx.source.sendFeedback(Component.translatable("jukeblock.np.nothing"))
							} else {
								ctx.source.player.connection.sendChat(describeNowPlaying())
							}
							1
						},
					),
			)
		}
	}

	private fun describeNowPlaying(): String {
		val track = MediaService.nowPlaying ?: return "♪ nothing playing"
		val artist = if (track.artist.isNotEmpty()) " — ${track.artist}" else ""
		return "♪ ${track.title}$artist"
	}
}
