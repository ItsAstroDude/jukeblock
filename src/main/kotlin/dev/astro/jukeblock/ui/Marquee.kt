package dev.astro.jukeblock.ui

/**
 * Side-to-side scrolling for text too wide for its box.
 *
 * Shared by the panel and the HUD, keyed by caller-supplied string so each place keeps
 * its own phase. Driven by the wall clock rather than a per-frame step, so text travels
 * at the same speed at 30 fps and at 300.
 */
object Marquee {

	/** Units per second. Slow enough to read, fast enough not to feel stuck. */
	private const val SPEED = 16f

	/** Dwell at each end, expressed as extra travel so it needs no separate state. */
	private const val PAUSE = 28f

	private val phases = HashMap<String, Float>()
	private var lastFrameMs = 0L

	/**
	 * How far to shift the text left, in units. Always within `0..(textWidth - viewWidth)`,
	 * so the caller can draw at `x - offset` inside a scissor rectangle.
	 */
	fun offset(key: String, textWidth: Int, viewWidth: Int): Int {
		val travel = (textWidth - viewWidth).toFloat()
		if (travel <= 0f) return 0

		val now = System.currentTimeMillis()
		// Clamped so a long stall (alt-tab, chunk load) doesn't teleport the text.
		val deltaMs = if (lastFrameMs == 0L) 0L else (now - lastFrameMs).coerceIn(0L, 100L)
		lastFrameMs = now

		val cycle = travel + PAUSE * 2
		val advanced = (phases.getOrDefault(key, 0f) + SPEED * deltaMs / 1000f) % (cycle * 2)
		phases[key] = advanced

		// First half of the loop travels out, second half comes back.
		val phase = if (advanced <= cycle) advanced else cycle * 2 - advanced
		return (phase - PAUSE).coerceIn(0f, travel).toInt()
	}

	/** Drops phases for keys no longer on screen, so the map can't grow forever. */
	fun forgetAllExcept(keep: Set<String>) {
		if (phases.size > 16) phases.keys.retainAll(keep)
	}
}
