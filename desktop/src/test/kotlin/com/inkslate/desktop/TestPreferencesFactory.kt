package com.inkslate.desktop

import java.util.prefs.AbstractPreferences
import java.util.prefs.Preferences
import java.util.prefs.PreferencesFactory

/** Keep native-picker preferences out of the real Windows registry during UI tests. */
class TestPreferencesFactory : PreferencesFactory {
    private val user = MemoryPreferences(null, "")
    private val system = MemoryPreferences(null, "")
    override fun userRoot(): Preferences = user
    override fun systemRoot(): Preferences = system

    private class MemoryPreferences(parent: AbstractPreferences?, name: String) : AbstractPreferences(parent, name) {
        private val values = HashMap<String, String>()
        private val children = HashMap<String, MemoryPreferences>()
        override fun putSpi(key: String, value: String) { values[key] = value }
        override fun getSpi(key: String): String? = values[key]
        override fun removeSpi(key: String) { values.remove(key) }
        override fun removeNodeSpi() { values.clear(); children.clear() }
        override fun keysSpi(): Array<String> = values.keys.toTypedArray()
        override fun childrenNamesSpi(): Array<String> = children.keys.toTypedArray()
        override fun childSpi(name: String): AbstractPreferences = children.getOrPut(name) { MemoryPreferences(this, name) }
        override fun syncSpi() {}
        override fun flushSpi() {}
    }
}
