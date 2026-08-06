package dev.astro.jukeblock

import com.google.gson.GsonBuilder
import com.google.gson.JsonParser
import net.fabricmc.loader.api.FabricLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.extension
import kotlin.io.path.nameWithoutExtension

/**
 * A named bundle of the panel's visual settings.
 *
 * Themes are **presets, not a layer**: applying one copies its values into the live
 * config, and everything stays individually editable afterwards. A theme that overrode
 * settings at render time would mean two sources of truth for every colour and a
 * permanent question of which one won.
 */
data class Theme(
	val name: String = "Unnamed",
	val panelColor: String = "#2A2A33",
	val panelOpacity: Int = 93,
	val backdropDim: Int = 15,
	val accentFromArt: Boolean = true,
	val accentColor: String = "#53E076",
	val adaptAccentToPanel: Boolean = true,
) {
	/** Copies this theme's values into the live config and persists them. */
	fun apply() {
		val config = JukeblockConfig.current
		config.panelColor = panelColor
		config.panelOpacity = panelOpacity
		config.backdropDim = backdropDim
		config.accentFromArt = accentFromArt
		config.accentColor = accentColor
		config.adaptAccentToPanel = adaptAccentToPanel
		JukeblockConfig.save()
	}

	companion object {
		private val GSON = GsonBuilder().setPrettyPrinting().create()

		/**
		 * Shipped presets.
		 *
		 * "Paper" is deliberately light: it's the one that exercises the dark-on-light
		 * text path, so a regression there shows up by picking a theme rather than by
		 * someone hand-editing a hex value.
		 */
		val BUILT_IN = listOf(
			Theme("Liquid Lens", "#2A2A33", 93, 15, true, "#53E076", true),
			Theme("Midnight", "#12121A", 96, 25, true, "#7C6CFF", true),
			Theme("Paper", "#EDEDF2", 95, 10, true, "#2F6FED", true),
			Theme("Terminal", "#0E1410", 92, 20, false, "#35E06A", true),
			Theme("Sakura", "#2B1F26", 92, 18, true, "#FF70A5", true),
			Theme("Clear", "#1A1A20", 55, 30, true, "#53E076", true),
		)

		/** `config/jukeblock/themes/` — anything dropped in here shows up in the picker. */
		val directory: Path
			get() = FabricLoader.getInstance().configDir.resolve(Jukeblock.MOD_ID).resolve("themes")

		/** Built-ins first, then whatever the user has added. */
		fun all(): List<Theme> = BUILT_IN + loadUserThemes()

		private fun loadUserThemes(): List<Theme> {
			val dir = directory
            if (!Files.isDirectory(dir)) return emptyList()

			return try {
				Files.list(dir).use { stream ->
					stream.filter { it.extension.equals("json", ignoreCase = true) }
						.map { path -> read(path) }
						.filter { it != null }
						.map { it!! }
						.toList()
				}
			} catch (e: Exception) {
				Jukeblock.LOGGER.warn("Could not list themes in {}", dir, e)
				emptyList()
			}
		}

		private fun read(path: Path): Theme? = try {
			val parsed = GSON.fromJson(JsonParser.parseString(Files.readString(path)), Theme::class.java)
			// Fall back to the filename so a theme without a "name" field still shows up
			// as something recognisable rather than "Unnamed".
			parsed?.let { if (it.name.isBlank() || it.name == "Unnamed") it.copy(name = path.nameWithoutExtension) else it }
		} catch (e: Exception) {
			Jukeblock.LOGGER.warn("Skipping malformed theme {}", path, e)
			null
		}

		/**
		 * Writes the current settings out as a theme file, so a look worth keeping can be
		 * edited by hand and shared. Returns the path written, or null on failure.
		 */
		fun exportCurrent(): Path? {
			val config = JukeblockConfig.current
			val theme = Theme(
				name = "My theme",
				panelColor = config.panelColor,
				panelOpacity = config.panelOpacity,
				backdropDim = config.backdropDim,
				accentFromArt = config.accentFromArt,
				accentColor = config.accentColor,
				adaptAccentToPanel = config.adaptAccentToPanel,
			)

			return try {
				Files.createDirectories(directory)
				// Never clobber an earlier export.
				var index = 1
				var target = directory.resolve("my-theme.json")
				while (Files.exists(target)) {
					index++
					target = directory.resolve("my-theme-$index.json")
				}
				Files.writeString(target, GSON.toJson(theme))
				Jukeblock.LOGGER.info("Exported current settings to {}", target)
				target
			} catch (e: Exception) {
				Jukeblock.LOGGER.warn("Could not export theme", e)
				null
			}
		}
	}
}
