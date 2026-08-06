package dev.astro.jukeblock.ui

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import dev.astro.jukeblock.JukeblockConfig
import dev.astro.jukeblock.Theme
import me.shedaniel.clothconfig2.api.ConfigBuilder
import me.shedaniel.clothconfig2.api.Requirement
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

			val hud = builder.getOrCreateCategory(Component.translatable("jukeblock.config.category.hud"))

			hud.addEntry(
				entries.startEnumSelector(
					Component.translatable("jukeblock.config.hudMode"),
					HudMode::class.java,
					config.hudModeEnum,
				)
					.setDefaultValue(HudMode.ON_TRACK_CHANGE)
					.setTooltip(Component.translatable("jukeblock.config.hudMode.tooltip"))
					.setSaveConsumer { config.hudMode = it.name }
					.build(),
			)

			val cornerEntry = entries.startEnumSelector(
				Component.translatable("jukeblock.config.hudCorner"),
				HudCorner::class.java,
				config.hudCornerEnum,
			)
				.setDefaultValue(HudCorner.TOP_LEFT)
				.setTooltip(Component.translatable("jukeblock.config.hudCorner.tooltip"))
				.setSaveConsumer { config.hudCorner = it.name }
				.build()
			hud.addEntry(cornerEntry)

			// A real button widget. Clickable text can't do this: Cloth dispatches entry
			// clicks to Screen.defaultHandleClickEvent, which doesn't handle RunCommand.
			hud.addEntry(
				ButtonConfigEntry(
					Component.translatable("jukeblock.config.hudPlace"),
					Component.translatable("jukeblock.config.hudPlace.button"),
				) {
					val client = net.minecraft.client.Minecraft.getInstance()
					// Keep the config screen as the parent so ESC comes back here.
					client.gui.setScreen(HudPositionScreen(client.gui.screen()))
				},
			)

			hud.addEntry(
				entries.startIntSlider(Component.translatable("jukeblock.config.hudToastSeconds"), config.hudToastSeconds, 1, 15)
					.setDefaultValue(4)
					.setTooltip(Component.translatable("jukeblock.config.hudToastSeconds.tooltip"))
					.setSaveConsumer { config.hudToastSeconds = it }
					.build(),
			)

			// Only meaningful for the four fixed corners — with FREE the HUD is already
			// exactly where it was dragged, so nudging it is noise.
			val notFree = Requirement.not(Requirement.isValue(cornerEntry, HudCorner.FREE))

			hud.addEntry(
				entries.startIntSlider(Component.translatable("jukeblock.config.hudOffsetX"), config.hudOffsetX, -200, 200)
					.setDefaultValue(0)
					.setTooltip(Component.translatable("jukeblock.config.hudOffset.tooltip"))
					.setDisplayRequirement(notFree)
					.setSaveConsumer { config.hudOffsetX = it }
					.build(),
			)

			hud.addEntry(
				entries.startIntSlider(Component.translatable("jukeblock.config.hudOffsetY"), config.hudOffsetY, -200, 200)
					.setDefaultValue(0)
					.setTooltip(Component.translatable("jukeblock.config.hudOffset.tooltip"))
					.setDisplayRequirement(notFree)
					.setSaveConsumer { config.hudOffsetY = it }
					.build(),
			)

			hud.addEntry(
				entries.startEnumSelector(
					Component.translatable("jukeblock.config.hudLayout"),
					HudLayout::class.java,
					config.hudLayoutEnum,
				)
					.setDefaultValue(HudLayout.FULL)
					.setTooltip(Component.translatable("jukeblock.config.hudLayout.tooltip"))
					.setSaveConsumer { config.hudLayout = it.name }
					.build(),
			)

			hud.addEntry(
				entries.startBooleanToggle(Component.translatable("jukeblock.config.hudHideWhenPaused"), config.hudHideWhenPaused)
					.setDefaultValue(false)
					.setSaveConsumer { config.hudHideWhenPaused = it }
					.build(),
			)

			// --- HUD colours ----------------------------------------------------
			val followEntry = entries.startBooleanToggle(
				Component.translatable("jukeblock.config.hudFollowTheme"),
				config.hudFollowPanelTheme,
			)
				.setDefaultValue(true)
				.setTooltip(Component.translatable("jukeblock.config.hudFollowTheme.tooltip"))
				.setSaveConsumer { config.hudFollowPanelTheme = it }
				.build()
			hud.addEntry(followEntry)

			// Showing overrides that aren't in effect would just invite the question of
			// why editing them changes nothing.
			val notFollowing = Requirement.isFalse(followEntry)

			hud.addEntry(
				entries.startColorField(Component.translatable("jukeblock.config.hudPanelColor"), config.hudSurfaceRgb)
					.setDefaultValue(0x2A2A33)
					.setDisplayRequirement(notFollowing)
					.setSaveConsumer { config.hudPanelColor = JukeblockConfig.toHex(it) }
					.build(),
			)

			hud.addEntry(
				entries.startIntSlider(Component.translatable("jukeblock.config.hudPanelOpacity"), config.hudPanelOpacity, 0, 100)
					.setDefaultValue(93)
					.setTextGetter { percent(it) }
					.setTooltip(Component.translatable("jukeblock.config.hudPanelOpacity.tooltip"))
					.setDisplayRequirement(notFollowing)
					.setSaveConsumer { config.hudPanelOpacity = it }
					.build(),
			)

			hud.addEntry(
				entries.startBooleanToggle(Component.translatable("jukeblock.config.hudAccentFromArt"), config.hudAccentFromArt)
					.setDefaultValue(true)
					.setDisplayRequirement(notFollowing)
					.setSaveConsumer { config.hudAccentFromArt = it }
					.build(),
			)

			hud.addEntry(
				entries.startColorField(Component.translatable("jukeblock.config.hudAccentColor"), config.hudFixedAccentRgb)
					.setDefaultValue(0x53E076)
					.setDisplayRequirement(notFollowing)
					.setSaveConsumer { config.hudAccentColor = JukeblockConfig.toHex(it) }
					.build(),
			)

			hud.addEntry(
				entries.startBooleanToggle(Component.translatable("jukeblock.config.hudAdaptAccent"), config.hudAdaptAccent)
					.setDefaultValue(true)
					.setTooltip(Component.translatable("jukeblock.config.adaptAccent.tooltip"))
					.setDisplayRequirement(notFollowing)
					.setSaveConsumer { config.hudAdaptAccent = it }
					.build(),
			)

			// --- themes ---------------------------------------------------------
			val themesCategory = builder.getOrCreateCategory(Component.translatable("jukeblock.config.category.themes"))
			val themes = Theme.all()
			val themeNames = themes.map { it.name }.toTypedArray()

			// Held so the Apply button can read the current selection. Cloth entries are
			// ValueHolders, which is exactly what's needed and saves tracking it twice.
			val themePicker = entries.startSelector(
				Component.translatable("jukeblock.config.theme"),
				themeNames,
				themeNames.firstOrNull() ?: "",
			)
				.setDefaultValue(themeNames.firstOrNull() ?: "")
				.setTooltip(Component.translatable("jukeblock.config.theme.tooltip"))
				.build()
			themesCategory.addEntry(themePicker)

			themesCategory.addEntry(
				ButtonConfigEntry(
					Component.translatable("jukeblock.config.themeApply"),
					Component.translatable("jukeblock.config.themeApply.button"),
				) {
					// Applying writes into the live config, so the other tabs' entries are
					// now stale. Reopening is the honest way to show that.
					themes.firstOrNull { it.name == themePicker.value }?.apply()
					val client = net.minecraft.client.Minecraft.getInstance()
					client.gui.setScreen(getModConfigScreenFactory().create(parent))
				},
			)

			themesCategory.addEntry(
				ButtonConfigEntry(
					Component.translatable("jukeblock.config.themeExport"),
					Component.translatable("jukeblock.config.themeExport.button"),
				) {
					// Save first: exporting what's on screen rather than what's on disk is
					// what anyone pressing this expects.
					JukeblockConfig.save()
					Theme.exportCurrent()
				},
			)

			themesCategory.addEntry(
				entries.startTextDescription(
					Component.translatable("jukeblock.config.themeFolder", Theme.directory.toString()),
				).build(),
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
