package dev.astro.jukeblock.ui

import dev.astro.jukeblock.JukeblockConfig
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.network.chat.Component
import org.lwjgl.glfw.GLFW

/**
 * Drag the now-playing HUD wherever you want it.
 *
 * Nudge sliders were the first attempt and they're miserable — you can't see what you're
 * adjusting while you adjust it. Here the HUD is drawn live and you just move it.
 *
 * Position is stored as a **fraction** of the screen rather than absolute units, so it
 * stays where you put it when the window resizes or the GUI scale changes.
 */
class HudPositionScreen(private val parent: Screen?) : Screen(Component.translatable("jukeblock.hud.position.title")) {

	private var dragging = false
	private var grabX = 0
	private var grabY = 0

	/** Snap to an edge or the centre when within this many units of it. */
	private val snap = 6

	override fun isPauseScreen(): Boolean = false

	override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
		graphics.fill(0, 0, width, height, 0x99000000.toInt())
		super.extractRenderState(graphics, mouseX, mouseY, partialTick)

		graphics.nextStratum()

		val box = NowPlayingHud.bounds(width, height)
		val hovered = box.contains(mouseX, mouseY)

		// Guide lines while dragging, so aligning to an edge or the centre is possible
		// by eye rather than by arithmetic.
		if (dragging) {
			val cx = box.x + box.w / 2
			val cy = box.y + box.h / 2
			graphics.fill(cx, 0, cx + 1, height, 0x33FFFFFF)
			graphics.fill(0, cy, width, cy + 1, 0x33FFFFFF)
		}

		NowPlayingHud.renderPreview(graphics)

		val outline = if (hovered || dragging) 0xFFFFFFFF.toInt() else 0x66FFFFFF
		graphics.fill(box.x - 1, box.y - 1, box.x + box.w + 1, box.y, outline)
		graphics.fill(box.x - 1, box.y + box.h, box.x + box.w + 1, box.y + box.h + 1, outline)
		graphics.fill(box.x - 1, box.y, box.x, box.y + box.h, outline)
		graphics.fill(box.x + box.w, box.y, box.x + box.w + 1, box.y + box.h, outline)

		graphics.centeredText(font, Component.translatable("jukeblock.hud.position.hint"), width / 2, 12, 0xFFFFFFFF.toInt())
		graphics.centeredText(font, Component.translatable("jukeblock.hud.position.hint2"), width / 2, 12 + font.lineHeight + 3, 0xFFA8A8B4.toInt())
	}

	override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
		val box = NowPlayingHud.bounds(width, height)
		if (box.contains(event.x().toInt(), event.y().toInt())) {
			dragging = true
			grabX = event.x().toInt() - box.x
			grabY = event.y().toInt() - box.y
			return true
		}
		return super.mouseClicked(event, doubleClick)
	}

	override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
		if (!dragging) return super.mouseDragged(event, dragX, dragY)

		val box = NowPlayingHud.bounds(width, height)
		var x = event.x().toInt() - grabX
		var y = event.y().toInt() - grabY

		// Snap to the edges and the centre line.
		if (x < snap) x = 0
		if (x + box.w > width - snap) x = width - box.w
		if (kotlin.math.abs(x + box.w / 2 - width / 2) < snap) x = (width - box.w) / 2
		if (y < snap) y = 0
		if (y + box.h > height - snap) y = height - box.h
		if (kotlin.math.abs(y + box.h / 2 - height / 2) < snap) y = (height - box.h) / 2

		x = x.coerceIn(0, (width - box.w).coerceAtLeast(0))
		y = y.coerceIn(0, (height - box.h).coerceAtLeast(0))

		val config = JukeblockConfig.current
		config.hudFreeX = x.toFloat() / (width - box.w).coerceAtLeast(1)
		config.hudFreeY = y.toFloat() / (height - box.h).coerceAtLeast(1)
		return true
	}

	override fun mouseReleased(event: MouseButtonEvent): Boolean {
		if (dragging) {
			dragging = false
			JukeblockConfig.save()
			return true
		}
		return super.mouseReleased(event)
	}

	override fun keyPressed(event: KeyEvent): Boolean {
		if (event.key() == GLFW.GLFW_KEY_ESCAPE) {
			onClose()
			return true
		}
		return super.keyPressed(event)
	}

	override fun onClose() {
		JukeblockConfig.save()
		minecraft.gui.setScreen(parent)
	}
}
