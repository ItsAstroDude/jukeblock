package dev.astro.jukeblock

import net.fabricmc.api.ClientModInitializer
import org.slf4j.LoggerFactory

object Jukeblock {
	const val MOD_ID = "jukeblock"
	val LOGGER = LoggerFactory.getLogger(MOD_ID)!!
}

class JukeblockClient : ClientModInitializer {
	override fun onInitializeClient() {
		Jukeblock.LOGGER.info("Jukeblock client initialized — spinning up.")
	}
}
