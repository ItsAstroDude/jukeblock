package dev.astro.jukeblock.ui

import com.mojang.blaze3d.platform.InputConstants
import net.fabricmc.fabric.api.client.keymapping.v1.KeyMappingHelper
import net.minecraft.client.KeyMapping
import org.lwjgl.glfw.GLFW

/**
 * Keybinds. The panel toggle defaults to `Z`, which is free in vanilla.
 *
 * The global transport binds work without opening the panel — the common case is
 * skipping a track mid-fight, and that shouldn't need a screen (PLAN.md §6.5).
 */
object JukeblockKeys {

	lateinit var toggle: KeyMapping
		private set

	lateinit var playPause: KeyMapping
		private set

	lateinit var next: KeyMapping
		private set

	lateinit var previous: KeyMapping
		private set

	fun register() {
		toggle = bind("toggle", GLFW.GLFW_KEY_Z)
		// Unbound by default: these are handy but not worth silently claiming three more
		// keys on every install.
		playPause = bind("play_pause", InputConstants.UNKNOWN.value)
		next = bind("next", InputConstants.UNKNOWN.value)
		previous = bind("previous", InputConstants.UNKNOWN.value)
	}

	private fun bind(name: String, key: Int): KeyMapping = KeyMappingHelper.registerKeyMapping(
		KeyMapping("key.jukeblock.$name", key, KeyMapping.Category.MISC),
	)

	/**
	 * True when [key] is the panel toggle, so pressing it again closes the panel.
	 *
	 * The screen swallows key input, so the normal keybind tick never fires while the
	 * panel is open and it has to check for itself.
	 */
	fun isToggleKey(key: Int): Boolean {
		if (!::toggle.isInitialized) return false
		val bound = KeyMappingHelper.getBoundKeyOf(toggle)
		return bound.type == InputConstants.Type.KEYSYM && bound.value == key
	}
}
