package dev.astro.jukeblock.ui

import com.mojang.blaze3d.platform.NativeImage
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

/**
 * Pulls an accent colour out of album art, clamped into a band that stays legible on
 * the dark panel.
 *
 * ⚠️ The clamping is the whole point. Raw cover colours are frequently near-black or
 * near-white and become invisible the moment you use them — this is the `ensureReadable`
 * lesson already paid for in playlist.lens.
 * Never use an extracted colour without passing it through [clampForDarkUi].
 *
 * The accent is only ever applied to the progress fill, control highlights and the glow
 * behind the art — never to body text or a full-saturation background.
 */
object Accent {

	/** Panel default when there's no art, or the art has nothing worth extracting. */
	const val FALLBACK = 0xFF53E076.toInt()

	/** Extraction runs on a downsample this wide; ~1k samples is plenty and costs nothing. */
	private const val SAMPLE_GRID = 32

	/** Below this saturation a pixel is grey and tells us nothing about the artwork's hue. */
	private const val MIN_INTERESTING_SATURATION = 0.15f

	private const val SATURATION_MIN = 0.35f
	private const val SATURATION_MAX = 0.85f
	private const val LIGHTNESS_MIN = 0.45f
	private const val LIGHTNESS_MAX = 0.72f

	/**
	 * Picks an accent from [image]. Returns [FALLBACK] for artwork with no usable colour
	 * (greyscale covers, mostly-black photos).
	 *
	 * Called once per track and cached — see [AlbumArt].
	 */
	fun extract(image: NativeImage): Int {
		val width = image.width
		val height = image.height
		if (width <= 0 || height <= 0) return FALLBACK

		// getPixelsABGR is used rather than getPixel because the channel order is in the
		// name; the packed int from getPixel is easy to decode backwards.
		val pixels = try {
			image.pixelsABGR
		} catch (_: Exception) {
			return FALLBACK
		}

		val stepX = max(1, width / SAMPLE_GRID)
		val stepY = max(1, height / SAMPLE_GRID)

		// Weight each sample by its saturation, so one vivid album spine outvotes a large
		// grey background instead of being averaged into mud.
		var weightedR = 0.0
		var weightedG = 0.0
		var weightedB = 0.0
		var totalWeight = 0.0

		var y = 0
		while (y < height) {
			var x = 0
			while (x < width) {
				val abgr = pixels[y * width + x]
				val a = (abgr ushr 24) and 0xFF
				if (a >= 128) {
					val b = (abgr ushr 16) and 0xFF
					val g = (abgr ushr 8) and 0xFF
					val r = abgr and 0xFF

					val saturation = saturationOf(r, g, b)
					if (saturation >= MIN_INTERESTING_SATURATION) {
						val weight = (saturation * saturation).toDouble()
						weightedR += r * weight
						weightedG += g * weight
						weightedB += b * weight
						totalWeight += weight
					}
				}
				x += stepX
			}
			y += stepY
		}

		if (totalWeight <= 0.0) return FALLBACK

		return clampForDarkUi(
			(weightedR / totalWeight).toInt(),
			(weightedG / totalWeight).toInt(),
			(weightedB / totalWeight).toInt(),
		)
	}

	/**
	 * Forces a colour into a saturation/lightness band that reads against the panel.
	 *
	 * Hue is preserved — that's the part that makes the accent feel like it belongs to
	 * the artwork. Saturation and lightness are the parts that make it legible.
	 */
	fun clampForDarkUi(red: Int, green: Int, blue: Int): Int {
		val (h, s, l) = rgbToHsl(red, green, blue)
		val clampedS = s.coerceIn(SATURATION_MIN, SATURATION_MAX)
		val clampedL = l.coerceIn(LIGHTNESS_MIN, LIGHTNESS_MAX)
		return hslToArgb(h, clampedS, clampedL)
	}

	private fun saturationOf(r: Int, g: Int, b: Int): Float {
		val maxC = max(r, max(g, b))
		val minC = min(r, min(g, b))
		if (maxC == 0) return 0f
		return (maxC - minC).toFloat() / maxC
	}

	private fun rgbToHsl(r: Int, g: Int, b: Int): Triple<Float, Float, Float> {
		val rf = r / 255f
		val gf = g / 255f
		val bf = b / 255f
		val maxC = max(rf, max(gf, bf))
		val minC = min(rf, min(gf, bf))
		val delta = maxC - minC
		val l = (maxC + minC) / 2f

		if (delta == 0f) return Triple(0f, 0f, l)

		val s = if (l > 0.5f) delta / (2f - maxC - minC) else delta / (maxC + minC)
		val h = when (maxC) {
			rf -> ((gf - bf) / delta + if (gf < bf) 6f else 0f)
			gf -> ((bf - rf) / delta + 2f)
			else -> ((rf - gf) / delta + 4f)
		} / 6f

		return Triple(h, s, l)
	}

	private fun hslToArgb(h: Float, s: Float, l: Float): Int {
		if (s == 0f) {
			val v = (l * 255f).toInt().coerceIn(0, 255)
			return argb(255, v, v, v)
		}
		val q = if (l < 0.5f) l * (1f + s) else l + s - l * s
		val p = 2f * l - q
		val r = hueToChannel(p, q, h + 1f / 3f)
		val g = hueToChannel(p, q, h)
		val b = hueToChannel(p, q, h - 1f / 3f)
		return argb(
			255,
			(r * 255f).toInt().coerceIn(0, 255),
			(g * 255f).toInt().coerceIn(0, 255),
			(b * 255f).toInt().coerceIn(0, 255),
		)
	}

