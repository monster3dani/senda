package net.danlab.senda

import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.contract.ActivityResultContracts
import com.getcapacitor.JSObject
import com.getcapacitor.Plugin
import com.getcapacitor.PluginCall
import com.getcapacitor.PluginMethod
import com.getcapacitor.annotation.CapacitorPlugin
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.identity.AuthorizationRequest
import com.google.android.gms.auth.api.identity.AuthorizationResult
import com.google.android.gms.auth.api.identity.Identity
import com.google.android.gms.common.api.Scope

/**
 * Senda (DanLab): pide a Google permiso para guardar la copia de seguridad
 * en la carpeta privada de Senda en Google Drive y devuelve el pase de acceso.
 * Las subidas y descargas las hace la app con ese pase.
 */
@CapacitorPlugin(name = "SendaDrive")
class SendaDrivePlugin : Plugin() {

    private val driveScope = Scope("https://www.googleapis.com/auth/drive.appdata")
    private var launcher: ActivityResultLauncher<IntentSenderRequest>? = null
    private var pending: PluginCall? = null

    override fun load() {
        launcher = activity.registerForActivityResult(ActivityResultContracts.StartIntentSenderForResult()) { res ->
            val call = pending ?: return@registerForActivityResult
            pending = null
            try {
                val result = Identity.getAuthorizationClient(activity).getAuthorizationResultFromIntent(res.data)
                resolveResult(call, result)
            } catch (e: Exception) {
                call.reject("No se ha dado permiso para usar Google Drive.", "DENIED")
            }
        }
    }

    private fun resolveResult(call: PluginCall, result: AuthorizationResult) {
        val token = result.accessToken
        if (token.isNullOrEmpty()) { call.reject("Google no ha devuelto el acceso.", "NO_TOKEN"); return }
        val r = JSObject()
        r.put("token", token)
        var email: String? = null
        try { email = result.toGoogleSignInAccount()?.email } catch (_: Exception) {}
        if (email != null) r.put("email", email)
        call.resolve(r)
    }

    /** interactive=false: solo si ya se dio permiso antes (sin enseñar nada). */
    @PluginMethod
    fun authorize(call: PluginCall) {
        val interactive = call.getBoolean("interactive", false) ?: false
        val req = AuthorizationRequest.builder().setRequestedScopes(listOf(driveScope)).build()
        Identity.getAuthorizationClient(activity).authorize(req)
            .addOnSuccessListener { result ->
                if (result.hasResolution()) {
                    if (!interactive) {
                        val r = JSObject(); r.put("needsUi", true); call.resolve(r); return@addOnSuccessListener
                    }
                    val pi = result.pendingIntent
                    if (pi == null || launcher == null) { call.reject("No se puede abrir la pantalla de Google.", "NO_UI"); return@addOnSuccessListener }
                    pending = call
                    launcher!!.launch(IntentSenderRequest.Builder(pi.intentSender).build())
                } else {
                    resolveResult(call, result)
                }
            }
            .addOnFailureListener { e ->
                call.reject("No se ha podido conectar con Google: " + (e.message ?: ""), "FAILED")
            }
    }

    /** Descarta un pase caducado para que Google dé uno nuevo. */
    @PluginMethod
    fun clearToken(call: PluginCall) {
        val token = call.getString("token")
        if (token == null) { call.resolve(); return }
        Thread {
            try { GoogleAuthUtil.clearToken(context, token) } catch (_: Exception) {}
            call.resolve()
        }.start()
    }

    @PluginMethod
    fun signOut(call: PluginCall) {
        try {
            Identity.getSignInClient(activity).signOut()
                .addOnCompleteListener { call.resolve() }
        } catch (_: Exception) { call.resolve() }
    }
}
