package at.hochalex.assistent.voice

import android.app.Activity
import android.app.KeyguardManager
import android.hardware.biometrics.BiometricManager.Authenticators
import android.hardware.biometrics.BiometricPrompt
import android.os.CancellationSignal
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlin.coroutines.resume

/**
 * Freigabe vor dem Senden:
 *  - Handy gesperrt  → Entsperren verlangen (Fingerabdruck/PIN).
 *  - Handy entsperrt → zusätzliche biometrische Bestätigung.
 */
class AuthGate(private val activity: Activity) {
    private val km = activity.getSystemService(KeyguardManager::class.java)

    val isLocked: Boolean get() = km.isKeyguardLocked

    suspend fun authorize(title: String, subtitle: String): Boolean = withContext(Dispatchers.Main) {
        if (km.isKeyguardLocked) dismissKeyguard() else biometric(title, subtitle)
    }

    private suspend fun dismissKeyguard(): Boolean = suspendCancellableCoroutine { cont ->
        km.requestDismissKeyguard(activity, object : KeyguardManager.KeyguardDismissCallback() {
            override fun onDismissSucceeded() { if (cont.isActive) cont.resume(true) }
            override fun onDismissCancelled() { if (cont.isActive) cont.resume(false) }
            override fun onDismissError() { if (cont.isActive) cont.resume(false) }
        })
    }

    private suspend fun biometric(title: String, subtitle: String): Boolean {
        if (!km.isDeviceSecure) return true // keine Displaysperre eingerichtet
        return suspendCancellableCoroutine { cont ->
            val signal = CancellationSignal()
            val prompt = BiometricPrompt.Builder(activity)
                .setTitle(title)
                .setSubtitle(subtitle)
                .setAllowedAuthenticators(Authenticators.BIOMETRIC_STRONG or Authenticators.DEVICE_CREDENTIAL)
                .build()
            prompt.authenticate(signal, activity.mainExecutor, object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult?) {
                    if (cont.isActive) cont.resume(true)
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence?) {
                    if (cont.isActive) cont.resume(false)
                }
            })
            cont.invokeOnCancellation { signal.cancel() }
        }
    }
}
