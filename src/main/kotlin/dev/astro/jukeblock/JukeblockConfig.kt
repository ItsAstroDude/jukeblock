package dev.astro.jukeblock

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path

/**
 * User settings, persisted as `config/jukeblock.json`.
 *
 * Deliberately plain data with hex colour strings: the file is meant to be hand-edited
 * until the Cloth Config screen lands, and `"#1E1E26"` is legible where `1973790` isn't.
 */
data class JukeblockConfig(
	/** Rail background. Alpha comes from [panelOpacity], not from this string. */
	var panelColor: String = "#2A2A33",

	/** 0 = invisible, 100 = solid. */
	var panelOpacity: Int = 93,

	/** How much the world behind the rail is dimmed, 0-100. */
	var backdropDim: Int = 15,

	/** Rail width in GUI units — not pixels. See PlayerScreen.RAIL_UNITS. */
	var railWidth: Int = 180,

	/** Take the accent from the album art. When false, [accentColor] is used as-is. */
	var accentFromArt: Boolean = true,

	var accentColor: String = "#53E076",

	/**
	 * Keep the accent legible against [panelColor].
	 *
	 * Worth leaving on: pick a red panel while a red album is playing and the progress
	 * fill would otherwise sit at almost the same colour as the surface behind it.
	 */
	var adaptAccentToPanel: Boolean = true,

	/** Smooth the album art instead of Minecraft's blocky nearest-neighbour upscale. */
	var smoothAlbumArt: Boolean = true,
) {
	val panelRgb: Int get() = parseHex(panelColor, 0x2A2A33)
	val accentRgb: Int get() = parseHex(accentColor, 0x53E076)

	/** Panel colour with the configured opacity baked into the alpha channel. */
	val panelArgb: Int
		get() = (panelOpacity.coerceIn(0, 100) * 255 / 100 shl 24) or (panelRgb and 0xFFFFFF)

	fun sanitised(): JukeblockConfig = copy(
		panelOpacity = panelOpacity.coerceIn(0, 100),
		backdropDim = backdropDim.coerceIn(0, 100),
		// A rail below ~120 units can't fit the transport row; above ~320 it's a wall.
		railWidth = railWidth.coerceIn(120, 320),
	)

	companion object {
		private val GSON = GsonBuilder().setPrettyPrinting().create()

		@Volatile
		var current: JukeblockConfig = JukeblockConfig()
			private set

		private val path: Path
			get() = FabricLoader.getInstance().configDir.resolve("${Jukeblock.MOD_ID}.json")

		fun load() {
			current = try {
				if (Files.exists(path)) {
					val text = Files.readString(path)
					// Parsed leniently: a hand-edited file with one bad field should fall
					// back to defaults for that field, not wipe the user's whole config.
					val parsed = GSON.fromJson(JsonParser.parseString(text), JukeblockConfig::class.java)
					(parsed ?: JukeblockConfig()).sanitised()
				} else {
					JukeblockConfig().also { save(it) }
				}
			} catch (e: Exception) {
				Jukeblock.LOGGER.warn("Could not read {}; using defaults.", path, e)
				JukeblockConfig()
			}
		}

		fun save(config: JukeblockConfig = current) {
			current = config.sanitised()
			try {
				Files.createDirectories(path.parent)
				Files.writeString(path, GSON.toJson(current))
			} catch (e: Exception) {
				Jukeblock.LOGGER.warn("Could not write {}", path, e)
			}
		}

		/** Back to the hex form the file stores, so a GUI edit stays hand-editable. */
		fun toHex(rgb: Int): String = "#%06X".format(rgb and 0xFFFFFF)

		/** Accepts `#RRGGBB`, `RRGGBB`, and `0xRRGGBB`. */
		private fun parseHex(raw: String, fallback: Int): Int {
			val cleaned = raw.trim().removePrefix("#").removePrefix("0x").removePrefix("0X")
			return cleaned.toIntOrNull(16)?.and(0xFFFFFF) ?: fallback
		}
	}
}