	private fun hueToChannel(p: Float, q: Float, tRaw: Float): Float {
		var t = tRaw
		if (t < 0f) t += 1f
		if (t > 1f) t -= 1f
		return when {
			t < 1f / 6f -> p + (q - p) * 6f * t
			t < 1f / 2f -> q
			t < 2f / 3f -> p + (q - p) * (2f / 3f - t) * 6f
			else -> p
		}
	}

	fun argb(a: Int, r: Int, g: Int, b: Int): Int =
		(a shl 24) or (r shl 16) or (g shl 8) or b

	/** Same colour at a different opacity — for glows and hover fills. */
	fun withAlpha(color: Int, alpha: Float): Int {
		val a = (alpha.coerceIn(0f, 1f) * 255f).toInt()
		return (color and 0x00FFFFFF) or (a shl 24)
	}

	/** Blends [from] toward [to]; used for hover and press transitions. */
	fun lerpColor(from: Int, to: Int, t: Float): Int {
		val f = t.coerceIn(0f, 1f)
		fun ch(shift: Int): Int {
			val a = (from ushr shift) and 0xFF
			val b = (to ushr shift) and 0xFF
			return (a + (b - a) * f).toInt().coerceIn(0, 255)
		}
		return argb(ch(24), ch(16), ch(8), ch(0))
	}

	/** True when [color] is light enough that dark text should sit on it. */
	fun isLight(color: Int): Boolean = relativeLuminance(color) > 0.42f

	/** WCAG relative luminance, 0 (black) to 1 (white). */
	fun relativeLuminance(color: Int): Float {
		fun channel(v: Int): Float {
			val c = v / 255f
			return if (c <= 0.03928f) c / 12.92f else ((c + 0.055f) / 1.055f).pow(2.4f)
		}
		val r = channel((color ushr 16) and 0xFF)
		val g = channel((color ushr 8) and 0xFF)
		val b = channel(color and 0xFF)
		return 0.2126f * r + 0.7152f * g + 0.0722f * b
	}

	/** WCAG contrast ratio between two colours, 1:1 (identical) to 21:1 (black on white). */
	fun contrastRatio(a: Int, b: Int): Float {
		val la = relativeLuminance(a)
		val lb = relativeLuminance(b)
		val lighter = max(la, lb)
		val darker = min(la, lb)
		return (lighter + 0.05f) / (darker + 0.05f)
	}

	// adaptTo walks HSL and iterates on contrast, and both the panel and the HUD ask for
	// it every frame with inputs that only change on a track change or a config edit.
	private val memoKeys = LongArray(2)
	private val memoValues = IntArray(2)
	private var memoNext = 0

	/**
	 * [adaptTo], memoised. Safe to call from a render loop.
	 *
	 * Two slots, one per caller. A single slot was enough only while the HUD inherited the
	 * panel's colours; once it could be given its own, the two asked different questions on
	 * alternating frames and the cache never hit.
	 */
	fun adaptToCached(accent: Int, background: Int, enabled: Boolean): Int {
		if (!enabled) return accent
		val key = (accent.toLong() shl 32) or (background.toLong() and 0xFFFFFFFFL)
		// A real result always carries alpha, so 0 can only mean "nothing cached yet".
		for (i in memoKeys.indices) {
			if (memoKeys[i] == key && memoValues[i] != 0) return memoValues[i]
		}
		val result = adaptTo(accent, background)
		memoKeys[memoNext] = key
		memoValues[memoNext] = result
		memoNext = (memoNext + 1) % memoKeys.size
		return result
	}

	/**
	 * Nudges [accent] until it stands out against [background].
	 *
	 * Once the panel colour is the user's to choose, an accent taken from album art can
	 * land right on top of it — a red cover playing against a red panel leaves the
	 * progress fill nearly invisible. The hue is kept, because that's what ties the
	 * accent to the artwork; lightness and saturation are what get moved.
	 *
	 * Direction is chosen from the background: go lighter on a dark panel, darker on a
	 * light one, so this works for a white panel as well as the default charcoal.
	 */
	fun adaptTo(accent: Int, background: Int, minContrast: Float = 3.0f): Int {
		if (contrastRatio(accent, background) >= minContrast) return accent

		val (h, s, l) = rgbToHsl(
			(accent ushr 16) and 0xFF,
			(accent ushr 8) and 0xFF,
			accent and 0xFF,
		)
		// A washed-out accent has nowhere to go on the lightness axis, so give it some
		// saturation to work with first.
		val saturation = s.coerceAtLeast(0.45f)
		val goLighter = relativeLuminance(background) < 0.4f

		var lightness = l
		var best = hslToArgb(h, saturation, lightness)
		// Step rather than solve: luminance isn't linear in HSL lightness, and 24 steps
		// covers the whole axis finely enough that nobody could see the difference.
		repeat(24) {
			if (contrastRatio(best, background) >= minContrast) return best
			lightness = if (goLighter) (lightness + 0.035f) else (lightness - 0.035f)
			if (lightness !in 0f..1f) return best
			best = hslToArgb(h, saturation, lightness)
		}
		return best
	}

	/** Smoothstep, for the panel slide. Linear easing looks mechanical at this speed. */
	fun smoothstep(t: Float): Float {
		val x = t.coerceIn(0f, 1f)
		return x * x * (3f - 2f * x)
	}

	internal fun approximatelyEqual(a: Float, b: Float): Boolean = abs(a - b) < 0.001f
}
