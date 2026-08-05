package dev.astro.jukeblock.ui

import com.mojang.blaze3d.platform.NativeImage
import com.mojang.blaze3d.systems.RenderSystem
import com.mojang.blaze3d.textures.FilterMode
import dev.astro.jukeblock.Jukeblock
import dev.astro.jukeblock.JukeblockConfig
import dev.astro.jukeblock.media.MediaService
import dev.astro.jukeblock.media.TrackInfo
import net.minecraft.client.Minecraft
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import java.util.concurrent.atomic.AtomicInteger

/**
 * Turns SMTC's album-art bytes into a Minecraft texture, one per track.
 *
 * Fetching and decoding happen on the media thread; the GPU upload has to happen on the
 * render thread, so the decoded image is handed back through [Minecraft.execute].
 *
 * Only one texture is alive at a time. Album art arrives at whatever size the player
 * chose (Spotify sends 640x640) and holding a history of them would be a real leak, so
 * the previous texture is released as soon as a new track's art lands.
 */
object AlbumArt {

	/** How many times to look again for a late thumbnail after a track change. */
	private const val MAX_RECHECKS = 4

	private const val RECHECK_INTERVAL_MS = 1_500L

	/** Distinct texture ids, because reusing one id while the old texture is bound flickers. */
	private val counter = AtomicInteger()

	/** The track whose art we have, or are fetching. Null means nothing loaded. */
	private var loadedKey: String? = null

	/** Guards against firing a second fetch for the same track while one is in flight. */
	private var requestedKey: String? = null

	var texture: Identifier? = null
		private set

	private var currentTexture: DynamicTexture? = null

	/** Accent for the current art, already clamped for legibility. */
	var accent: Int = Accent.FALLBACK
		private set

	/**
	 * Source dimensions of the loaded art.
	 *
	 * Album covers are square but browser thumbnails are 16:9, so the panel needs these
	 * to letterbox rather than stretch — a YouTube frame squeezed into a square box
	 * looks visibly wrong.
	 */
	var artWidth = 0
		private set

	var artHeight = 0
		private set

	/**
	 * Content hash of the loaded bytes, so a re-check can tell "the player finally
	 * updated its thumbnail" from "same image again".
	 */
	private var loadedHash = 0

	private var loadedAtMs = 0L
	private var recheckCount = 0

	/**
	 * Ensures the art for [track] is loaded, fetching it if the track changed.
	 *
	 * Safe to call every frame — it's a string compare in the common case.
	 */
	fun sync(track: TrackInfo?) {
		if (track == null) {
			clear()
			return
		}

		val key = track.trackKey
		if (key == requestedKey) return
		if (key == loadedKey) {
			recheckStaleArtwork(key)
			return
		}

		if (!track.hasThumbnail) {
			// Player has no art for this track; drop the old one so we don't show the
			// previous track's cover next to the new title.
			clear()
			loadedKey = key
			return
		}

		recheckCount = 0
		fetch(key)
	}

	/**
	 * Re-fetches shortly after a track change, in case the player was still catching up.
	 *
	 * Browsers update the SMTC title the moment a video changes but publish the new
	 * thumbnail a beat later. Fetching once on the title change therefore caches the
	 * *previous* video's image against the new track and keeps it until the track
	 * changes again — which is exactly the "it takes it from old vids" symptom.
	 *
	 * A handful of re-checks, comparing content hashes, costs a few native calls per
	 * track and only swaps the texture if the bytes actually changed.
	 */
	private fun recheckStaleArtwork(key: String) {
		if (recheckCount >= MAX_RECHECKS) return
		val now = System.currentTimeMillis()
		if (now - loadedAtMs < RECHECK_INTERVAL_MS) return

		recheckCount++
		loadedAtMs = now
		fetch(key)
	}

