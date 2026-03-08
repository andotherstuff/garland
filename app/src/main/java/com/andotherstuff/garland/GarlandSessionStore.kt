package com.andotherstuff.garland

import android.content.Context

class GarlandSessionStore(context: Context) {
    private val prefs = context.getSharedPreferences("garland-session", Context.MODE_PRIVATE)

    fun savePrivateKeyHex(privateKeyHex: String) {
        val editor = prefs.edit()
        normalizePrivateKeyHex(privateKeyHex)
            ?.let { editor.putString(KEY_PRIVATE_KEY_HEX, it) }
            ?: editor.remove(KEY_PRIVATE_KEY_HEX)
        editor.apply()
    }

    fun loadPrivateKeyHex(): String? = normalizePrivateKeyHex(prefs.getString(KEY_PRIVATE_KEY_HEX, null))

    fun clearPrivateKeyHex() {
        prefs.edit().remove(KEY_PRIVATE_KEY_HEX).apply()
    }

    fun saveBlossomServers(servers: List<String>) {
        prefs.edit()
            .putString(KEY_SERVER_ONE, servers.getOrNull(0)?.trim())
            .putString(KEY_SERVER_TWO, servers.getOrNull(1)?.trim())
            .putString(KEY_SERVER_THREE, servers.getOrNull(2)?.trim())
            .apply()
    }

    fun saveRelays(relays: List<String>) {
        prefs.edit()
            .putString(KEY_RELAY_ONE, relays.getOrNull(0)?.trim())
            .putString(KEY_RELAY_TWO, relays.getOrNull(1)?.trim())
            .putString(KEY_RELAY_THREE, relays.getOrNull(2)?.trim())
            .apply()
    }

    fun loadBlossomServers(): List<String> {
        val fallback = GarlandConfig.defaults.blossomServers
        return listOf(
            prefs.getString(KEY_SERVER_ONE, fallback[0]).orEmpty(),
            prefs.getString(KEY_SERVER_TWO, fallback[1]).orEmpty(),
            prefs.getString(KEY_SERVER_THREE, fallback[2]).orEmpty(),
        )
    }

    fun loadRelays(): List<String> {
        val fallback = GarlandConfig.defaults.relays
        return listOf(
            prefs.getString(KEY_RELAY_ONE, fallback[0]).orEmpty(),
            prefs.getString(KEY_RELAY_TWO, fallback[1]).orEmpty(),
            prefs.getString(KEY_RELAY_THREE, fallback[2]).orEmpty(),
        )
    }

    fun resolvedBlossomServers(): List<String> {
        return GarlandConfig.normalizeConfiguredEndpoints(loadBlossomServers(), GarlandConfig.defaults.blossomServers)
    }

    fun resolvedRelays(): List<String> {
        return GarlandConfig.normalizeConfiguredEndpoints(loadRelays(), GarlandConfig.defaults.relays)
    }

    companion object {
        internal fun normalizePrivateKeyHex(privateKeyHex: String?): String? {
            return privateKeyHex?.trim()?.takeIf { it.isNotEmpty() }
        }

        private const val KEY_PRIVATE_KEY_HEX = "private_key_hex"
        private const val KEY_SERVER_ONE = "server_one"
        private const val KEY_SERVER_TWO = "server_two"
        private const val KEY_SERVER_THREE = "server_three"
        private const val KEY_RELAY_ONE = "relay_one"
        private const val KEY_RELAY_TWO = "relay_two"
        private const val KEY_RELAY_THREE = "relay_three"
    }
}
