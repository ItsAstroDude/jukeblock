package dev.astro.jukeblock

import net.fabricmc.loader.api.FabricLoader
import org.slf4j.LoggerFactory
import java.nio.file.Path

/**
 * Mod-wide constants and logging.
 *
 * Deliberately free of Minecraft imports: everything under [dev.astro.jukeblock.media]
 * depends on this, and keeping it game-agnostic lets the media layer be exercised from
 * a plain JVM (see the `smoke` source set) without booting Minecraft.
 */
object Jukeblock {
	const val MOD_ID = "jukeblock"

	val LOGGER = LoggerFactory.getLogger(MOD_ID)!!

	/** Where the native bridge gets unpacked. Mod-owned, so we can clean it up. */
	val nativeDir: Path
		get() = FabricLoader.getInstance().gameDir.resolve(".$MOD_ID")
}
