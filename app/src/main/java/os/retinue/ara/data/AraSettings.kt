package os.retinue.ara.data

/**
 * User-editable runtime configuration. Defaults mirror the Signal Gateway's
 * environment defaults (see `scripts/signal-gateway.py`): 3 s initial poll,
 * ×2 backoff to a 300 s ceiling, 30 s per-request timeout, 3600 s overall.
 *
 * The Retinue endpoint is the one environment-specific value that cannot be
 * derived from the gateway (it runs on the internal compose network), so it is
 * exposed here as the single documented configuration mechanism — no baked-in
 * default host, no alternative protocol.
 */
data class AraSettings(
    val baseUrl: String = "",
    val messagePath: String = "/message",
    val pollIntervalMs: Long = 3_000,
    val pollIntervalMaxMs: Long = 300_000,
    val pollBackoff: Double = 2.0,
    val requestTimeoutMs: Long = 30_000,
    val overallJobTimeoutMs: Long = 3_600_000,
    val maxConsecutivePollFailures: Int = 10,
) {
    /** The absolute URL a prompt is POSTed to. Empty when unconfigured. */
    fun messageUrl(): String? {
        val base = baseUrl.trim().trimEnd('/')
        if (base.isEmpty()) return null
        val path = if (messagePath.startsWith("/")) messagePath else "/$messagePath"
        return base + path
    }
}
