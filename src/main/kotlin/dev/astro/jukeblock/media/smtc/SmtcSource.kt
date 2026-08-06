package dev.astro.jukeblock.media.smtc

import com.google.gson.JsonObject
import dev.astro.jukeblock.Jukeblock
import dev.astro.jukeblock.media.Capability
import dev.astro.jukeblock.media.MediaCommand
import dev.astro.jukeblock.media.MediaSource
import dev.astro.jukeblock.media.PlaybackStatus
import dev.astro.jukeblock.media.RepeatMode
import dev.astro.jukeblock.media.SessionSummary
import dev.astro.jukeblock.media.TrackInfo
import java.nio.file.Path

/**
 * The v1.0 media source: whatever Windows itself is playing.
 *
 * Zero auth, zero setup, and it sees every player — Spotify (free accounts included),
 * browser YouTube, VLC, local files. This is the reason Jukeblock exists.
 */
class SmtcSource private constructor(private val bridge: SmtcBridge) : MediaSource {

	override val displayName: String = "System"

	override val isAvailable: Boolean = true

	override fun nowPlaying(sourceId: String?): TrackInfo? {
		val json = bridge.nowPlaying(sourceId) ?: return null
		if (!json.bool("ok")) {
			Jukeblock.LOGGER.debug("SMTC read failed: {}", json.str("error"))
			return null
		}
		if (!json.bool("active")) return null
		return json.toTrackInfo()
	}

	override fun sessions(): List<SessionSummary> {
		val json = bridge.sessions() ?: return emptyList()
		if (!json.bool("ok")) return emptyList()
		val array = json.getAsJsonArray("sessions") ?: return emptyList()

		return array.mapNotNull { element ->
			val o = element as? JsonObject ?: return@mapNotNull null
			SessionSummary(
				sourceId = o.str("sourceAppId") ?: return@mapNotNull null,
				title = o.str("title").orEmpty(),
				artist = o.str("artist").orEmpty(),
				status = PlaybackStatus.parse(o.str("status")),
				isCurrent = o.bool("isCurrent"),
			)
		}
	}

	override fun artwork(sourceId: String?): ByteArray? = bridge.artwork(sourceId)

	override fun control(command: MediaCommand, sourceId: String?): Boolean =
		bridge.control(command.wire, sourceId, command.arg)

	override fun volume(sourceId: String?): Float? = bridge.volume(sourceId)

	override fun setVolume(value: Float, sourceId: String?): Boolean =
		bridge.setVolume(sourceId, value)

	// --- JSON decoding ---------------------------------------------------------
	// Every field is read defensively. Players fill in wildly varying subsets of the
	// SMTC metadata, and a browser tab missing an album must not break the panel.

	private fun JsonObject.toTrackInfo() = TrackInfo(
		sourceId = str("sourceAppId").orEmpty(),
		title = str("title").orEmpty(),
		artist = str("artist").orEmpty(),
		album = str("album").orEmpty(),
		albumArtist = str("albumArtist").orEmpty(),
		trackNumber = int("trackNumber"),
		status = PlaybackStatus.parse(str("status")),
		positionMs = long("positionMs"),
		durationMs = long("durationMs"),
		lastUpdatedUnixMs = long("lastUpdatedUnixMs"),
		shuffle = boolOrNull("shuffle"),
		repeat = RepeatMode.parse(str("repeat")),
		hasThumbnail = bool("hasThumbnail"),
		capabilities = getAsJsonObject("caps").toCapabilities(),
	)

	private fun JsonObject?.toCapabilities(): Set<Capability> {
		if (this == null) return emptySet()
		return buildSet {
			if (bool("play")) add(Capability.PLAY)
			if (bool("pause")) add(Capability.PAUSE)
			if (bool("stop")) add(Capability.STOP)
			if (bool("next")) add(Capability.NEXT)
			if (bool("previous")) add(Capability.PREVIOUS)
			if (bool("seek")) add(Capability.SEEK)
			if (bool("shuffle")) add(Capability.SHUFFLE)
			if (bool("repeat")) add(Capability.REPEAT)
		}
	}

	private fun JsonObject.str(key: String): String? =
		get(key)?.takeIf { it.isJsonPrimitive }?.asString

	private fun JsonObject.bool(key: String): Boolean =
		get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean ?: false

	private fun JsonObject.boolOrNull(key: String): Boolean? =
		get(key)?.takeIf { it.isJsonPrimitive }?.asBoolean

	private fun JsonObject.int(key: String): Int =
		get(key)?.takeIf { it.isJsonPrimitive }?.asInt ?: 0

	private fun JsonObject.long(key: String): Long =
		get(key)?.takeIf { it.isJsonPrimitive }?.asLong ?: 0L

	companion object {
		/**
		 * Loads the native bridge and wraps it, or returns null when SMTC isn't
		 * available (not Windows, or the DLL wouldn't load). Callers must treat null
		 * as "no system source", not as an error.
		 */
		fun create(extractDir: Path): SmtcSource? = SmtcBridge.load(extractDir)?.let(::SmtcSource)
	}
}
