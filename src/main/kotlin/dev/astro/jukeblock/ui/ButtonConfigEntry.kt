package dev.astro.jukeblock.ui

import me.shedaniel.clothconfig2.api.AbstractConfigListEntry
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.components.Button
import net.minecraft.client.gui.components.events.GuiEventListener
import net.minecraft.client.gui.narration.NarratableEntry
import net.minecraft.network.chat.Component
import java.util.Optional

/**
 * A Cloth config row that is just a button.
 *
 * Cloth ships no button entry, and the obvious workaround — clickable text carrying a
 * `ClickEvent.RunCommand` — cannot work. Cloth routes entry clicks to
 * `Screen.defaultHandleClickEvent`, whose type switch covers only OpenUrl, OpenFile,
 * SuggestCommand and CopyToClipboard; `RunCommand` is handled exclusively by the *game*
 * variant, which requires a player and which Cloth never calls. The click fell through
 * the switch and did nothing, with no error to show for it.
 *
 * A real [Button] widget sidesteps the whole question. The entry carries no value, so it
 * saves nothing and never marks the config dirty.
 */
class ButtonConfigEntry(
	label: Component,
	buttonText: Component,
	private val onPress: () -> Unit,
) : AbstractConfigListEntry<Unit>(label, false) {

	private val button: Button = Button.builder(buttonText) { onPress() }
		.bounds(0, 0, BUTTON_WIDTH, BUTTON_HEIGHT)
		.build()

	override fun getValue() = Unit

	override fun getDefaultValue(): Optional<Unit> = Optional.empty()

	/** Nothing to persist — this row is an action, not a setting. */
	override fun save() = Unit

	override fun isEdited() = false

	override fun children(): List<GuiEventListener> = listOf(button)

	override fun narratables(): List<NarratableEntry> = listOf(button)

	override fun extractRenderState(
		graphics: GuiGraphicsExtractor,
		index: Int,
		y: Int,
		x: Int,
		entryWidth: Int,
		entryHeight: Int,
		mouseX: Int,
		mouseY: Int,
		isHovered: Boolean,
		delta: Float,
	) {
		super.extractRenderState(graphics, index, y, x, entryWidth, entryHeight, mouseX, mouseY, isHovered, delta)

		val font = Minecraft.getInstance().font
		graphics.text(font, fieldName, x, y + 6, 0xFFFFFFFF.toInt())

		// Right-aligned, matching where every other Cloth entry puts its control.
		button.x = x + entryWidth - BUTTON_WIDTH
		button.y = y
		button.extractRenderState(graphics, mouseX, mouseY, delta)
	}

	private companion object {
		const val BUTTON_WIDTH = 150
		const val BUTTON_HEIGHT = 20
	}
}
