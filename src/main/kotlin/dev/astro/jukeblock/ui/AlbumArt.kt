package dev.astro.jukeblock.ui

import com.mojang.blaze3d.platform.NativeImage
import dev.astro.jukeblock.Jukeblock
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
		if (key == loadedKey || key == requestedKey) return

		if (!track.hasThumbnail) {
			// Player has no art for this track; drop the old one so we don't show the
			// previous track's cover next to the new title.
			clear()
			loadedKey = key
			return
		}

		requestedKey = key
		MediaService.requestArtwork { bytes ->
			// Back on the media thread here. Decode is cheap enough to do off-thread,
			// and it keeps image parsing away from the render loop.
			val decoded = decode(bytes)
			Minecraft.getInstance().execute {
				// The user may have skipped tracks while this was in flight.
				if (requestedKey == key) {
					requestedKey = null
					if (decoded != null) {
						apply(key, decoded)
					} else {
						clear()
						loadedKey = key
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

	/** Render thread only: uploads the image and swaps it in. */
	private fun apply(key: String, image: NativeImage) {
		try {
			accent = Accent.extract(image)

			val id = Identifier.fromNamespaceAndPath(
				Jukeblock.MOD_ID,
				"album_art/${counter.getAndIncrement()}",
			)
			// DynamicTexture takes ownership of the image and closes it with the texture.
			val tex = DynamicTexture({ "Jukeblock album art" }, image)
			Minecraft.getInstance().textureManager.register(id, tex)

			releaseCurrent()
			currentTexture = tex
			texture = id
			loadedKey = key
		} catch (e: Exception) {
			Jukeblock.LOGGER.warn("Could not upload album art texture", e)
			image.close()
			clear()
			// Remember the track anyway. clear() resets loadedKey, and without this the
			// next frame sees "not loaded", re-requests, fails again — a native fetch
			// every frame for as long as the track is playing.
			loadedKey = key
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
		accent = Accent.FALLBACK
	}
}
