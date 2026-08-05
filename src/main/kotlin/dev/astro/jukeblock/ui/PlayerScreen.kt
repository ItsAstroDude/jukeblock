package dev.astro.jukeblock.ui

import dev.astro.jukeblock.JukeblockConfig
import dev.astro.jukeblock.media.Capability
import dev.astro.jukeblock.media.MediaCommand
import dev.astro.jukeblock.media.MediaService
import dev.astro.jukeblock.media.RepeatMode
import dev.astro.jukeblock.media.TrackInfo
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.network.chat.Component
import net.minecraft.resources.Identifier
import org.lwjgl.glfw.GLFW
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import kotlin.math.roundToInt

/**
 * The Jukeblock panel: a full-height rail on the left edge.
 *
 * A rail rather than a floating card, deliberately — lyrics (Phase 2) and queue/search
 * (Phase 5) need somewhere to live, and a card would have to be redesigned to fit them.
 *
 * The game keeps running behind it ([isPauseScreen] is false), so this is opened and
 * closed constantly; everything about it is tuned for that. The slide is ~180 ms because
 * CosmicNotify's 400 ms felt sluggish for something you hit between fights.
 */
class PlayerScreen : Screen(Component.translatable("jukeblock.panel.title")) {

	private companion object {
		/**
		 * Rail width in GUI units.
		 *
		 * ⚠️ `width`/`height` on a Screen are GUI-**scaled** units, not pixels. At GUI
		 * scale 4 one unit is four physical pixels, so the plan's "~300 px" rail would
		 * cover 1200 px — most of the screen. 180 units is a shade wider than the vanilla
		 * inventory (176), which is the width Minecraft UIs have trained everyone to read.
		 */
		const val RAIL_UNITS = 180

		/** Never let the rail swallow a small window, whatever the GUI scale. */
		const val RAIL_MAX_SCREEN_FRACTION = 0.42f

		const val PADDING = 12

		/** Gap between stacked sections. Smaller than the edge padding, or the rail reads as empty. */
		const val SECTION_GAP = 10

		const val SLIDE_MS = 180f

		// Text and chrome come in a light-on-dark and a dark-on-light set. Which one is
		// used depends on the panel colour, so a user who picks a pale background still
		// gets readable text instead of white-on-white.
		const val TEXT_ON_DARK = 0xFFF4F4F7.toInt()
		const val TEXT_ON_DARK_DIM = 0xFFA8A8B4.toInt()
		const val TEXT_ON_DARK_FAINT = 0xFF7A7A88.toInt()
		const val TEXT_ON_LIGHT = 0xFF16161A.toInt()
		const val TEXT_ON_LIGHT_DIM = 0xFF4A4A55.toInt()
		const val TEXT_ON_LIGHT_FAINT = 0xFF74747F.toInt()

		// Gradient endpoints. Both are fully opaque and the blend is only 6%, so the
		// panel's own alpha shifts by well under one step of 255.
		const val WHITE = 0xFFFFFFFF.toInt()
		const val BLACK = 0xFF000000.toInt()

		val ICONS: Identifier = Identifier.fromNamespaceAndPath("jukeblock", "textures/gui/icons.png")
		/** Size the glyph is drawn at, in GUI units. */
		const val ICON_SIZE = 16

		/** Source resolution per glyph. 64px is 1:1 at GUI scale 4. */
		const val ICON_SRC = 64
		const val ICON_SHEET_WIDTH = ICON_SRC * 7

		/** The three buttons actually hit mid-game get room; toggles are smaller. */
		const val MAIN_TRANSPORT_SIZE = 38
		const val MAIN_TRANSPORT_GAP = 14
		const val TOGGLE_SIZE = 18
		const val TOGGLE_ICON_SIZE = 12

		const val VOLUME_HEIGHT = 4
		const val VOLUME_HIT_PAD = 6
		const val VOLUME_ICON_WIDTH = 12
		const val PROGRESS_HEIGHT = 4
		/** Generous vertical hit area — the bar itself is only 4px and hard to hit. */
		const val PROGRESS_HIT_PAD = 6

		val REVERSE_DNS_PREFIXES = setOf("com", "org", "net", "io", "app")

		/** Ids whose cleaned-up form still isn't what the app is actually called. */
		val FRIENDLY_NAMES = mapOf(
			"msedge" to "Edge",
			"chrome" to "Chrome",
			"firefox" to "Firefox",
			"brave" to "Brave",
			"opera" to "Opera",
			"vlc" to "VLC",
			"wmplayer" to "Windows Media Player",
			"microsoft.zunemusic" to "Media Player",
			"microsoft.zunevideo" to "Films & TV",
			"applemusic" to "Apple Music",
			"itunes" to "iTunes",
		)
	}

