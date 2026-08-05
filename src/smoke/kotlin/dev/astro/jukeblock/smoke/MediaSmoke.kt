package dev.astro.jukeblock.smoke

import dev.astro.jukeblock.media.MediaCommand
import dev.astro.jukeblock.media.MediaService
import java.nio.file.Path

/**
 * Exercises the whole Kotlin media layer from a plain JVM — no Minecraft.
 *
 * The panel is built on top of [MediaService], so being able to prove the service works
 * (and to reproduce a user's "it shows the wrong player" report) without a 60-second
 * game launch is worth keeping around.
 *
 *     ./gradlew smoke
 */
object MediaSmoke {

	@JvmStatic
	fun main(args: Array<String>) {
		val dir = Path.of(System.getProperty("java.io.tmpdir"), "jukeblock-smoke")
		println("extracting native bridge to $dir")

		MediaService.start(dir)
		if (!MediaService.isAvailable) {
			println("FAIL: no media source available")
			return
		}
		MediaService.setActive(true)
		println("source: ${MediaService.sourceName}")
		println()

		// The poller runs on its own thread; give it a moment to publish a snapshot.
		Thread.sleep(400)

		println("sessions:")
		MediaService.sessions.forEach {
			val mark = if (it.isCurrent) "*" else " "
			println("  $mark ${it.sourceId.padEnd(34)} ${it.status.name.padEnd(8)} ${it.title} — ${it.artist}")
		}
		println()

		val track = MediaService.nowPlaying
		if (track == null) {
			println("nothing playing")
		} else {
			println("now playing:")
			println("  ${track.title}")
			println("  ${track.artist}${if (track.album.isNotEmpty()) "  ·  ${track.album}" else ""}")
			println("  ${track.status}  ${fmt(track.positionNowMs())} / ${fmt(track.durationMs)}  (${(track.progress * 100).toInt()}%)")
			println("  shuffle=${track.shuffle}  repeat=${track.repeat}  art=${track.hasThumbnail}")
			println("  can: ${track.capabilities.joinToString(", ") { it.name.lowercase() }}")
			println("  trackKey: ${track.trackKey}")
		}
		println()

		// Position interpolation: SMTC only reports on events, so the panel extrapolates
		// between polls. If this doesn't advance, the progress bar will visibly stutter.
		println("interpolated position over 2s:")
		repeat(4) {
			val t = MediaService.nowPlaying
			println("  ${fmt(t?.positionNowMs() ?: 0)}   raw=${fmt(t?.positionMs ?: 0)}")
			Thread.sleep(500)
		}
		println()

		// Artwork travels the same off-thread path the panel will use.
		val latch = java.util.concurrent.CountDownLatch(1)
		MediaService.requestArtwork { bytes ->
			println("artwork: ${bytes?.size ?: 0} bytes ${bytes?.let(::sniff) ?: ""}")
			latch.countDown()
		}
		latch.await(3, java.util.concurrent.TimeUnit.SECONDS)

		// Pinning: prove we can read a player that isn't the system's current session.
		val other = MediaService.sessions.firstOrNull { !it.isCurrent }
		if (other != null) {
			println()
			println("pinning to ${other.sourceId}")
			MediaService.pin(other.sourceId)
			Thread.sleep(400)
			val pinned = MediaService.nowPlaying
			println("  -> ${pinned?.title} — ${pinned?.artist}  [${pinned?.sourceId}]")
			MediaService.pin(null)
		}

		// Deliberately not sending a real transport command: this is a diagnostic, and
		// it should never touch what the user is actually listening to.
		println()
		println("declined-command probe: ")
		MediaService.send(MediaCommand.Seek(-1))

		Thread.sleep(300)
		MediaService.stop()
		println()
		println("SMOKE: OK")
	}

	private fun fmt(ms: Long): String {
		val total = ms / 1000
		return "%d:%02d".format(total / 60, total % 60)
	}

	private fun sniff(b: ByteArray): String = when {
		b.size > 8 && b[0].toInt() and 0xFF == 0x89 && b[1].toInt() == 'P'.code -> "(PNG)"
		b.size > 3 && b[0].toInt() and 0xFF == 0xFF && b[1].toInt() and 0xFF == 0xD8 -> "(JPG)"
		else -> "(unknown)"
	}
}
