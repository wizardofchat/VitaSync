package com.vitasync

import android.content.Context
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.*
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import java.time.Instant
import kotlin.reflect.KClass

data class MetricDef(
    val label: String,
    val recordType: KClass<out Record>,
    val lookbackDays: Long
)

class HealthConnectManager(private val context: Context) {

    val healthConnectClient: HealthConnectClient by lazy {
        HealthConnectClient.getOrCreate(context)
    }

    fun isAvailable(): Boolean =
        HealthConnectClient.getSdkStatus(context) == HealthConnectClient.SDK_AVAILABLE

    val metrics = listOf(
        MetricDef("Heart Rate", HeartRateRecord::class, 1),
        MetricDef("Resting Heart Rate", RestingHeartRateRecord::class, 7),
        MetricDef("Sleep", SleepSessionRecord::class, 7),
        MetricDef("Oxygen Saturation", OxygenSaturationRecord::class, 7),
        MetricDef("Exercise", ExerciseSessionRecord::class, 14),
        MetricDef("Steps", StepsRecord::class, 7),
        MetricDef("Distance", DistanceRecord::class, 7),
        MetricDef("Active Calories", ActiveCaloriesBurnedRecord::class, 7),
        MetricDef("Total Calories", TotalCaloriesBurnedRecord::class, 7),
        MetricDef("Respiratory Rate", RespiratoryRateRecord::class, 7),
        MetricDef("Body Temperature", BodyTemperatureRecord::class, 30),
        MetricDef("Weight", WeightRecord::class, 90),
        MetricDef("Body Fat", BodyFatRecord::class, 90),
        MetricDef("Blood Pressure", BloodPressureRecord::class, 30),
        MetricDef("Blood Glucose", BloodGlucoseRecord::class, 30),
        MetricDef("VO2 Max", Vo2MaxRecord::class, 90),
        MetricDef("Hydration", HydrationRecord::class, 7),
        MetricDef("Floors Climbed", FloorsClimbedRecord::class, 7),
        MetricDef("Elevation Gained", ElevationGainedRecord::class, 7),
        MetricDef("Power", PowerRecord::class, 7),
        MetricDef("Speed", SpeedRecord::class, 7)
    )

    val permissions: Set<String> =
        metrics.map { HealthPermission.getReadPermission(it.recordType) }.toSet()

    /** The types sent to VitaClaw: v1 contract four + resting_heart_rate and steps (26 Oct 2026, Garmin). */
    val exportPermissions: Set<String> = setOf(
        HealthPermission.getReadPermission(HeartRateRecord::class),
        HealthPermission.getReadPermission(SleepSessionRecord::class),
        HealthPermission.getReadPermission(OxygenSaturationRecord::class),
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(RestingHeartRateRecord::class),
        HealthPermission.getReadPermission(StepsRecord::class)
    )

    /** Lets the daily job read while the app is closed (Health Connect feature). */
    val backgroundPermission = "android.permission.health.READ_HEALTH_DATA_IN_BACKGROUND"

    suspend fun hasAllPermissions(): Boolean =
        healthConnectClient.permissionController.getGrantedPermissions()
            .containsAll(permissions)

    suspend fun hasExportPermissions(): Boolean =
        healthConnectClient.permissionController.getGrantedPermissions()
            .containsAll(exportPermissions)

    suspend fun canReadInBackground(): Boolean =
        healthConnectClient.permissionController.getGrantedPermissions()
            .contains(backgroundPermission)

    /** Reads every page, not only the first (Health Connect pages large results). */
    suspend inline fun <reified T : Record> readRecords(start: Instant, end: Instant): List<T> {
        val all = mutableListOf<T>()
        var token: String? = null
        do {
            val response = healthConnectClient.readRecords(
                ReadRecordsRequest(
                    recordType = T::class,
                    timeRangeFilter = TimeRangeFilter.between(start, end),
                    pageToken = token
                )
            )
            all += response.records
            token = response.pageToken
        } while (token != null)
        return all
    }
}