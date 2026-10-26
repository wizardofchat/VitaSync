package com.vitasync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Duration
import java.time.Instant

/**
 * Sends one export to VitaClaw: POST {VITACLAW_URL}/api/v1/health/batch with
 * the X-API-Key header. URL and key come from local.properties at build time
 * (vitaclaw.url, vitaclaw.key); local.properties is git-ignored.
 *
 * Also owns on-device state kept between sends:
 *   - per-record-type cursor advanced only on a confirmed 2xx response, so
 *     a run that fails to deliver loses no ground;
 *   - last-success record (time, how, summary), always kept;
 *   - last-error record, cleared on the next success — the UI uses this to
 *     show "retrying, last error: ..." under the last successful send.
 *
 * The connection goes over Tailscale (WireGuard-encrypted). Plain HTTP is
 * allowed only for *.ts.net names (res/xml/network_security_config.xml).
 */
object VitaClawSender {

    data class Outcome(val httpCode: Int, val summary: String) {
        val ok: Boolean get() = httpCode == 200
    }

    fun isConfigured(): Boolean =
        BuildConfig.VITACLAW_URL.isNotBlank() && BuildConfig.VITACLAW_KEY.isNotBlank()

    suspend fun send(export: JSONObject): Outcome = withContext(Dispatchers.IO) {
        if (!isConfigured()) {
            return@withContext Outcome(-2, "VitaClaw URL or key missing from local.properties")
        }
        val url = URL(BuildConfig.VITACLAW_URL.trimEnd('/') + "/api/v1/health/batch")
        var conn: HttpURLConnection? = null
        try {
            conn = url.openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.connectTimeout = 15_000
            conn.readTimeout = 60_000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("X-API-Key", BuildConfig.VITACLAW_KEY)
            conn.outputStream.use { it.write(export.toString().toByteArray(Charsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val body = stream?.bufferedReader()?.use { it.readText() } ?: ""
            Outcome(code, if (code == 200) summarise(body) else "HTTP $code")
        } catch (e: Exception) {
            Outcome(-1, "not reached: ${e.javaClass.simpleName}")
        } finally {
            conn?.disconnect()
        }
    }

    /** Counts only, from the receiver's reply. */
    private fun summarise(body: String): String = try {
        val reply = JSONObject(body)
        fun total(name: String): Int {
            val obj = reply.optJSONObject(name) ?: return 0
            return obj.keys().asSequence().sumOf { obj.optInt(it) }
        }
        "received ${reply.optInt("received")}, accepted ${reply.optInt("accepted")}, " +
            "duplicate ${reply.optInt("duplicate")}, rejected ${total("rejected")}, " +
            "unsupported ${total("unsupported")}"
    } catch (e: Exception) {
        "HTTP 200, reply not readable"
    }

    private const val PREFS = "vitaclaw_send"
    const val DEFAULT_LOOKBACK_DAYS = 30L

    fun recordAttempt(context: Context, outcome: Outcome, how: String) {
        val now = Instant.now()
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        if (outcome.ok) {
            edit.putString("success_at", now.toString())
                .putString("success_summary", outcome.summary)
                .putString("success_how", how)
                .remove("error_summary")
                .remove("error_at")
        } else {
            edit.putString("error_summary", outcome.summary)
                .putString("error_at", now.toString())
        }
        edit.apply()
    }

    fun lastSendLine(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val successAt = prefs.getString("success_at", null)
        val successSummary = prefs.getString("success_summary", null)
        val successHow = prefs.getString("success_how", null)
        val error = prefs.getString("error_summary", null)
        val base = if (successAt != null && successSummary != null)
            "$successAt (${successHow ?: "?"}): $successSummary"
        else "no send yet"
        return if (error != null) "$base — retrying, last error: $error" else base
    }

    fun cursorFor(context: Context, recordType: String, now: Instant = Instant.now()): Instant {
        val fallback = now.minus(Duration.ofDays(DEFAULT_LOOKBACK_DAYS))
        val stored = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("cursor.$recordType", null) ?: return fallback
        return try { Instant.parse(stored) } catch (_: Exception) { fallback }
    }

    fun advanceCursors(context: Context, windowEnds: Map<String, Instant>) {
        if (windowEnds.isEmpty()) return
        val edit = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
        windowEnds.forEach { (type, end) -> edit.putString("cursor.$type", end.toString()) }
        edit.apply()
    }
}
