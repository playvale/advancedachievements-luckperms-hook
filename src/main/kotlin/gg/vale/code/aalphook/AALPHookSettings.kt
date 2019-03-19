package gg.vale.code.aalphook

import com.algorithmjunkie.mc.konfig.system.bukkit.BukkitKonfig

class AALPHookSettings(private val plugin: AALPHookPlugin) : BukkitKonfig(plugin, "settings.yml", plugin.dataFolder) {
    fun isDebug(): Boolean {
        return getBoolean("debug")
    }

    fun setDebug(debug: Boolean) {
        return set("debug", debug)
    }
}