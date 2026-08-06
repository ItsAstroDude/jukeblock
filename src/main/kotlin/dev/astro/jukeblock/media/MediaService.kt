package dev.astro.jukeblock.media

import dev.astro.jukeblock.Jukeblock
import dev.astro.jukeblock.media.smtc.SmtcSource
import java.nio.file.Path
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Owns the media source and the thread that talks to it.
 *
 * Every native call lands on one dedicated daemon thread. Two reasons: a poll costs a
 * few milliseconds and a transport command blocks on the player's response, neither of
 * which belongs on the render thread; and serialising them means the bridge is never
 * called concurrently. The render thread only ever reads [nowPlaying], a volatile
 * snapshot, and never blocks.
 */
object MediaService {

	/**
	 * How often to ask the bridge what's playing.
	 *
	 * [BACKGROUND] exists for the HUD's pop-up mode: nothing is on screen, but a track
	 * change has to be *noticed* promptly or the toast arrives late.
	 */
	enum class Cadence(val intervalMs: Long) {
		INTERACTIVE(500L),
		BACKGROUND(1_000L),
		IDLE(2_000L),
	}

	/** Session enumeration is ~5x the cost of a poll and changes rarely. */
	private const val SESSION_SCAN_EVERY_MS = 5_000L

	private var source: MediaSource? = null
	private var executor: ScheduledExecutorService? = null
	private val running = AtomicBoolean(false)

	@Volatile
	private var lastSessionScan = 0L

	@Volatile
	private var interval = Cadence.IDLE.intervalMs

	/**
	 * Whether to keep [volume] fresh.
	 *
	 * Enumerating audio sessions opens a handle per process and is by far the most
	 * expensive call the bridge makes, so it only runs while something is actually
	 * showing the slider.
	 */
	@Volatile
	private var trackVolume = false

	/** Latest snapshot. Null when nothing is playing or no source is available. */
	@Volatile
	var nowPlaying: TrackInfo? = null
		private set

	/** Every session the source can see. */
	@Volatile
	var sessions: List<SessionSummary> = emptyList()
		private set

	/**
	 * Player volume 0.0-1.0, or -1 when the player owns no audio session.
	 *
	 * Read on the session-scan cadence rather than every poll: it enumerates every
	 * audio session on the endpoint and opens a handle per process, which is far more
	 * work than a now-playing read and changes far less often.
	 */
	@Volatile
	var volume: Float = -1f
		private set

	/**
	 * The player the user pinned, or null to follow whatever Windows considers current.
	 *
	 * Worth pinning: SMTC's "current session" follows system focus, so a background
	 * browser tab can hijack the panel mid-song.
	 */
	@Volatile
	var pinnedSourceId: String? = null
		private set

	val isAvailable: Boolean get() = source?.isAvailable == true

	val sourceName: String get() = source?.displayName ?: "None"

	fun start(extractDir: Path) {
		if (!running.compareAndSet(false, true)) return

		source = SmtcSource.create(extractDir)
		if (source == null) {
			// Not fatal: the panel shows a "no system source" state instead.
			Jukeblock.LOGGER.info("No media source available; Jukeblock will show a fallback panel.")
			running.set(false)
			return
		}

		executor = Executors.newSingleThreadScheduledExecutor { r ->
			Thread(r, "Jukeblock-Media").apply { isDaemon = true }
		}
		scheduleNext(0L)
	}

	fun stop() {
		if (!running.compareAndSet(true, false)) return
		executor?.shutdownNow()
		executor = null
		nowPlaying = null
		sessions = emptyList()
	}

	/** Sets the poll rate, and whether volume is worth keeping fresh. */
	fun setCadence(cadence: Cadence, volumeVisible: Boolean) {
		interval = cadence.intervalMs
		trackVolume = volumeVisible
	}

	/** Pin to a specific player, or pass null to follow the system's current session. */
	fun pin(sourceId: String?) {
		pinnedSourceId = sourceId
		// Refresh immediately so the UI doesn't show the old player for up to a poll.
		submit { poll() }
	}

	/**
	 * Sends a transport command off-thread.
	 *
	 * Fire-and-forget by design: the caller is a button on the render thread and has
	 * nothing useful to do with the result. The next poll reflects what actually
	 * happened, which is the truth anyway — a player can accept a command and ignore it.
	 */
	fun send(command: MediaCommand) {
		val src = source ?: return
		submit {
			val accepted = src.control(command, pinnedSourceId)
			if (!accepted) {
				Jukeblock.LOGGER.debug("Player declined command: {}", command.wire)
			}
			// Re-read straight away so the UI reacts now rather than at the next tick.
			poll()
		}
	}

	/**
	 * Sets the player's volume, off-thread.
	 *
	 * Publishes the requested value immediately so the slider doesn't snap back while
	 * the native call is still in flight.
	 */
	fun setVolume(value: Float) {
		val src = source ?: return
		val clamped = value.coerceIn(0f, 1f)
		volume = clamped
		submit {
			src.setVolume(clamped, pinnedSourceId)
			volume = src.volume(pinnedSourceId) ?: -1f
		}
	}

	/** Fetches album art off-thread and hands the bytes to [consumer] on that thread. */
	fun requestArtwork(consumer: (ByteArray?) -> Unit) {
		val src = source ?: return consumer(null)
		submit { consumer(src.artwork(pinnedSourceId)) }
	}

	private fun submit(task: () -> Unit) {
		val exec = executor ?: return
		if (exec.isShutdown) return
		try {
			exec.execute { guard(task) }
		} catch (_: Exception) {
			// Racing with stop(); nothing useful to do.
		}
	}

	private fun scheduleNext(delayMs: Long) {
		val exec = executor ?: return
		if (exec.isShutdown) return
		try {
			exec.schedule({
				guard { poll() }
				scheduleNext(interval)
			}, delayMs, TimeUnit.MILLISECONDS)
		} catch (_: Exception) {
			// Racing with stop().
		}
	}

	/**
	 * Nothing on the media thread may throw: an escaping exception would kill the
	 * scheduled loop silently and the panel would freeze on a stale track forever.
	 */
	private inline fun guard(task: () -> Unit) {
		try {
			task()
		} catch (e: Throwable) {
			Jukeblock.LOGGER.error("Media thread task failed", e)
		}
	}

	private fun poll() {
		val src = source ?: return
		val pinned = pinnedSourceId

		var track = src.nowPlaying(pinned)

		// The pinned player closed. Fall back to the system's current session rather
		// than showing an empty panel, and drop the pin so we don't keep missing.
		if (track == null && pinned != null) {
			track = src.nowPlaying(null)
			if (track != null) {
				Jukeblock.LOGGER.debug("Pinned source {} is gone; following the current session.", pinned)
				pinnedSourceId = null
			}
		}
		nowPlaying = track

		val now = System.currentTimeMillis()
		if (now - lastSessionScan >= SESSION_SCAN_EVERY_MS) {
			lastSessionScan = now
			sessions = src.sessions()
			volume = if (trackVolume) src.volume(pinnedSourceId) ?: -1f else -1f
		}
	}
}