	private var openedAtMs = 0L
	private var closing = false
	private var slide = 0f

	/** Recomputed on init/resize — see [RAIL_UNITS]. */
	private var railWidth = RAIL_UNITS

	/**
	 * Album art edge length. The art is the one element that can be given whatever room
	 * is left, so it absorbs the difference between a tall window and a short one; at
	 * GUI scale 4 the screen is only ~270 units high and a full-width square cover would
	 * push the transport row off the bottom entirely.
	 */
	private var artSize = 0

	/** Set while the user drags the progress bar, so polling doesn't fight the scrub. */
	private var scrubbing = false
	private var scrubFraction = 0f

	// Resolved per frame from the config, so editing jukeblock.json and reopening the
	// panel is enough to see the change.
	private var colorText = TEXT_ON_DARK
	private var colorTextDim = TEXT_ON_DARK_DIM
	private var colorTextFaint = TEXT_ON_DARK_FAINT
	private var colorTrack = 0xFF3A3A44.toInt()
	private var colorDisabled = 0xFF4E4E58.toInt()

	/**
	 * The accent to draw with: from the album art or the user's fixed colour, then made
	 * to stand out against the panel.
	 *
	 * The adaptation is why the panel colour is safe to expose as a setting — pick a red
	 * rail while a red cover is playing and the progress fill would otherwise vanish into
	 * the surface behind it.
	 */
	private fun accentFor(config: JukeblockConfig): Int {
		val base = if (config.accentFromArt) AlbumArt.accent else (0xFF shl 24) or config.accentRgb
		return if (config.adaptAccentToPanel) Accent.adaptTo(base, config.panelRgb) else base
	}

	/**
	 * Picks the text/chrome palette for the configured panel colour, and derives the
	 * track and disabled shades from it so they sit a fixed distance from the surface
	 * rather than being hardcoded for one background.
	 */
	private fun resolvePalette(panel: Int) {
		val light = Accent.isLight(panel)
		colorText = if (light) TEXT_ON_LIGHT else TEXT_ON_DARK
		colorTextDim = if (light) TEXT_ON_LIGHT_DIM else TEXT_ON_DARK_DIM
		colorTextFaint = if (light) TEXT_ON_LIGHT_FAINT else TEXT_ON_DARK_FAINT
		// Empty progress track: a step away from the surface, toward the text.
		colorTrack = Accent.lerpColor(panel or (0xFF shl 24), colorText, 0.18f)
		colorDisabled = Accent.lerpColor(panel or (0xFF shl 24), colorText, 0.42f)
	}

	private val transportButtons = mutableListOf<TransportButton>()
	private val toggleButtons = mutableListOf<TransportButton>()

	/** Set while the user drags the volume bar, so polling doesn't fight the drag. */
	private var draggingVolume = false
	private var volumeDrag = 0f
	private var volumeBarTop: Int? = null

	/** Where the title line was drawn, so it can be clicked to open the track. */
	private var titleTop: Int? = null

	private class TransportButton(
		val command: MediaCommand,
		val glyph: Glyph,
		var x: Int = 0,
		var y: Int = 0,
		var size: Int = MAIN_TRANSPORT_SIZE,
		var enabled: Boolean = true,
	) {
		fun contains(mx: Double, my: Double): Boolean =
			mx >= x && mx < x + size && my >= y && my < y + size
	}

	/** `index` is the glyph's slot in icons.png — keep in step with tools/icons/make_icons.py. */
	private enum class Glyph(val index: Int) {
		PREVIOUS(0),
		PLAY(1),
		PAUSE(2),
		NEXT(3),
		SHUFFLE(4),
		REPEAT(5),
		REPEAT_ONE(6),
	}

	override fun init() {
		if (openedAtMs == 0L) openedAtMs = System.nanoTime()
		MediaService.setActive(true)
		layout()
	}

	/**
	 * Sizes the rail and the artwork for the current window.
	 *
	 * Everything below the art has a fixed height, so it gets reserved first and the art
	 * takes what remains. That way the transport row is always reachable — it's the part
	 * the panel exists for — and the cover is what gives ground on a short window.
	 */
	private fun layout() {
		railWidth = minOf(JukeblockConfig.current.railWidth, (width * RAIL_MAX_SCREEN_FRACTION).toInt())

		// Mirrors the render order exactly. Metadata always reserves three lines even
		// when a track has no album, so the art doesn't resize as tracks change — a
		// cover that jumps size on every skip is worse than a little slack.
		val metadata = font.lineHeight * 3 + 6
		val progress = PROGRESS_HEIGHT + 5 + font.lineHeight

		val reserved = PADDING + SECTION_GAP + metadata + SECTION_GAP + progress +
			SECTION_GAP + MAIN_TRANSPORT_SIZE + SECTION_GAP +
			// One bottom band holds volume and the toggles together.
			TOGGLE_SIZE + PADDING

		artSize = minOf(railWidth - PADDING * 2, height - reserved).coerceAtLeast(0)
	}

