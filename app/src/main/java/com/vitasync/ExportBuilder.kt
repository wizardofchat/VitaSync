package com.vitasync

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import androidx.health.connect.client.records.metadata.Device
import androidx.health.connect.client.records.metadata.Metadata
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

/**
 * Builds the v1 export (schema_version 1): heart_rate, sleep_session,
 * oxygen_saturation, exercise_session. Record shape unchanged from the
 * 9 October 2026 export — VitaClaw dedups by source_record_id.
 *
 * Each type's read window is:
 *   [cursor - 48h, min(now, cursor + 7d)]
 *
 * The 48h backward overlap catches readings that watches (e.g. Garmin
 * Connect) write to Health Connect hours after their own timestamp; those
 * records would otherwise be skipped forever once the cursor passed them.
 * Duplicates in the overlap are dropped by VitaClaw.
 *
 * The 7-day forward cap chunks a long catch-up (first run from 30 days
 * behind, or a Proton-VPN gap) into bites small enough to send reliably on
 * one connection. The caller advances the cursor to the chunk's actual end,
 * not to now, so a 30-day backlog clears in about five sends.
 *
 * A type whose permission is not granted is skipped (contract rule 6:
 * partial batches are valid) and is left out of windowEnds, so its cursor
 * stays where it was.
 */
object ExportBuilder {

    const val MAX_CHUNK_DAYS = 7L
    const val OVERLAP_HOURS = 48L
    val TYPES = listOf("heart_rate", "sleep_session", "oxygen_saturation", "exercise_session")

    data class Result(val json: JSONObject, val windowEnds: Map<String, Instant>)

    private fun base(type: String, app: String, id: String, recordedAt: Instant) =
        JSONObject().apply {
            put("source", "health_connect")
            put("record_type", type)
            put("source_app", app)
            put("source_record_id", id)
            put("data_recorded_at", recordedAt.toString())
            put("data_ingested_at", Instant.now().toString())
        }

    private fun deviceType(type: Int): String = when (type) {
        Device.TYPE_WATCH -> "watch"
        Device.TYPE_PHONE -> "phone"
        Device.TYPE_SCALE -> "scale"
        Device.TYPE_RING -> "ring"
        Device.TYPE_HEAD_MOUNTED -> "head_mounted"
        Device.TYPE_FITNESS_BAND -> "fitness_band"
        Device.TYPE_CHEST_STRAP -> "chest_strap"
        Device.TYPE_SMART_DISPLAY -> "smart_display"
        else -> "unknown"
    }

    private fun JSONObject.withDevice(metadata: Metadata): JSONObject {
        val device = metadata.device ?: return this
        if (device.manufacturer == null && device.model == null && device.type == Device.TYPE_UNKNOWN) return this
        put("device", JSONObject().apply {
            put("manufacturer", device.manufacturer ?: JSONObject.NULL)
            put("model", device.model ?: JSONObject.NULL)
            put("type", deviceType(device.type))
        })
        return this
    }

    private data class Window(val readStart: Instant, val readEnd: Instant)

    private fun window(cursor: Instant, now: Instant, maxChunkDays: Long, overlapHours: Long): Window {
        val readStart = cursor.minus(Duration.ofHours(overlapHours))
        val chunkEnd = cursor.plus(Duration.ofDays(maxChunkDays))
        val readEnd = if (chunkEnd.isBefore(now)) chunkEnd else now
        return Window(readStart, readEnd)
    }

    suspend fun build(
        manager: HealthConnectManager,
        cursorFor: (String) -> Instant,
        now: Instant = Instant.now(),
        maxChunkDays: Long = MAX_CHUNK_DAYS,
        overlapHours: Long = OVERLAP_HOURS
    ): Result {
        val records = JSONArray()
        val windowEnds = mutableMapOf<String, Instant>()

        run {
            val type = "heart_rate"
            val w = window(cursorFor(type), now, maxChunkDays, overlapHours)
            try {
                manager.readRecords<HeartRateRecord>(w.readStart, w.readEnd).forEach { rec ->
                    rec.samples.forEach { sample ->
                        records.put(base(type, rec.metadata.dataOrigin.packageName,
                            rec.metadata.id, sample.time).apply {
                            put("data", JSONObject().put("bpm", sample.beatsPerMinute).withDevice(rec.metadata))
                        })
                    }
                }
                windowEnds[type] = w.readEnd
            } catch (_: SecurityException) { }
        }

        run {
            val type = "sleep_session"
            val w = window(cursorFor(type), now, maxChunkDays, overlapHours)
            try {
                manager.readRecords<SleepSessionRecord>(w.readStart, w.readEnd).forEach { rec ->
                    records.put(base(type, rec.metadata.dataOrigin.packageName,
                        rec.metadata.id, rec.startTime).apply {
                        put("data", JSONObject().apply {
                            put("start_time", rec.startTime.toString())
                            put("end_time", rec.endTime.toString())
                            put("stage_count", rec.stages.size)
                            put("stages", JSONArray().apply {
                                rec.stages.forEach { stage ->
                                    put(JSONObject().apply {
                                        put("stage_type", stage.stage)
                                        put("start_time", stage.startTime.toString())
                                        put("end_time", stage.endTime.toString())
                                    })
                                }
                            })
                        }.withDevice(rec.metadata))
                    })
                }
                windowEnds[type] = w.readEnd
            } catch (_: SecurityException) { }
        }

        run {
            val type = "oxygen_saturation"
            val w = window(cursorFor(type), now, maxChunkDays, overlapHours)
            try {
                manager.readRecords<OxygenSaturationRecord>(w.readStart, w.readEnd).forEach { rec ->
                    records.put(base(type, rec.metadata.dataOrigin.packageName,
                        rec.metadata.id, rec.time).apply {
                        put("data", JSONObject().put("percentage", rec.percentage.value).withDevice(rec.metadata))
                    })
                }
                windowEnds[type] = w.readEnd
            } catch (_: SecurityException) { }
        }

        run {
            val type = "exercise_session"
            val w = window(cursorFor(type), now, maxChunkDays, overlapHours)
            try {
                manager.readRecords<ExerciseSessionRecord>(w.readStart, w.readEnd).forEach { rec ->
                    records.put(base(type, rec.metadata.dataOrigin.packageName,
                        rec.metadata.id, rec.startTime).apply {
                        put("data", JSONObject().apply {
                            put("start_time", rec.startTime.toString())
                            put("end_time", rec.endTime.toString())
                            put("exercise_type", rec.exerciseType)
                        }.withDevice(rec.metadata))
                    })
                }
                windowEnds[type] = w.readEnd
            } catch (_: SecurityException) { }
        }

        val json = JSONObject().apply {
            put("_note", "VitaSync v1 export: heart_rate, sleep_session, oxygen_saturation, exercise_session.")
            put("sync_run_at", now.toString())
            put("schema_version", 1)
            put("records", records)
        }
        return Result(json, windowEnds)
    }
}
