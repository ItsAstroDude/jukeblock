package dev.astro.jukeblock.ui

import dev.astro.jukeblock.Jukeblock
import dev.astro.jukeblock.JukeblockConfig
import dev.astro.jukeblock.media.MediaService
import dev.astro.jukeblock.media.TrackInfo
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElement
import net.fabricmc.fabric.api.client.rendering.v1.hud.HudElementRegistry
import net.minecraft.client.DeltaTracker
import net.minecraft.client.Minecraft
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import kotlin.math.max
import kotlin.math.roundToInt

/** Where the HUD sits. Top-left is the default — see [NowPlayingHud]. */
enum class HudCorner { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

enum class HudMode {
	/** Never shown. */
	OFF,

	/** Appears for a few seconds when the track changes, then fades. */
	ON_TRACK_CHANGE,

	/** Always on screen while something is playing. */
	ALWAYS,
}

/**
 * The corner now-playing readout: small art, title, artist, and a thin progress line.
 *
 * Display-only and click-through by design — it's drawn into the HUD layer, so there is
 * nothing to hit. Every control lives in the panel; this exists to answer "what is this
 * song" without opening anything.
 *
 * **Top-left is the default corner** because it's the one place vanilla reliably leaves
 * clear: chat owns bottom-left, status effects top-right, the scoreboard the right edge,
 * and the hotbar/health/XP the bottom centre. Other mods are a different matter — this
 * modpack has a durability HUD that draws over screens — hence the corner choice and the
 * X/Y nudge in config.
 */
object NowPlayingHud : HudElement {

	private const val ART = 26
	private const val PAD = 4
	private const val GAP = 5
	private const val WIDTH = 140
	private const val PROGRESS_H = 2

	/** Fade at each end of a toast. */
	private const val FADE_MS = 220f

	private var lastTrackKey: String? = null
	private var toastUntilMs = 0L

	/** Marquee offset for titles too long to fit, in units. */
	private var scroll = 0f
	private var scrollKey: String? = null
	private var lastFrameMs = 0L

	fun register() {
		HudElementRegistry.addLast(Identifier.fromNamespaceAndPath(Jukeblock.MOD_ID, "now_playing"), this)
	}

	/**
	 * Called from the client tick so a track change is noticed even when the HUD is
	 * hidden — otherwise the first render after a change would be treated as the change.
	 */
	fun tick() {
		val key = MediaService.nowPlaying?.trackKey
		if (key != lastTrackKey) {
			// Don't toast the very first track seen after launch; the player didn't
			// change anything, the game just started.
			if (lastTrackKey != null && key != null) {
				toastUntilMs = System.currentTimeMillis() +
					(JukeblockConfig.current.hudToastSeconds * 1000L)
			}
			lastTrackKey = key
			scroll = 0f
		}
	}

	override fun extractRenderState(graphics: GuiGraphicsExtractor, delta: DeltaTracker) {
		val config = JukeblockConfig.current
		val mode = config.hudModeEnum
		if (mode == HudMode.OFF) return

		val client = Minecraft.getInstance()
		// A screen is open, so the panel (or an inventory) is already saying more than
		// this would. F1 is handled for us: Minecraft skips the whole HUD pass.
		if (client.gui.screen() != null) return
		if (client.debugOverlay.showDebugScreen()) return

		val track = MediaService.nowPlaying ?: return
		if (config.hudHideWhenPaused && !track.status.isPlaying) return

		val alpha = when (mode) {
			HudMode.ALWAYS -> 1f
			HudMode.ON_TRACK_CHANGE -> toastAlpha()
			HudMode.OFF -> 0f
		}
		if (alpha <= 0.01f) return

		draw(graphics, track, alpha, config)
	}

	/** Fades in at the start of a toast and back out at the end. */
	private fun toastAlpha(): Float {
		val remaining = toastUntilMs - System.currentTimeMillis()
		if (remaining <= 0) return 0f

		val total = JukeblockConfig.current.hudToastSeconds * 1000f
		val elapsed = total - remaining
		val fadeIn = (elapsed / FADE_MS).coerceIn(0f, 1f)
		val fadeOut = (remaining / FADE_MS).coerceIn(0f, 1f)
		return Accent.smoothstep(minOf(fadeIn, fadeOut))
	}

