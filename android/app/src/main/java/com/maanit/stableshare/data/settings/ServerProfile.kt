package com.maanit.stableshare.data.settings

import okhttp3.HttpUrl.Companion.toHttpUrlOrNull

/** Where the app sends transfers. Only [HOSTED] and [EMULATOR] have fixed URLs. */
enum class ServerProfile { HOSTED, EMULATOR, LAN, CUSTOM }

/** A server selection: the profile plus the value it needs (LAN host and port, or the custom URL). */
data class ServerChoice(
    val profile: ServerProfile,
    val lanHost: String = "",
    val lanPort: Int = ServerProfiles.DEFAULT_LAN_PORT,
    val customUrl: String = "",
)

/** Server URLs, normalisation, and the mapping from a saved URL to a profile. Pure. */
object ServerProfiles {
    /** The only place the hosted server's URL lives. */
    const val HOSTED_URL = "https://stableshare.onrender.com"
    const val EMULATOR_URL = "http://10.0.2.2:8080"
    const val DEFAULT_LAN_PORT = 8080
    private val PORTS = 1..65535

    private val HOSTED_HOST = HOSTED_URL.toHttpUrlOrNull()!!.host

    /** The base URL [choice] points at, or null if its LAN host or custom URL is not usable. */
    fun urlOf(choice: ServerChoice): String? = when (choice.profile) {
        ServerProfile.HOSTED -> HOSTED_URL
        ServerProfile.EMULATOR -> EMULATOR_URL
        ServerProfile.LAN -> lanUrl(choice.lanHost, choice.lanPort)
        ServerProfile.CUSTOM -> normalizeServerUrl(choice.customUrl)
    }

    /**
     * Trims, rejects whitespace inside, adds http:// when no scheme is given, drops a trailing
     * slash. Null if the result is not an http(s) URL with a host and no query or fragment.
     */
    fun normalizeServerUrl(input: String): String? {
        val trimmed = input.trim()
        if (trimmed.isEmpty() || trimmed.any { it.isWhitespace() }) return null
        val withScheme = if ("://" in trimmed) trimmed else "http://$trimmed"
        val url = withScheme.toHttpUrlOrNull() ?: return null
        if (url.host.isEmpty() || url.query != null || url.fragment != null) return null
        return url.toString().trimEnd('/')
    }

    /** `http://<host>:<port>`; null if [host] is blank or more than a host, or [port] is out of range. */
    fun lanUrl(host: String, port: Int): String? {
        val h = host.trim()
        if (h.isEmpty() || port !in PORTS || h.any { it.isWhitespace() || it in "/:@?#" }) return null
        return normalizeServerUrl("http://$h:$port")
    }

    /**
     * Which profile a saved URL belongs to (the first-run migration, and URLs typed as a whole).
     * The hosted host over any scheme is HOSTED; exactly [EMULATOR_URL] is EMULATOR; a plain
     * `http://<private IPv4>[:port]` is LAN; anything else is CUSTOM. Null if [url] is invalid.
     */
    fun classify(url: String): ServerChoice? {
        val normalized = normalizeServerUrl(url) ?: return null
        val parsed = normalized.toHttpUrlOrNull() ?: return null
        val bare = parsed.encodedPath == "/"
        return when {
            parsed.host == HOSTED_HOST -> ServerChoice(ServerProfile.HOSTED)
            normalized == EMULATOR_URL -> ServerChoice(ServerProfile.EMULATOR)
            parsed.scheme == "http" && bare && isPrivateIpv4(parsed.host) ->
                ServerChoice(ServerProfile.LAN, lanHost = parsed.host, lanPort = parsed.port)
            else -> ServerChoice(ServerProfile.CUSTOM, customUrl = normalized)
        }
    }

    /** 10.0.0.0/8, 172.16.0.0/12 or 192.168.0.0/16. */
    fun isPrivateIpv4(host: String): Boolean {
        val parts = host.split('.')
        if (parts.size != 4) return false
        val octets = parts.map { p -> p.toIntOrNull()?.takeIf { p.length in 1..3 && it in 0..255 } ?: return false }
        val (a, b) = octets
        return a == 10 || (a == 172 && b in 16..31) || (a == 192 && b == 168)
    }
}
