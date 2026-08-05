package dev.astro.jukeblock.media.smtc

import com.sun.jna.Library
import com.sun.jna.Pointer
import com.sun.jna.ptr.LongByReference

/**
 * Raw JNA binding to `smtc_bridge.dll`. One method per export, nothing clever.
 *
 * Note every string-returning method is typed as [Pointer], never `String`. JNA will
 * happily marshal a `String` for us, but it discards the underlying pointer in the
 * process and we could never free it — a leak on every poll, several times a second.
 * [SmtcBridge] wraps these so callers never see a raw pointer.
 */
internal interface SmtcNative : Library {

	fun jukeblock_abi_version(): Int

	fun jukeblock_get_now_playing(sourceAppId: String?): Pointer?

	fun jukeblock_get_sessions(): Pointer?

	fun jukeblock_free_string(ptr: Pointer?)

	fun jukeblock_get_thumbnail(sourceAppId: String?, outLen: LongByReference): Pointer?

	fun jukeblock_free_bytes(ptr: Pointer?, len: Long)

	fun jukeblock_control(sourceAppId: String?, command: String, arg: Long): Int
}