	private fun draw(graphics: GuiGraphicsExtractor, track: TrackInfo, alpha: Float, config: JukeblockConfig) {
		val client = Minecraft.getInstance()
		val font = client.font
		val height = ART + PAD * 2

		val screenW = graphics.guiWidth()
		val screenH = graphics.guiHeight()

		val corner = config.hudCornerEnum
		val x = when (corner) {
			HudCorner.TOP_LEFT, HudCorner.BOTTOM_LEFT -> PAD + config.hudOffsetX
			HudCorner.TOP_RIGHT, HudCorner.BOTTOM_RIGHT -> screenW - WIDTH - PAD + config.hudOffsetX
		}
		val y = when (corner) {
			HudCorner.TOP_LEFT, HudCorner.TOP_RIGHT -> PAD + config.hudOffsetY
			HudCorner.BOTTOM_LEFT, HudCorner.BOTTOM_RIGHT -> screenH - height - PAD + config.hudOffsetY
		}

		val accent = if (config.accentFromArt) AlbumArt.accent else (0xFF shl 24) or config.accentRgb
		val panel = config.panelArgb
		val adapted = if (config.adaptAccentToPanel) Accent.adaptTo(accent, config.panelRgb) else accent

		fun fade(color: Int): Int {
			val a = ((color ushr 24 and 0xFF) / 255f * alpha * 255f).toInt().coerceIn(0, 255)
			return (color and 0x00FFFFFF) or (a shl 24)
		}

		graphics.fill(x, y, x + WIDTH, y + height, fade(panel))
		// Accent edge, matching the panel's own hairline.
		graphics.fill(x, y, x + 1, y + height, fade(Accent.withAlpha(adapted, 0.8f)))

		// Album art, fitted rather than stretched — browser thumbnails are 16:9.
		val art = AlbumArt.texture
		val artX = x + PAD
		val artY = y + PAD
		if (art != null && AlbumArt.artWidth > 0) {
			val srcW = AlbumArt.artWidth
			val srcH = AlbumArt.artHeight.coerceAtLeast(1)
			val w: Int
			val h: Int
			if (srcW >= srcH) {
				w = ART
				h = max(1, ART * srcH / srcW)
			} else {
				h = ART
				w = max(1, ART * srcW / srcH)
			}
			graphics.blit(art, artX + (ART - w) / 2, artY + (ART - h) / 2, artX + (ART - w) / 2 + w, artY + (ART - h) / 2 + h, 0f, 1f, 0f, 1f)
		} else {
			graphics.fill(artX, artY, artX + ART, artY + ART, fade(Accent.withAlpha(adapted, 0.25f)))
		}

		val textX = artX + ART + GAP
		val textW = WIDTH - PAD - (textX - x)
		val light = Accent.isLight(config.panelRgb)
		val title = fade(if (light) 0xFF16161A.toInt() else 0xFFF4F4F7.toInt())
		val sub = fade(if (light) 0xFF4A4A55.toInt() else 0xFFA8A8B4.toInt())

		// Marquee only the title; the artist gets a plain ellipsis. Two things scrolling
		// at once in the corner of the screen is noise, not information.
		graphics.enableScissor(textX, y + PAD, textX + textW, y + PAD + font.lineHeight)
		val titleW = font.width(track.title)
		val offset = if (titleW > textW) marquee(track.trackKey, titleW, textW) else 0
		graphics.text(font, track.title, textX - offset, y + PAD, title)
		graphics.disableScissor()

		val artist = if (font.width(track.artist) > textW) {
			font.plainSubstrByWidth(track.artist, textW - font.width("...")) + "..."
		} else {
			track.artist
		}
		graphics.text(font, artist, textX, y + PAD + font.lineHeight + 2, sub)

		if (track.durationMs > 0) {
			val barY = y + height - PAD - PROGRESS_H
			val barW = textW
            val fill = (barW * track.progress).roundToInt().coerceIn(0, barW)
			graphics.fill(textX, barY, textX + barW, barY + PROGRESS_H, fade(Accent.withAlpha(adapted, 0.25f)))
			graphics.fill(textX, barY, textX + fill, barY + PROGRESS_H, fade(adapted))
		}
	}

	/**
	 * Scrolls a long title back and forth, pausing at each end.
	 *
	 * Frame-rate independent: driven by wall-clock delta rather than a per-frame step,
	 * so it moves at the same speed at 30 fps and 300.
	 */
	private fun marquee(key: String, textWidth: Int, viewWidth: Int): Int {
		val now = System.currentTimeMillis()
		val deltaMs = if (lastFrameMs == 0L) 0L else (now - lastFrameMs).coerceAtMost(100L)
		lastFrameMs = now

		if (key != scrollKey) {
			scrollKey = key
			scroll = 0f
		}

		val travel = (textWidth - viewWidth).toFloat()
		// Units per second, plus a pause at each end expressed as extra travel.
		val speed = 14f
		val pause = 26f
		val cycle = travel + pause * 2
		scroll = (scroll + speed * deltaMs / 1000f) % (cycle * 2)

		val forward = scroll <= cycle
		val phase = if (forward) scroll else cycle * 2 - scroll
		return (phase - pause).coerceIn(0f, travel).toInt()
	}
}
