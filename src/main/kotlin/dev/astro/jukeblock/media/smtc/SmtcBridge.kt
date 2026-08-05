package dev.astro.jukeblock.media.smtc

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.sun.jna.Library
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.ptr.LongByReference
import dev.astro.jukeblock.Jukeblock
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.security.MessageDigest

/**
 * Safe Kotlin front for the native SMTC bridge: owns loading, ABI checking, pointer
 * lifetimes and JSON decoding, so nothing above this file touches JNA.
 *
 * Obtain one with [load]; a null result means SMTC isn't usable here and the caller
 * should degrade gracefully rather than treat it as an error.
 */
internal class SmtcBridge private constructor(private val lib: SmtcNative) {

	/** Reads an owned C string out and hands the buffer straight back to Rust. */
	private fun take(ptr: Pointer?): String? {
		if (ptr == null) return null
		return try {
			ptr.getString(0, "UTF-8")
		} finally {
			lib.jukeblock_free_string(ptr)
		}
	}

	private fun takeJson(ptr: Pointer?): JsonObject? {
		val raw = take(ptr) ?: return null
		return try {
			JsonParser.parseString(raw).asJsonObject
		} catch (e: Exception) {
			Jukeblock.LOGGER.warn("Malformed JSON from native bridge: {}", raw, e)
			null
		}
	}

	fun nowPlaying(sourceId: String?): JsonObject? = takeJson(lib.jukeblock_get_now_playing(sourceId))

	fun sessions(): JsonObject? = takeJson(lib.jukeblock_get_sessions())

	fun artwork(sourceId: String?): ByteArray? {
		val len = LongByReference()
		val ptr = lib.jukeblock_get_thumbnail(sourceId, len) ?: return null
		return try {
			val size = len.value
			// Guard the int narrowing: album art is tens of KB, so anything near 2GB
			// means the bridge handed back garbage and we should not allocate on it.
			if (size <= 0 || size > MAX_ARTWORK_BYTES) {
				Jukeblock.LOGGER.warn("Ignoring implausible artwork size: {} bytes", size)
				null
			} else {
				ptr.getByteArray(0, size.toInt())
			}
		} finally {
			lib.jukeblock_free_bytes(ptr, len.value)
		}
	}

	/** Returns true only if the player actually accepted the command. */
	fun control(command: String, sourceId: String?, arg: Long): Boolean =
		lib.jukeblock_control(sourceId, command, arg) == 1

	companion object {
		/** Must match `ABI_VERSION` in `native/smtc-bridge/src/lib.rs`. */
		private const val EXPECTED_ABI = 2

		private const val LIBRARY_NAME = "smtc_bridge"

		/** JNA's platform folder convention, and where the DLL lives in our jar. */
		private const val RESOURCE_PATH = "/win32-x86-64/$LIBRARY_NAME.dll"

		private const val MAX_ARTWORK_BYTES = 64L * 1024 * 1024

		fun isSupportedPlatform(): Boolean =
			System.getProperty("os.name").orEmpty().startsWith("Windows", ignoreCase = true)

		/**
		 * Extracts and loads the bridge, or returns null if it can't be used.
		 *
		 * @param extractDir a mod-owned directory to unpack the DLL into.
		 */
		fun load(extractDir: Path): SmtcBridge? {
			if (!isSupportedPlatform()) {
				Jukeblock.LOGGER.info("Not on Windows — SMTC unavailable, Jukeblock will run without a system source.")
				return null
			}

			return try {
				val dll = extract(extractDir)
				// UTF-8 explicitly: the bridge speaks UTF-8 in both directions, and JNA
				// would otherwise use the platform encoding.
				val options = mapOf(Library.OPTION_STRING_ENCODING to "UTF-8")
				val lib = Native.load(dll.toAbsolutePath().toString(), SmtcNative::class.java, options)

				val abi = lib.jukeblock_abi_version()
				if (abi != EXPECTED_ABI) {
					// Almost certainly a stale extracted DLL from an older Jukeblock.
					Jukeblock.LOGGER.error(
						"Native bridge ABI mismatch: DLL reports {}, expected {}. Refusing to use it.",
						abi, EXPECTED_ABI,
					)
					return null
				}

				Jukeblock.LOGGER.info("Native SMTC bridge loaded (ABI {}).", abi)
				SmtcBridge(lib)
			} catch (e: Throwable) {
				// UnsatisfiedLinkError is a Throwable, not an Exception, and a failure
				// here must never be fatal — the panel just falls back.
				Jukeblock.LOGGER.error("Could not load the native SMTC bridge; system media source disabled.", e)
				null
			}
		}

		/**
		 * Unpacks the DLL next to the game rather than relying on JNA's classpath
		 * extraction, which is unpredictable under Fabric's Knot classloader.
		 *
		 * The filename carries a hash of the contents, so a Jukeblock update never
		 * reuses the previous version's DLL and a half-written file can't be mistaken
		 * for a good one.
		 */
		private fun extract(dir: Path): Path {
			val bytes = SmtcBridge::class.java.getResourceAsStream(RESOURCE_PATH)?.use { it.readBytes() }
				?: throw IllegalStateException("$RESOURCE_PATH missing from the mod jar")

			val hash = MessageDigest.getInstance("SHA-256")
				.digest(bytes)
				.take(8)
				.joinToString("") { "%02x".format(it) }

			Files.createDirectories(dir)
			val target = dir.resolve("$LIBRARY_NAME-$hash.dll")

			if (Files.exists(target) && Files.size(target) == bytes.size.toLong()) {
				return target
			}

			// Write beside the target and move into place, so a crash mid-write can't
			// leave a truncated DLL that later looks valid.
			val tmp = Files.createTempFile(dir, "$LIBRARY_NAME-", ".tmp")
			try {
				Files.write(tmp, bytes)
				Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING)
			} catch (e: Exception) {
				Files.deleteIfExists(tmp)
				// A previous run may already have put a good copy here; if the DLL is
				// currently mapped by this process Windows refuses the overwrite.
				if (!Files.exists(target)) throw e
			}
			return target
		}
	}
}
