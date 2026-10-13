package com.vitasync

import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.OxygenSaturationRecord
import androidx.health.connect.client.records.SleepSessionRecord
import org.json.JSONArray
import org.json.JSONObject
import java.time.Duration
import java.time.Instant

/**
 * Builds the v1 export (schema_version 1) that VitaClaw's receiver accepts.
 * Same four record types and the same record shape as the 9 October 2026
 * export. Windows overlap from one day to the next on purpose: VitaClaw
 * ignores a record it already holds, so a missed day is picked up next time.
 *
 * A type whose permission is not granted is skipped, not an error
 * (contract rule 6: partial batches are valid).
 */
object ExportBuilder {

    private val HEART_RATE_WINDOW: Duration = Duration.ofHours(48)
    private val SLEEP_WINDOW: Duration = Duration.ofDays(7)
    private val SPO2_WINDOW: Duration = Duration.ofDays(7)
    private val EXERCISE_WINDOW: Duration = Duration.ofDays(14)

    private fun base(type: String, app: String, id: String, recordedAt: Instant) =
        JSONObject().apply {
            put("source", "health_connect")
            put("record_type", type)
            put("source_app", app)
            put("source_record_id", id)
            put("data_recorded_at", recordedAt.toString())
            put("data_ingested_at", Instant.now().toString())
        }

    private suspend fun <T> readOrSkip(block: suspend () -> List<T>): List<T> =
        try {
            block()
        } catch (e: SecurityException) {
            emptyList()
        }

    suspend fun build(manager: HealthConnectManager, now: Instant = Instant.now()): JSONObject {
        val records = JSONArray()

        readOrSkip { manager.readRecords<HeartRateRecord>(now.minus(HEART_RATE_WINDOW), now) }
            .forEach { rec ->
                rec.samples.forEach { sample ->
                    records.put(base("heart_rate", rec.metadata.dataOrigin.packageName,
                        rec.metadata.id, sample.time).apply {
                        put("data", JSONObject().put("bpm", sample.beatsPerMinute))
                    })
                }
            }

        readOrSkip { manager.readRecords<SleepSessionRecord>(now.minus(SLEEP_WINDOW), now) }
            .forEach { rec ->
                records.put(base("sleep_session", rec.metadata.dataOrigin.packageName,
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
                    })
                })
            }

        readOrSkip { manager.readRecords<OxygenSaturationRecord>(now.minus(SPO2_WINDOW), now) }
            .forEach { rec ->
                records.put(base("oxygen_saturation", rec.metadata.dataOrigin.packageName,
                    rec.metadata.id, rec.time).apply {
                    put("data", JSONObject().put("percentage", rec.percentage.value))
                })
            }

        readOrSkip { manager.readRecords<ExerciseSessionRecord>(now.minus(EXERCISE_WINDOW), now) }
            .forEach { rec ->
                records.put(base("exercise_session", rec.metadata.dataOrigin.packageName,
                    rec.metadata.id, rec.startTime).apply {
                    put("data", JSONObject().apply {
                        put("start_time", rec.startTime.toString())
                        put("end_time", rec.endTime.toString())
                        put("exercise_type", rec.exerciseType)
                    })
                })
            }

        return JSONObject().apply {
            put("_note", "VitaSync v1 export: heart_rate, sleep_session, oxygen_saturation, exercise_session.")
            put("sync_run_at", Instant.now().toString())
            put("schema_version", 1)
            put("records", records)
        }
    }
}
