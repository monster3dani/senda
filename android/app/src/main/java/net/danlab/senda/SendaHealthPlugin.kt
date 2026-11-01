package net.danlab.senda

import android.content.Intent
import android.net.Uri
import android.os.Build
import androidx.activity.result.ActivityResult
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.PermissionController
import androidx.health.connect.client.contracts.ExerciseRouteRequestContract
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseRoute
import androidx.health.connect.client.records.ExerciseRouteResult
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord
import androidx.health.connect.client.records.TotalCaloriesBurnedRecord
import androidx.health.connect.client.request.AggregateRequest
import androidx.health.connect.client.request.ReadRecordsRequest
import androidx.health.connect.client.time.TimeRangeFilter
import com.getcapacitor.JSArray
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.ActivityCallback
import com.getcapacitor.annotation.CapacitorPlugin
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.Instant

/**
 * Senda (DanLab): lee de Health Connect las caminatas que guarda Zepp (u otra app),
 * con su ruta GPS, para añadirlas a Senda sin exportar archivos.
 */
@CapacitorPlugin(name = "SendaHealth")
class SendaHealthPlugin : Plugin() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val routePermission = "android.permission.health.READ_EXERCISE_ROUTES"

    private fun permissions(): Set<String> {
        val p = mutableSetOf(
            HealthPermission.getReadPermission(ExerciseSessionRecord::class),
            HealthPermission.getReadPermission(HeartRateRecord::class),
            HealthPermission.getReadPermission(DistanceRecord::class),
            HealthPermission.getReadPermission(TotalCaloriesBurnedRecord::class),
            HealthPermission.getReadPermission(ElevationGainedRecord::class)
        )
        // En Android 15 o superior se puede pedir permiso para todas las rutas de una vez
        if (Build.VERSION.SDK_INT >= 35) p.add(routePermission)
        return p
    }

    private fun client(): HealthConnectClient = HealthConnectClient.getOrCreate(context)

    private fun status(): String = when (HealthConnectClient.getSdkStatus(context)) {
        HealthConnectClient.SDK_AVAILABLE -> "available"
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED -> "update_required"
        else -> if (Build.VERSION.SDK_INT >= 34) "unavailable" else "not_installed"
    }

    @PluginMethod
    fun availability(call: PluginCall) {
        val r = JSObject()
        r.put("status", status())
        call.resolve(r)
    }

    /** Abre la Play Store para instalar o actualizar Health Connect. */
    @PluginMethod
    fun openInstall(call: PluginCall) {
        try {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.google.android.apps.healthdata&url=healthconnect%3A%2F%2Fonboarding"))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            i.setPackage("com.android.vending")
            context.startActivity(i)
        } catch (e: Exception) {
            val i = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.google.android.apps.healthdata"))
            i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            context.startActivity(i)
        }
        call.resolve()
    }

    @PluginMethod
    fun hasPermissions(call: PluginCall) {
        if (status() != "available") { val r = JSObject(); r.put("granted", false); call.resolve(r); return }
        scope.launch {
            try {
                val granted = client().permissionController.getGrantedPermissions()
                val need = permissions()
                val r = JSObject()
                r.put("granted", granted.contains(HealthPermission.getReadPermission(ExerciseSessionRecord::class)))
                r.put("all", granted.containsAll(need))
                call.resolve(r)
            } catch (e: Exception) {
                call.reject("Health Connect no responde: ${e.message}")
            }
        }
    }

    @PluginMethod
    fun requestPermissions(call: PluginCall) {
        if (status() != "available") { call.reject("Health Connect no está disponible", "UNAVAILABLE"); return }
        try {
            val contract = PermissionController.createRequestPermissionResultContract()
            val intent = contract.createIntent(context, permissions())
            startActivityForResult(call, intent, "permissionsResult")
        } catch (e: Exception) {
            call.reject("No se pudo pedir el permiso: ${e.message}")
        }
    }

    @ActivityCallback
    private fun permissionsResult(call: PluginCall?, result: ActivityResult) {
        if (call == null) return
        scope.launch {
            try {
                val granted = client().permissionController.getGrantedPermissions()
                val r = JSObject()
                r.put("granted", granted.contains(HealthPermission.getReadPermission(ExerciseSessionRecord::class)))
                r.put("all", granted.containsAll(permissions()))
                call.resolve(r)
            } catch (e: Exception) {
                call.reject("No se pudo comprobar el permiso: ${e.message}")
            }
        }
    }

    private fun typeName(t: Int): String = when (t) {
        ExerciseSessionRecord.EXERCISE_TYPE_WALKING -> "Caminata"
        ExerciseSessionRecord.EXERCISE_TYPE_HIKING -> "Senderismo"
        ExerciseSessionRecord.EXERCISE_TYPE_RUNNING -> "Carrera"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING -> "Ciclismo"
        ExerciseSessionRecord.EXERCISE_TYPE_BIKING_STATIONARY -> "Bici estática"
        else -> "Ejercicio"
    }

    /** Lista las sesiones de ejercicio desde una fecha (ms). */
    @PluginMethod
    fun listSessions(call: PluginCall) {
        val since = (call.data.opt("since") as? Number)?.toLong() ?: (System.currentTimeMillis() - 30L * 86400000L)
        scope.launch {
            try {
                val req = ReadRecordsRequest(
                    recordType = ExerciseSessionRecord::class,
                    timeRangeFilter = TimeRangeFilter.between(Instant.ofEpochMilli(since), Instant.now()),
                    ascendingOrder = false,
                    pageSize = 200
                )
                val res = client().readRecords(req)
                val arr = JSArray()
                for (s in res.records) {
                    val o = JSObject()
                    o.put("id", s.metadata.id)
                    o.put("start", s.startTime.toEpochMilli())
                    o.put("end", s.endTime.toEpochMilli())
                    o.put("type", typeName(s.exerciseType))
                    o.put("title", s.title ?: "")
                    o.put("origin", s.metadata.dataOrigin.packageName)
                    o.put("route", when (s.exerciseRouteResult) {
                        is ExerciseRouteResult.Data -> "yes"
                        is ExerciseRouteResult.ConsentRequired -> "consent"
                        else -> "no"
                    })
                    arr.put(o)
                }
                val r = JSObject()
                r.put("sessions", arr)
                call.resolve(r)
            } catch (e: SecurityException) {
                call.reject("Falta el permiso de Health Connect", "PERMISSION_DENIED")
            } catch (e: Exception) {
                call.reject("No se pudieron leer los ejercicios: ${e.message}")
            }
        }
    }

    /** Lee una sesión completa: ruta, pulso, distancia, calorías y desnivel. */
    @PluginMethod
    fun readSession(call: PluginCall) {
        val id = call.getString("id")
        if (id == null) { call.reject("Falta la sesión"); return }
        scope.launch {
            try {
                val s = client().readRecord(ExerciseSessionRecord::class, id).record
                when (val rr = s.exerciseRouteResult) {
                    is ExerciseRouteResult.Data -> call.resolve(buildSession(s, rr.exerciseRoute))
                    is ExerciseRouteResult.ConsentRequired -> {
                        // Android pide permiso para esta ruta concreta
                        bridge.activity.runOnUiThread {
                            try {
                                val intent = ExerciseRouteRequestContract().createIntent(context, id)
                                startActivityForResult(call, intent, "routeResult")
                            } catch (e: Exception) {
                                call.reject("No se pudo pedir permiso para la ruta: ${e.message}")
                            }
                        }
                    }
                    else -> call.resolve(buildSession(s, null))
                }
            } catch (e: SecurityException) {
                call.reject("Falta el permiso de Health Connect", "PERMISSION_DENIED")
            } catch (e: Exception) {
                call.reject("No se pudo leer la caminata: ${e.message}")
            }
        }
    }

    @ActivityCallback
    private fun routeResult(call: PluginCall?, result: ActivityResult) {
        if (call == null) return
        val id = call.getString("id") ?: return
        val route = try { ExerciseRouteRequestContract().parseResult(result.resultCode, result.data) } catch (e: Exception) { null }
        scope.launch {
            try {
                val s = client().readRecord(ExerciseSessionRecord::class, id).record
                call.resolve(buildSession(s, route))
            } catch (e: Exception) {
                call.reject("No se pudo leer la caminata: ${e.message}")
            }
        }
    }

    private suspend fun buildSession(s: ExerciseSessionRecord, route: ExerciseRoute?): JSObject {
        val o = JSObject()
        o.put("id", s.metadata.id)
        o.put("start", s.startTime.toEpochMilli())
        o.put("end", s.endTime.toEpochMilli())
        o.put("type", typeName(s.exerciseType))
        o.put("title", s.title ?: "")
        o.put("origin", s.metadata.dataOrigin.packageName)
        val pts = JSArray()
        route?.route?.forEach { l ->
            val p = JSArray()
            p.put(l.latitude); p.put(l.longitude)
            p.put(l.altitude?.inMeters ?: org.json.JSONObject.NULL)
            p.put(l.time.toEpochMilli())
            pts.put(p)
        }
        o.put("points", pts)
        val range = TimeRangeFilter.between(s.startTime, s.endTime)
        // pulso
        val hr = JSArray()
        try {
            val res = client().readRecords(ReadRecordsRequest(HeartRateRecord::class, range, pageSize = 1000))
            for (rec in res.records) for (sm in rec.samples) {
                val p = JSArray(); p.put(sm.time.toEpochMilli()); p.put(sm.beatsPerMinute); hr.put(p)
            }
        } catch (e: Exception) { /* sin permiso o sin datos */ }
        o.put("hr", hr)
        // totales
        try {
            val agg = client().aggregate(AggregateRequest(
                metrics = setOf(DistanceRecord.DISTANCE_TOTAL, TotalCaloriesBurnedRecord.ENERGY_TOTAL, ElevationGainedRecord.ELEVATION_GAINED_TOTAL),
                timeRangeFilter = range
            ))
            agg[DistanceRecord.DISTANCE_TOTAL]?.let { o.put("distance", it.inMeters) }
            agg[TotalCaloriesBurnedRecord.ENERGY_TOTAL]?.let { o.put("calories", it.inKilocalories) }
            agg[ElevationGainedRecord.ELEVATION_GAINED_TOTAL]?.let { o.put("ascent", it.inMeters) }
        } catch (e: Exception) { /* sin permiso o sin datos */ }
        return o
    }
}
