package com.vitasync

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.records.*
import com.vitasync.ui.theme.VitaSyncTheme
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Duration
import java.time.Instant

class MainActivity : ComponentActivity() {

    private lateinit var healthConnectManager: HealthConnectManager

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        healthConnectManager = HealthConnectManager(this)

        setContent {
            VitaSyncTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SyncScreen(healthConnectManager)
                }
            }
        }
    }
}

data class MetricResult(val label: String, val summary: String, val error: Boolean = false)

@Composable
fun SyncScreen(healthConnectManager: HealthConnectManager) {
    val scope = rememberCoroutineScope()
    val context = androidx.compose.ui.platform.LocalContext.current
    var results by remember { mutableStateOf<List<MetricResult>>(emptyList()) }
    var status by remember { mutableStateOf("Ready") }

    val requestPermissionsLauncher = rememberLauncherForActivityResult(
        contract = PermissionController.createRequestPermissionResultContract()
    ) { granted ->
        status = if (granted.containsAll(healthConnectManager.permissions))
            "Permissions granted. Tap Sync All."
        else
            "Partial grant: ${granted.size}/${healthConnectManager.permissions.size} permissions."
    }

    suspend fun ensureReady(): Boolean {
        if (!healthConnectManager.isAvailable()) {
            status = "Health Connect not available on this device."
            return false
        }
        if (!healthConnectManager.hasAllPermissions()) {
            status = "Requesting permissions..."
            requestPermissionsLauncher.launch(healthConnectManager.permissions)
            return false
        }
        return true
    }

    suspend fun summarize(def: MetricDef): MetricResult {
        val end = Instant.now()
        val start = end.minus(Duration.ofDays(def.lookbackDays))
        return try {
            when (def.recordType) {
                HeartRateRecord::class -> {
                    val r = healthConnectManager.readRecords<HeartRateRecord>(start, end)
                    MetricResult(def.label, "${r.size} record(s), ${r.sumOf { it.samples.size }} sample(s)")
                }
                SleepSessionRecord::class -> {
                    val r = healthConnectManager.readRecords<SleepSessionRecord>(start, end)
                    MetricResult(def.label, "${r.size} session(s)")
                }
                OxygenSaturationRecord::class -> {
                    val r = healthConnectManager.readRecords<OxygenSaturationRecord>(start, end)
                    MetricResult(def.label, "${r.size} reading(s)")
                }
                ExerciseSessionRecord::class -> {
                    val r = healthConnectManager.readRecords<ExerciseSessionRecord>(start, end)
                    MetricResult(def.label, "${r.size} session(s)")
                }
                RestingHeartRateRecord::class -> {
                    val r = healthConnectManager.readRecords<RestingHeartRateRecord>(start, end)
                    MetricResult(def.label, "${r.size} reading(s)")
                }
                StepsRecord::class -> {
                    val r = healthConnectManager.readRecords<StepsRecord>(start, end)
                    MetricResult(def.label, "${r.size} record(s), total=${r.sumOf { it.count }}")
                }
                DistanceRecord::class -> {
                    val r = healthConnectManager.readRecords<DistanceRecord>(start, end)
                    MetricResult(def.label, "${r.size} record(s)")
                }
                ActiveCaloriesBurnedRecord::class -> {
                    val r = healthConnectManager.readRecords<ActiveCaloriesBurnedRecord>(start, end)
                    MetricResult(def.label, "${r.size} record(s)")
                }
                TotalCaloriesBurnedRecord::class -> {
                    val r = healthConnectManager.readRecords<TotalCaloriesBurnedRecord>(start, end)
                    MetricResult(def.label, "${r.size} record(s)")
                }
                RespiratoryRateRecord::class -> {
                    val r = healthConnectManager.readRecords<RespiratoryRateRecord>(start, end)
                    MetricResult(def.label, "${r.size} reading(s)")
                }
                BodyTemperatureRecord::class -> {
                    val r = healthConnectManager.readRecords<BodyTemperatureRecord>(start, end)
                    MetricResult(def.label, "${r.size} reading(s)")
                }
                WeightRecord::class -> {
                    val r = healthConnectManager.readRecords<WeightRecord>(start, end)
                    MetricResult(def.label, "${r.size} reading(s)")
                }
                BodyFatRecord::class -> {
                    val r = healthConnectManager.readRecords<BodyFatRecord>(start, end)
                    MetricResult(def.label, "${r.size} reading(s)")
                }
                BloodPressureRecord::class -> {
                    val r = healthConnectManager.readRecords<BloodPressureRecord>(start, end)
                    MetricResult(def.label, "${r.size} reading(s)")
                }
                BloodGlucoseRecord::class -> {
                    val r = healthConnectManager.readRecords<BloodGlucoseRecord>(start, end)
                    MetricResult(def.label, "${r.size} reading(s)")
                }
                Vo2MaxRecord::class -> {
                    val r = healthConnectManager.readRecords<Vo2MaxRecord>(start, end)
                    MetricResult(def.label, "${r.size} reading(s)")
                }
                HydrationRecord::class -> {
                    val r = healthConnectManager.readRecords<HydrationRecord>(start, end)
                    MetricResult(def.label, "${r.size} record(s)")
                }
                FloorsClimbedRecord::class -> {
                    val r = healthConnectManager.readRecords<FloorsClimbedRecord>(start, end)
                    MetricResult(def.label, "${r.size} record(s)")
                }
                ElevationGainedRecord::class -> {
                    val r = healthConnectManager.readRecords<ElevationGainedRecord>(start, end)
                    MetricResult(def.label, "${r.size} record(s)")
                }
                PowerRecord::class -> {
                    val r = healthConnectManager.readRecords<PowerRecord>(start, end)
                    MetricResult(def.label, "${r.size} record(s)")
                }
                SpeedRecord::class -> {
                    val r = healthConnectManager.readRecords<SpeedRecord>(start, end)
                    MetricResult(def.label, "${r.size} record(s)")
                }
                else -> MetricResult(def.label, "unhandled type")
            }
        } catch (e: Exception) {
            MetricResult(def.label, e.message ?: "error", error = true)
        }
    }

    // ---- Export: 4 Council-approved v1 types only ----
    suspend fun exportV1Json(): File {
        val end = Instant.now()

        val hrRecords = healthConnectManager.readRecords<HeartRateRecord>(end.minus(Duration.ofHours(24)), end)
        val sleepRecords = healthConnectManager.readRecords<SleepSessionRecord>(end.minus(Duration.ofDays(7)), end)
        val spo2Records = healthConnectManager.readRecords<OxygenSaturationRecord>(end.minus(Duration.ofDays(7)), end)
        val exerciseRecords = healthConnectManager.readRecords<ExerciseSessionRecord>(end.minus(Duration.ofDays(14)), end)

        val recordsArray = JSONArray()

        hrRecords.forEach { rec ->
            rec.samples.forEach { sample ->
                recordsArray.put(JSONObject().apply {
                    put("source", "health_connect")
                    put("record_type", "heart_rate")
                    put("source_app", rec.metadata.dataOrigin.packageName)
                    put("source_record_id", rec.metadata.id)
                    put("data_recorded_at", sample.time.toString())
                    put("data_ingested_at", Instant.now().toString())
                    put("data", JSONObject().apply {
                        put("bpm", sample.beatsPerMinute)
                    })
                })
            }
        }

        sleepRecords.forEach { rec ->
            recordsArray.put(JSONObject().apply {
                put("source", "health_connect")
                put("record_type", "sleep_session")
                put("source_app", rec.metadata.dataOrigin.packageName)
                put("source_record_id", rec.metadata.id)
                put("data_recorded_at", rec.startTime.toString())
                put("data_ingested_at", Instant.now().toString())
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

        spo2Records.forEach { rec ->
            recordsArray.put(JSONObject().apply {
                put("source", "health_connect")
                put("record_type", "oxygen_saturation")
                put("source_app", rec.metadata.dataOrigin.packageName)
                put("source_record_id", rec.metadata.id)
                put("data_recorded_at", rec.time.toString())
                put("data_ingested_at", Instant.now().toString())
                put("data", JSONObject().apply {
                    put("percentage", rec.percentage.value)
                })
            })
        }

        exerciseRecords.forEach { rec ->
            recordsArray.put(JSONObject().apply {
                put("source", "health_connect")
                put("record_type", "exercise_session")
                put("source_app", rec.metadata.dataOrigin.packageName)
                put("source_record_id", rec.metadata.id)
                put("data_recorded_at", rec.startTime.toString())
                put("data_ingested_at", Instant.now().toString())
                put("data", JSONObject().apply {
                    put("start_time", rec.startTime.toString())
                    put("end_time", rec.endTime.toString())
                    put("exercise_type", rec.exerciseType)
                })
            })
        }

        val root = JSONObject().apply {
            put("_note", "REAL device export from VitaSync — Council-approved v1 scope only (heart_rate, sleep_session, oxygen_saturation, exercise_session). Generated on-device, not hand-fabricated.")
            put("sync_run_at", Instant.now().toString())
            put("schema_version", 1)
            put("records", recordsArray)
        }

        val exportDir = File(healthConnectManager.healthConnectClient.let { context.cacheDir }, "exports")
        exportDir.mkdirs()
        val file = File(exportDir, "vitasync_export_${System.currentTimeMillis()}.json")
        file.writeText(root.toString(2))
        return file
    }

    Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
        Text(text = "VitaSync", style = MaterialTheme.typography.headlineMedium)
        Spacer(modifier = Modifier.height(8.dp))
        Text(text = status)
        Spacer(modifier = Modifier.height(12.dp))

        Button(onClick = {
            scope.launch {
                if (!ensureReady()) return@launch
                status = "Syncing ${healthConnectManager.metrics.size} metrics..."
                results = healthConnectManager.metrics.map { summarize(it) }
                status = "Done. ${results.count { !it.error }} ok, ${results.count { it.error }} errored."
            }
        }) {
            Text("Sync All")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(onClick = {
            scope.launch {
                if (!ensureReady()) return@launch
                status = "Exporting v1 JSON..."
                val file = exportV1Json()
                status = "Exported: ${file.name}"

                val uri = FileProvider.getUriForFile(context, "com.vitasync.fileprovider", file)
                val shareIntent = Intent(Intent.ACTION_SEND).apply {
                    type = "application/json"
                    putExtra(Intent.EXTRA_STREAM, uri)
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                }
                context.startActivity(Intent.createChooser(shareIntent, "Share VitaSync export"))
            }
        }) {
            Text("Export v1 JSON")
        }

        Spacer(modifier = Modifier.height(16.dp))

        LazyColumn {
            items(results.filter { !it.summary.startsWith("0 ") }) { r ->
                Text(
                    text = "${r.label}: ${r.summary}",
                    color = if (r.error) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(vertical = 4.dp)
                )
            }
        }
    }
}