package dev.astro.jukeblock.ui

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import dev.astro.jukeblock.JukeblockConfig
import me.shedaniel.clothconfig2.api.ConfigBuilder
import net.fabricmc.loader.api.FabricLoader
import net.minecraft.network.chat.Component

/**
 * ModMenu entry, so the panel can be configured in-game rather than by hand-editing
 * `config/jukeblock.json`.
 *
 * ModMenu and Cloth Config are both optional at runtime — the mod is compiled against
 * local jars in `libs/` and declares them only as `suggests`. If Cloth Config isn't
 * installed, ModMenu shows Jukeblock without a config button instead of crashing.
 */
class JukeblockModMenu : ModMenuApi {

	override fun getModConfigScreenFactory(): ConfigScreenFactory<*> {
		if (!FabricLoader.getInstance().isModLoaded("cloth-config")) {
			return ConfigScreenFactory { null }
		}

		return ConfigScreenFactory { parent ->
			val config = JukeblockConfig.current
			val builder = ConfigBuilder.create()
				.setParentScreen(parent)
				.setTitle(Component.translatable("jukeblock.config.title"))
				.setSavingRunnable { JukeblockConfig.save() }

			val entries = builder.entryBuilder()
			val appearance = builder.getOrCreateCategory(Component.translatable("jukeblock.config.category.appearance"))

			appearance.addEntry(
				entries.startColorField(Component.translatable("jukeblock.config.panelColor"), config.panelRgb)
					.setDefaultValue(0x2A2A33)
					.setTooltip(Component.translatable("jukeblock.config.panelColor.tooltip"))
					.setSaveConsumer { config.panelColor = JukeblockConfig.toHex(it) }
					.build(),
			)

			appearance.addEntry(
				entries.startIntSlider(Component.translatable("jukeblock.config.panelOpacity"), config.panelOpacity, 0, 100)
					.setDefaultValue(93)
					.setTextGetter { percent(it) }
					.setTooltip(Component.translatable("jukeblock.config.panelOpacity.tooltip"))
					.setSaveConsumer { config.panelOpacity = it }
					.build(),
			)

			appearance.addEntry(
				entries.startIntSlider(Component.translatable("jukeblock.config.backdropDim"), config.backdropDim, 0, 100)
					.setDefaultValue(15)
					.setTextGetter { percent(it) }
					.setTooltip(Component.translatable("jukeblock.config.backdropDim.tooltip"))
					.setSaveConsumer { config.backdropDim = it }
					.build(),
			)

			appearance.addEntry(
				entries.startIntSlider(Component.translatable("jukeblock.config.railWidth"), config.railWidth, 120, 320)
					.setDefaultValue(180)
					.setTooltip(Component.translatable("jukeblock.config.railWidth.tooltip"))
					.setSaveConsumer { config.railWidth = it }
					.build(),
			)

			appearance.addEntry(
				entries.startBooleanToggle(Component.translatable("jukeblock.config.smoothAlbumArt"), config.smoothAlbumArt)
					.setDefaultValue(true)
					.setTooltip(Component.translatable("jukeblock.config.smoothAlbumArt.tooltip"))
					.setSaveConsumer { config.smoothAlbumArt = it }
					.build(),
			)

			val accent = builder.getOrCreateCategory(Component.translatable("jukeblock.config.category.accent"))

			accent.addEntry(
				entries.startBooleanToggle(Component.translatable("jukeblock.config.accentFromArt"), config.accentFromArt)
					.setDefaultValue(true)
					.setTooltip(Component.translatable("jukeblock.config.accentFromArt.tooltip"))
					.setSaveConsumer { config.accentFromArt = it }
					.build(),
			)

			accent.addEntry(
				entries.startColorField(Component.translatable("jukeblock.config.accentColor"), config.accentRgb)
					.setDefaultValue(0x53E076)
					.setTooltip(Component.translatable("jukeblock.config.accentColor.tooltip"))
					.setSaveConsumer { config.accentColor = JukeblockConfig.toHex(it) }
					.build(),
			)

			accent.addEntry(
				entries.startBooleanToggle(Component.translatable("jukeblock.config.adaptAccent"), config.adaptAccentToPanel)
					.setDefaultValue(true)
					.setTooltip(Component.translatable("jukeblock.config.adaptAccent.tooltip"))
					.setSaveConsumer { config.adaptAccentToPanel = it }
					.build(),
			)

			builder.build()
		}
	}

	private fun percent(value: Int): Component = Component.literal("$value%")
}
