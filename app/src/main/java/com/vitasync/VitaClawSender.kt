package com.vitasync

import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.Instant

/**
 * Sends one export to VitaClaw: POST {VITACLAW_URL}/api/v1/health/batch with
 * the X-API-Key header. URL and key come from local.properties at build time
 * (vitaclaw.url, vitaclaw.key); local.properties is git-ignored, so neither
 * ever reaches the public repository.
 *
 * The connection goes over Tailscale (WireGuard-encrypted). Plain HTTP is
 * allowed only for *.ts.net names (res/xml/network_security_config.xml).
 */
object VitaClawSender {

    data class Outcome(val httpCode: Int, val summary: String) {
        val ok: Boolean get() = httpCode == 200
        /** Network failure or server error: worth retrying later. */
        val retryable: Boolean get() = httpCode == -1 || httpCode >= 500
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

    // Last result, kept on the phone so the screen can show what the
    // background job did.
    private const val PREFS = "vitaclaw_send"

    fun remember(context: Context, outcome: Outcome, how: String) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putString("last", "${Instant.now()} ($how): ${outcome.summary}")
            .apply()
    }

    fun lastResult(context: Context): String =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getString("last", "no send yet") ?: "no send yet"
}
