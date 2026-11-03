package support

import java.util.prefs.AbstractPreferences
import java.util.prefs.Preferences
import java.util.prefs.PreferencesFactory

/** Test workers must never use desktop preferences (macOS ignores userRoot overrides). */
class InMemoryPreferencesFactory : PreferencesFactory {
    private val user = Node(null, "")
    private val system = Node(null, "")
    override fun userRoot(): Preferences = user
    override fun systemRoot(): Preferences = system

    private class Node(private val parentNode: Node?, name: String) : AbstractPreferences(parentNode, name) {
        private val values = linkedMapOf<String, String>()
        private val children = linkedMapOf<String, Node>()
        override fun putSpi(key: String, value: String) { values[key] = value }
        override fun getSpi(key: String): String? = values[key]
        override fun removeSpi(key: String) { values.remove(key) }
        override fun keysSpi(): Array<String> = values.keys.toTypedArray()
        override fun childrenNamesSpi(): Array<String> = children.keys.toTypedArray()
        override fun childSpi(name: String): AbstractPreferences = children.getOrPut(name) { Node(this, name) }
        override fun removeNodeSpi() { values.clear(); parentNode?.children?.remove(name()) }
        override fun syncSpi() = Unit
        override fun flushSpi() = Unit
    }
}