	override fun resize(width: Int, height: Int) {
		super.resize(width, height)
		layout()
	}

	override fun removed() {
		MediaService.setActive(false)
	}

	/** The whole point of the panel: the game must not pause behind it. */
	override fun isPauseScreen(): Boolean = false

	// The rail is drawn in extractRenderState; the default menu background would cover
	// the world, which is exactly what we don't want.
	override fun extractBackground(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
		val strength = JukeblockConfig.current.backdropDim / 100f
		val dim = (strength * slide * 255f).toInt().coerceIn(0, 255)
		if (dim > 0) {
			graphics.fill(0, 0, width, height, dim shl 24)
		}
	}

	override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, partialTick: Float) {
		advanceSlide()
		if (closing && slide <= 0f) {
			minecraft.gui.setScreen(null)
			return
		}

		val track = MediaService.nowPlaying
		AlbumArt.sync(track)

		// Slide by offsetting every x we draw, rather than transforming the matrix —
		// mouse hit-testing then needs no inverse transform.
		val originX = (-railWidth * (1f - slide)).roundToInt()

		super.extractRenderState(graphics, mouseX, mouseY, partialTick)

		// Put the rail in its own stratum. HUD elements from other mods are drawn into
		// the same render state, and anything that doesn't check for an open screen
		// otherwise lands on top of the panel — durability readouts over the transport
		// row, which is what a busy modpack looks like without this.
		graphics.nextStratum()

		val config = JukeblockConfig.current
		val panel = config.panelArgb
		resolvePalette(config.panelRgb)

		// Faint top-to-bottom gradient around the chosen colour, so the rail reads as a
		// surface rather than a flat slab. Both ends derive from the user's colour, so it
		// stays subtle whether they picked charcoal or cream.
		graphics.fillGradient(
			originX, 0, originX + railWidth, height,
			Accent.lerpColor(panel, WHITE, 0.06f),
			Accent.lerpColor(panel, BLACK, 0.06f),
		)

		val accent = accentFor(config)
		// Accent hairline down the rail's edge — ties the panel to the artwork without
		// putting a saturated colour anywhere near the text.
		graphics.fill(originX + railWidth - 1, 0, originX + railWidth, height, Accent.withAlpha(accent, 0.5f))

		if (track == null) {
			renderEmpty(graphics, originX)
			return
		}

		var y = PADDING
		y = renderArtwork(graphics, originX, y, accent)
		y = renderMetadata(graphics, originX, y, track, accent, overTitle(mouseX.toDouble(), mouseY.toDouble(), track))
		y = renderProgress(graphics, originX, y, track, accent, mouseX, mouseY)
		renderTransport(graphics, originX, y, track, accent, mouseX, mouseY)

		// One bottom band: volume on the left, toggles on the right. Giving the slider
		// its own row cost a whole section's height and the artwork paid for it.
		val bandTop = height - PADDING - TOGGLE_SIZE
		renderToggles(graphics, originX, bandTop, track, accent, mouseX, mouseY)
		renderVolume(graphics, originX, bandTop, accent, mouseX, mouseY)
	}

	private fun renderEmpty(graphics: GuiGraphicsExtractor, originX: Int) {
		val message = if (MediaService.isAvailable) {
			Component.translatable("jukeblock.panel.nothing_playing")
		} else {
			Component.translatable("jukeblock.panel.no_source")
		}
		graphics.text(font, message, originX + PADDING, height / 2 - font.lineHeight / 2, colorTextDim)
	}

	private fun renderArtwork(graphics: GuiGraphicsExtractor, originX: Int, top: Int, accent: Int): Int {
		val size = artSize
		// Too short a window to show a cover at all — skip it rather than draw a sliver.
		if (size < 24) return top

		// Centred: on a short window the art is narrower than the rail.
		val x = originX + (railWidth - size) / 2

		val art = AlbumArt.texture
		if (art != null) {
			// Fit rather than fill. Album covers are square, but browser thumbnails are
			// 16:9 and stretching one into a square box is exactly the "looks funny"
			// artefact — letterboxing keeps the whole frame at its own proportions.
			// The art area is a box, not a square: `size` is the vertical allowance and
			// the full content width is available across. A 16:9 video thumbnail then
			// grows to fill the width instead of being shrunk to fit a square, which is
			// what left browser art looking small and stranded.
			val srcW = AlbumArt.artWidth.coerceAtLeast(1)
			val srcH = AlbumArt.artHeight.coerceAtLeast(1)
			val boxW = railWidth - PADDING * 2
			val scale = minOf(boxW.toFloat() / srcW, size.toFloat() / srcH)
			val drawW = (srcW * scale).toInt().coerceAtLeast(1)
			val drawH = (srcH * scale).toInt().coerceAtLeast(1)
			val ax = originX + (railWidth - drawW) / 2
			val ay = top + (size - drawH) / 2

			// Soft glow behind the art, in the extracted accent.
			graphics.fill(ax - 2, ay - 2, ax + drawW + 2, ay + drawH + 2, Accent.withAlpha(accent, 0.25f))
			// Edge coordinates, not x/y/width/height: this overload forwards to
			// innerBlit(x0, x1, y0, y1). Passing a size here silently renders the
			// wrong rectangle.
			graphics.blit(art, ax, ay, ax + drawW, ay + drawH, 0f, 1f, 0f, 1f)
		} else {
			graphics.fill(x, top, x + size, top + size, colorTrack)
			val label = Component.translatable("jukeblock.panel.no_art")
			graphics.centeredText(font, label, x + size / 2, top + size / 2 - font.lineHeight / 2, colorTextFaint)
		}
		return top + size + SECTION_GAP
	}

	private fun renderMetadata(graphics: GuiGraphicsExtractor, originX: Int, top: Int, track: TrackInfo, accentForHover: Int, mouseOverTitle: Boolean): Int {
		val x = originX + PADDING
		val maxWidth = railWidth - PADDING * 2
		var y = top

		titleTop = y
		val titleHovered = mouseOverTitle
		val titleColor = if (titleHovered) accentForHover else colorText

		// Long titles scroll rather than truncate. An ellipsis hides exactly the part
		// that distinguishes one remix from another, and the rail is narrow enough that
		// plenty of titles overflow it.
		val titleWidth = font.width(track.title)
		if (titleWidth > maxWidth) {
			graphics.enableScissor(x, y, x + maxWidth, y + font.lineHeight)
			val offset = Marquee.offset("panel:" + track.trackKey, titleWidth, maxWidth)
			graphics.text(font, track.title, x - offset, y, titleColor)
			graphics.disableScissor()
		} else {
			graphics.text(font, track.title, x, y, titleColor)
		}
		if (titleHovered) {
			// Underline, so it's discoverable as a link rather than a secret.
			graphics.fill(x, y + font.lineHeight, x + minOf(maxWidth, titleWidth), y + font.lineHeight + 1, accentForHover)
		}
		y += font.lineHeight + 4

		if (track.artist.isNotEmpty()) {
			graphics.text(font, truncate(track.artist, maxWidth), x, y, colorTextDim)
			y += font.lineHeight + 2
		}
		if (track.album.isNotEmpty()) {
			graphics.text(font, truncate(track.album, maxWidth), x, y, colorTextFaint)
			y += font.lineHeight
		}
		return y + SECTION_GAP
	}

	private fun renderProgress(
		graphics: GuiGraphicsExtractor,
		originX: Int,
		top: Int,
		track: TrackInfo,
		accent: Int,
		mouseX: Int,
		mouseY: Int,
	): Int {
		val x = originX + PADDING
		val barWidth = railWidth - PADDING * 2

		// Remembered so input hit-tests the position that was actually laid out, rather
		// than a second copy of the layout arithmetic that could drift out of sync.
		progressBarTop = top

		val fraction = if (scrubbing) scrubFraction else track.progress
		val fillWidth = (barWidth * fraction).roundToInt().coerceIn(0, barWidth)

		graphics.fill(x, top, x + barWidth, top + PROGRESS_HEIGHT, colorTrack)
		graphics.fill(x, top, x + fillWidth, top + PROGRESS_HEIGHT, accent)

		// Scrub handle, shown on hover or while dragging, and only when seek is supported.
		val canSeek = track.supports(Capability.SEEK)
		val hovered = mouseX >= x && mouseX < x + barWidth &&
			mouseY >= top - PROGRESS_HIT_PAD && mouseY < top + PROGRESS_HEIGHT + PROGRESS_HIT_PAD
		if (canSeek && (hovered || scrubbing)) {
			val handleX = x + fillWidth
			graphics.fill(handleX - 2, top - 3, handleX + 2, top + PROGRESS_HEIGHT + 3, colorText)
		}

		var y = top + PROGRESS_HEIGHT + 5
		val elapsed = if (scrubbing) (track.durationMs * scrubFraction).toLong() else track.positionNowMs()
		graphics.text(font, formatTime(elapsed), x, y, colorTextFaint)

		val total = formatTime(track.durationMs)
		graphics.text(font, total, x + barWidth - font.width(total), y, colorTextFaint)

		// The gap between elapsed and total is dead space, and the source name is short.
		// Putting it here buys back the whole line it used to occupy at the bottom.
		val source = prettySourceName(track.sourceId) +
			if (MediaService.pinnedSourceId != null) " *" else ""
		graphics.centeredText(font, source, x + barWidth / 2, y, colorTextFaint)

		y += font.lineHeight + SECTION_GAP
		return y
	}

	/**
	 * The main transport row: previous, play/pause, next.
	 *
	 * Only these three live here. Shuffle and repeat are toggles rather than actions —
	 * you set them once and forget them — so they sit on the bottom line beside the
	 * source name, which leaves the three buttons you actually hit mid-game big and
	 * well separated.
	 */
	private fun renderTransport(
		graphics: GuiGraphicsExtractor,
		originX: Int,
		top: Int,
		track: TrackInfo,
		accent: Int,
		mouseX: Int,
		mouseY: Int,
	): Int {
		transportButtons.clear()

		val playing = track.status.isPlaying
		// Toggle needs whichever half applies right now: a player that's paused advertises
		// PLAY, and one that's playing advertises PAUSE.
		val toggleCap = if (playing) Capability.PAUSE else Capability.PLAY

		transportButtons += TransportButton(
			MediaCommand.Previous, Glyph.PREVIOUS,
			size = MAIN_TRANSPORT_SIZE, enabled = track.supports(Capability.PREVIOUS),
		)
		transportButtons += TransportButton(
			MediaCommand.Toggle,
			if (playing) Glyph.PAUSE else Glyph.PLAY,
			size = MAIN_TRANSPORT_SIZE, enabled = track.supports(toggleCap),
		)
		transportButtons += TransportButton(
			MediaCommand.Next, Glyph.NEXT,
			size = MAIN_TRANSPORT_SIZE, enabled = track.supports(Capability.NEXT),
		)

		val totalWidth = transportButtons.size * MAIN_TRANSPORT_SIZE + (transportButtons.size - 1) * MAIN_TRANSPORT_GAP
		var x = originX + (railWidth - totalWidth) / 2
		for (button in transportButtons) {
			button.x = x
			button.y = top
			drawButton(graphics, button, track, accent, mouseX, mouseY, ICON_SIZE)
			x += MAIN_TRANSPORT_SIZE + MAIN_TRANSPORT_GAP
		}

		return top + MAIN_TRANSPORT_SIZE + SECTION_GAP
	}

	/** Shuffle and repeat, tucked onto the source line at the bottom of the rail. */
	private fun renderToggles(
		graphics: GuiGraphicsExtractor,
		originX: Int,
		top: Int,
		track: TrackInfo,
		accent: Int,
		mouseX: Int,
		mouseY: Int,
	) {
		toggleButtons.clear()

		toggleButtons += TransportButton(
			MediaCommand.Shuffle(!(track.shuffle ?: false)),
			Glyph.SHUFFLE,
			size = TOGGLE_SIZE, enabled = track.supports(Capability.SHUFFLE),
		)
		toggleButtons += TransportButton(
			MediaCommand.Repeat(nextRepeat(track.repeat)),
			// Repeat-one gets its own glyph rather than a marker dot, so the three states
			// are told apart at a glance instead of by squinting.
			if (track.repeat == RepeatMode.TRACK) Glyph.REPEAT_ONE else Glyph.REPEAT,
			size = TOGGLE_SIZE, enabled = track.supports(Capability.REPEAT),
		)

		val totalWidth = toggleButtons.size * TOGGLE_SIZE + (toggleButtons.size - 1) * 4
		var x = originX + railWidth - PADDING - totalWidth
		for (button in toggleButtons) {
			button.x = x
			button.y = top
			drawButton(graphics, button, track, accent, mouseX, mouseY, TOGGLE_ICON_SIZE)
			x += TOGGLE_SIZE + 4
		}
	}

	private fun drawButton(
		graphics: GuiGraphicsExtractor,
		button: TransportButton,
		track: TrackInfo,
		accent: Int,
		mouseX: Int,
		mouseY: Int,
		iconSize: Int,
	) {
		val hovered = button.enabled && button.contains(mouseX.toDouble(), mouseY.toDouble())
		if (hovered) {
			graphics.fill(button.x, button.y, button.x + button.size, button.y + button.size, Accent.withAlpha(accent, 0.22f))
		}

		// Active states get the accent; unsupported controls are greyed rather than
		// hidden, so it's obvious the player is the limitation, not the mod.
		val active = when (button.glyph) {
			Glyph.SHUFFLE -> track.shuffle == true
			Glyph.REPEAT, Glyph.REPEAT_ONE -> track.repeat != null && track.repeat != RepeatMode.NONE
			else -> false
		}
		val color = when {
			!button.enabled -> colorDisabled
			active || hovered -> accent
			// Full strength, not the dim shade: these are the panel's primary action and
			// have to stay legible over whatever the world is doing behind them.
			else -> colorText
		}
		drawGlyph(graphics, button.glyph, button.x, button.y, button.size, color, iconSize)
	}

	/**
	 * Per-application volume.
	 *
	 * Not an SMTC feature — it comes from WASAPI audio sessions, so it's hidden entirely
	 * when the player owns none (a paused app often releases its stream). Deliberately
	 * per-app rather than the system master: turning Minecraft's own audio down along
	 * with the music would make it useless.
	 */
	private fun renderVolume(
		graphics: GuiGraphicsExtractor,
		originX: Int,
		top: Int,
		accent: Int,
		mouseX: Int,
		mouseY: Int,
	): Int {
		val level = if (draggingVolume) volumeDrag else MediaService.volume
		if (level < 0f) {
			volumeBarTop = null
			return top
		}
		volumeBarTop = top

		val x = originX + PADDING + VOLUME_ICON_WIDTH
		val barWidth = volumeBarWidth()
		val fill = (barWidth * level).roundToInt().coerceIn(0, barWidth)

		// Centred in the band rather than sitting on its top edge, so it lines up with
		// the toggle glyphs beside it.
		val barY = top + (TOGGLE_SIZE - VOLUME_HEIGHT) / 2
		val cy = barY + VOLUME_HEIGHT / 2

		// A small speaker mark, drawn from two rectangles — enough to label the row.
		graphics.fill(originX + PADDING, cy - 2, originX + PADDING + 3, cy + 2, colorTextDim)
		graphics.fill(originX + PADDING + 3, cy - 4, originX + PADDING + 6, cy + 4, colorTextDim)

		graphics.fill(x, barY, x + barWidth, barY + VOLUME_HEIGHT, colorTrack)
		graphics.fill(x, barY, x + fill, barY + VOLUME_HEIGHT, Accent.withAlpha(accent, 0.9f))

		val hovered = mouseX >= x && mouseX < x + barWidth &&
			mouseY >= barY - VOLUME_HIT_PAD && mouseY < barY + VOLUME_HEIGHT + VOLUME_HIT_PAD
		if (hovered || draggingVolume) {
			graphics.fill(x + fill - 2, barY - 3, x + fill + 2, barY + VOLUME_HEIGHT + 3, colorText)
		}

		return top
	}

	/** Runs from the speaker mark to just short of the toggle buttons. */
	private fun volumeBarWidth(): Int {
		val togglesWidth = 2 * TOGGLE_SIZE + 4
		return (railWidth - PADDING * 2 - VOLUME_ICON_WIDTH - togglesWidth - 8).coerceAtLeast(16)
	}

	// --- glyphs ---------------------------------------------------------------

	/**
	 * Draws one icon from the sheet, tinted.
	 *
	 * The icons are white pixel art and the blit multiplies by [color], which is how a
	 * single sheet covers every state — accent when active, grey when the source doesn't
	 * support the control. Hand-drawn rectangles were the first attempt and looked it.
	 */
	private fun drawGlyph(graphics: GuiGraphicsExtractor, glyph: Glyph, x: Int, y: Int, size: Int, color: Int, iconSize: Int = ICON_SIZE) {
		val left = x + (size - iconSize) / 2
		val top = y + (size - iconSize) / 2

		// The dark halo is baked into the texture rather than blitted as an offset copy:
		// multiplying by the tint leaves white as the tint colour and black as black, so
		// the halo survives colouring and scales with the glyph. The offset version left
		// a visible ghost that made the icons look smeared.
		graphics.blit(
			RenderPipelines.GUI_TEXTURED,
			ICONS,
			left,
			top,
			(glyph.index * ICON_SRC).toFloat(),
			0f,
			iconSize,
			iconSize,
			// Source region is 64px per glyph while the draw size is ~16 units, so the
			// region has to be given separately from the destination size.
			ICON_SRC,
			ICON_SRC,
			ICON_SHEET_WIDTH,
			ICON_SRC,
			color,
		)
	}

	// --- input ----------------------------------------------------------------

	override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
		if (closing) return true
		val mx = event.x()
		val my = event.y()

		// Click-outside dismiss. Uses the rail's animated position so a click during the
		// slide-in doesn't immediately close it again.
		val railRight = railWidth + (-railWidth * (1f - slide))
		if (mx > railRight) {
			beginClose()
			return true
		}

		for (button in transportButtons + toggleButtons) {
			if (button.enabled && button.contains(mx, my)) {
				MediaService.send(button.command)
				return true
			}
		}

		val track = MediaService.nowPlaying
		if (overTitle(mx, my, track) && track != null) {
			openTrack(track)
			return true
		}

		if (overVolumeBar(mx, my)) {
			draggingVolume = true
			volumeDrag = volumeFractionAt(mx)
			MediaService.setVolume(volumeDrag)
			return true
		}

		if (track != null && track.supports(Capability.SEEK) && overProgressBar(mx, my)) {
			scrubbing = true
			scrubFraction = fractionAt(mx)
			return true
		}

		return super.mouseClicked(event, doubleClick)
	}

	override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
		if (draggingVolume) {
			volumeDrag = volumeFractionAt(event.x())
			MediaService.setVolume(volumeDrag)
			return true
		}
		if (scrubbing) {
			scrubFraction = fractionAt(event.x())
			return true
		}
		return super.mouseDragged(event, dragX, dragY)
	}

	override fun mouseReleased(event: MouseButtonEvent): Boolean {
		if (draggingVolume) {
			draggingVolume = false
			return true
		}
		if (scrubbing) {
			scrubbing = false
			val track = MediaService.nowPlaying
			if (track != null && track.durationMs > 0) {
				MediaService.send(MediaCommand.Seek((track.durationMs * scrubFraction).toLong()))
			}
			return true
		}
		return super.mouseReleased(event)
	}

	override fun mouseScrolled(mouseX: Double, mouseY: Double, scrollX: Double, scrollY: Double): Boolean {
		if (overVolumeBar(mouseX, mouseY)) {
			val current = MediaService.volume
			if (current >= 0f) {
				MediaService.setVolume((current + if (scrollY > 0) 0.05f else -0.05f).coerceIn(0f, 1f))
				return true
			}
		}

		// Scroll over the progress bar to seek (PLAN §6.5).
		val track = MediaService.nowPlaying
		if (track != null && track.supports(Capability.SEEK) && track.durationMs > 0 && overProgressBar(mouseX, mouseY)) {
			val step = 5_000L
			val target = (track.positionNowMs() + (if (scrollY > 0) step else -step))
				.coerceIn(0L, track.durationMs)
			MediaService.send(MediaCommand.Seek(target))
			return true
		}
		return super.mouseScrolled(mouseX, mouseY, scrollX, scrollY)
	}

	override fun keyPressed(event: KeyEvent): Boolean {
		// ESC and the toggle key both dismiss, so the panel closes with whichever the
		// player reaches for.
		// F1 is the screenshot-tidying key everywhere else in Minecraft; while a screen
		// is open the game doesn't handle it, so the panel honours it itself.
		if (event.key() == GLFW.GLFW_KEY_ESCAPE ||
			event.key() == GLFW.GLFW_KEY_F1 ||
			JukeblockKeys.isToggleKey(event.key())
		) {
			beginClose()
			return true
		}
		return super.keyPressed(event)
	}

	override fun shouldCloseOnEsc(): Boolean = false

	// --- helpers --------------------------------------------------------------

	/** Vertical band around the progress bar, matching what [renderProgress] hit-tests. */
	private fun overProgressBar(mx: Double, my: Double): Boolean {
		val y = progressBarTop ?: return false
		val originX = (-railWidth * (1f - slide)).roundToInt()
		val x = originX + PADDING
		return mx >= x && mx < x + (railWidth - PADDING * 2) &&
			my >= y - PROGRESS_HIT_PAD && my < y + PROGRESS_HEIGHT + PROGRESS_HIT_PAD
	}

	/** Recorded during render so input uses exactly the laid-out position. */
	private var progressBarTop: Int? = null

	/** The title doubles as a link; only worth offering when there's something to open. */
	private fun overTitle(mx: Double, my: Double, track: TrackInfo?): Boolean {
		if (track == null || track.title.isEmpty()) return false
		val y = titleTop ?: return false
		val originX = (-railWidth * (1f - slide)).roundToInt()
		val x = originX + PADDING
		return mx >= x && mx < x + (railWidth - PADDING * 2) &&
			my >= y && my < y + font.lineHeight
	}

	private fun overVolumeBar(mx: Double, my: Double): Boolean {
		val band = volumeBarTop ?: return false
		val y = band + (TOGGLE_SIZE - VOLUME_HEIGHT) / 2
		val originX = (-railWidth * (1f - slide)).roundToInt()
		val x = originX + PADDING + VOLUME_ICON_WIDTH
		return mx >= x && mx < x + volumeBarWidth() &&
			my >= y - VOLUME_HIT_PAD && my < y + VOLUME_HEIGHT + VOLUME_HIT_PAD
	}

	private fun volumeFractionAt(mx: Double): Float {
		val originX = (-railWidth * (1f - slide)).roundToInt()
		val x = originX + PADDING + VOLUME_ICON_WIDTH
		return ((mx - x) / volumeBarWidth()).toFloat().coerceIn(0f, 1f)
	}

	private fun fractionAt(mx: Double): Float {
		val originX = (-railWidth * (1f - slide)).roundToInt()
		val x = originX + PADDING
		val barWidth = railWidth - PADDING * 2
		return ((mx - x) / barWidth).toFloat().coerceIn(0f, 1f)
	}

	private fun beginClose() {
		if (!closing) {
			closing = true
			openedAtMs = System.nanoTime()
		}
	}

	/** Frame-rate independent: driven by wall clock, not tick count. */
	private fun advanceSlide() {
		val elapsed = (System.nanoTime() - openedAtMs) / 1_000_000f
		val raw = (elapsed / SLIDE_MS).coerceIn(0f, 1f)
		slide = if (closing) Accent.smoothstep(1f - raw) else Accent.smoothstep(raw)
	}

	/**
	 * Opens the track in a browser.
	 *
	 * SMTC exposes no track URL — not even for Spotify — so this is a search rather than
	 * a direct link. Routed through Minecraft's own confirm-link screen because the query
	 * is built from metadata some other application supplied, and that shouldn't open a
	 * browser without the player seeing where it goes.
	 */
	private fun openTrack(track: TrackInfo) {
		val query = listOf(track.title, track.artist)
			.filter { it.isNotEmpty() }
			.joinToString(" ")
		if (query.isBlank()) return

		val encoded = URLEncoder.encode(query, StandardCharsets.UTF_8)
		val uri = runCatching { URI("https://open.spotify.com/search/$encoded") }.getOrNull() ?: return
		clickUrlAction(minecraft, this, uri)
	}

	private fun nextRepeat(current: RepeatMode?): RepeatMode = when (current) {
		null, RepeatMode.NONE -> RepeatMode.LIST
		RepeatMode.LIST -> RepeatMode.TRACK
		RepeatMode.TRACK -> RepeatMode.NONE
	}

	private fun truncate(text: String, maxWidth: Int): Component {
		if (font.width(text) <= maxWidth) return Component.literal(text)
		val ellipsis = "..."
		val room = maxWidth - font.width(ellipsis)
		return Component.literal(font.plainSubstrByWidth(text, room) + ellipsis)
	}

	private fun formatTime(ms: Long): String {
		val totalSeconds = (ms / 1000).coerceAtLeast(0)
		val minutes = totalSeconds / 60
		val seconds = totalSeconds % 60
		return if (minutes >= 60) {
			"%d:%02d:%02d".format(minutes / 60, minutes % 60, seconds)
		} else {
			"%d:%02d".format(minutes, seconds)
		}
	}

	/**
	 * SMTC ids are raw app identifiers. They come in three shapes:
	 *
	 *     Spotify.exe                            classic desktop executable
	 *     Helium.NXYZFKH5N5QLK4VHZYCROOE6P4      packaged app + publisher hash
	 *     Microsoft.ZuneMusic_8wekyb3d8bbwe!App  package family name + app id
	 *
	 * Showing any of those verbatim in the panel is noise, so strip them down to the bit
	 * a person would recognise.
	 */
	private fun prettySourceName(sourceId: String): String {
		if (sourceId.isEmpty()) return "Unknown"

		var name = sourceId.substringBefore('!').substringBefore('_').removeSuffix(".exe")

		val segments = name.split('.')
		if (segments.size > 1) {
			val last = segments.last()
			name = when {
				// Trailing publisher hash: a long meaningless run of caps and digits.
				last.length >= 10 && last.all { it.isUpperCase() || it.isDigit() } ->
					segments.dropLast(1).joinToString(".")
				// Reverse-DNS id, e.g. com.squirrel.Discord — the app name is last.
				segments.first().lowercase() in REVERSE_DNS_PREFIXES -> last
				else -> name
			}
		}

		return FRIENDLY_NAMES[name.lowercase()] ?: name.ifEmpty { sourceId }
	}
}