	private fun fetch(key: String) {
		requestedKey = key
		MediaService.requestArtwork { bytes ->
			// Still on the media thread. Both the decode and the accent extraction happen
			// here on purpose: extraction walks the image's whole pixel array, which for
			// Spotify's 640x640 covers is 1.6 MB, and doing that on the render thread put
			// a visible hitch right at the moment the panel slides in.
			val hash = bytes?.contentHashCode() ?: 0

			// Nothing changed since the last look — the common case for a re-check, and
			// re-uploading an identical texture would flicker for no reason.
			if (bytes != null && hash == loadedHash && key == loadedKey) {
				requestedKey = null
				return@requestArtwork
			}

			val decoded = decode(bytes)
			val extractedAccent = decoded?.let {
				try {
					Accent.extract(it)
				} catch (e: Exception) {
					Jukeblock.LOGGER.debug("Accent extraction failed", e)
					Accent.FALLBACK
				}
			} ?: Accent.FALLBACK

			Minecraft.getInstance().execute {
				// The user may have skipped tracks while this was in flight.
				if (requestedKey == key) {
					requestedKey = null
					if (decoded != null) {
						apply(key, decoded, extractedAccent, hash)
					} else if (key != loadedKey) {
						clear()
						loadedKey = key
						loadedAtMs = System.currentTimeMillis()
					}
				} else {
					decoded?.close()
				}
			}
		}
	}

	private fun decode(bytes: ByteArray?): NativeImage? {
		if (bytes == null || bytes.isEmpty()) return null
		return try {
			// SMTC hands back whatever the player supplied — usually PNG, sometimes JPEG.
			// NativeImage.read sniffs the format itself.
			NativeImage.read(bytes)
		} catch (e: Exception) {
			Jukeblock.LOGGER.debug("Could not decode album art ({} bytes)", bytes.size, e)
			null
		}
	}

	/** Render thread only: uploads the image and swaps it in. Nothing heavy happens here. */
	private fun apply(key: String, image: NativeImage, extractedAccent: Int, hash: Int) {
		try {
			accent = extractedAccent

			val id = Identifier.fromNamespaceAndPath(
				Jukeblock.MOD_ID,
				"album_art/${counter.getAndIncrement()}",
			)
			// DynamicTexture takes ownership of the image and closes it with the texture.
			val tex = if (JukeblockConfig.current.smoothAlbumArt) {
				SmoothTexture(image)
			} else {
				DynamicTexture({ "Jukeblock album art" }, image)
			}
			Minecraft.getInstance().textureManager.register(id, tex)

			releaseCurrent()
			currentTexture = tex
			texture = id
			loadedKey = key
			loadedHash = hash
			loadedAtMs = System.currentTimeMillis()
			artWidth = image.width
			artHeight = image.height
		} catch (e: Exception) {
			Jukeblock.LOGGER.warn("Could not upload album art texture", e)
			image.close()
			clear()
			// Remember the track anyway. clear() resets loadedKey, and without this the
			// next frame sees "not loaded", re-requests, fails again — a native fetch
			// every frame for as long as the track is playing.
			loadedKey = key
			loadedAtMs = System.currentTimeMillis()
		}
	}

	/** Render thread only. */
	private fun releaseCurrent() {
		val id = texture
		if (id != null) {
			try {
				Minecraft.getInstance().textureManager.release(id)
			} catch (e: Exception) {
				Jukeblock.LOGGER.debug("Failed releasing album art texture", e)
			}
		}
		currentTexture = null
		texture = null
	}

	fun clear() {
		releaseCurrent()
		loadedKey = null
		requestedKey = null
		loadedHash = 0
		artWidth = 0
		artHeight = 0
		accent = Accent.FALLBACK
	}

	/**
	 * Album art with linear filtering instead of Minecraft's default nearest-neighbour.
	 *
	 * SMTC hands out a 300x300 thumbnail whatever the player's real cover resolution is,
	 * and the panel draws it larger than that — ~1.4x at GUI scale 4. Nearest-neighbour
	 * upscaling by a non-integer factor duplicates some pixel rows and not others, which
	 * is the blockiness you see on a photo. Nearest is right for Minecraft's own pixel-art
	 * textures and wrong for a photograph.
	 *
	 * `DynamicTexture` hardcodes NEAREST in its constructor, so the sampler is swapped
	 * afterwards. Clamp-to-edge rather than repeat, so filtering at the border can't pull
	 * in pixels from the opposite side.
	 */
	private class SmoothTexture(image: NativeImage) : DynamicTexture({ "Jukeblock album art" }, image) {
		init {
			sampler = RenderSystem.getSamplerCache().getClampToEdge(FilterMode.LINEAR)
		}
	}
}
