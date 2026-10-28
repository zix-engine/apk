package com.roziq.zixtheserver

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity

/** Kunci aplikasi memakai sidik jari / wajah / PIN-pola kunci layar HP. */
object AppLock {

    private const val GRACE_MS = 30_000L
    const val WIPE_AFTER = 10
    private const val AUTHENTICATORS =
        BiometricManager.Authenticators.BIOMETRIC_WEAK or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL

    @Volatile private var authed = false
    @Volatile private var lastActive = 0L
    @Volatile private var prompting = false

    fun isSupported(activity: FragmentActivity): Boolean =
        BiometricManager.from(activity).canAuthenticate(AUTHENTICATORS) ==
            BiometricManager.BIOMETRIC_SUCCESS

    /** Dipanggil saat layar aktif/ditinggalkan agar masa tenggang terhitung. */
    fun touch() {
        if (authed) lastActive = System.currentTimeMillis()
    }

    fun needsAuth(): Boolean =
        !authed || System.currentTimeMillis() - lastActive > GRACE_MS

    fun markLocked() {
        authed = false
    }

    /** Menampilkan prompt. [onResult] true bila berhasil. */
    fun authenticate(activity: FragmentActivity, title: String, onResult: (Boolean) -> Unit) {
        if (prompting) return
        prompting = true
        val executor = ContextCompat.getMainExecutor(activity)
        val prompt = BiometricPrompt(
            activity, executor,
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    prompting = false
                    Prefs(activity).lockFails = 0
                    authed = true
                    lastActive = System.currentTimeMillis()
                    onResult(true)
                }

                override fun onAuthenticationFailed() {
                    val p = Prefs(activity)
                    if (!p.wipeEnabled) return
                    p.lockFails = p.lockFails + 1
                    if (p.lockFails >= WIPE_AFTER) {
                        wipe(activity)
                        prompting = false
                        onResult(false)
                    }
                }

                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    prompting = false
                    onResult(false)
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle(title)
            .setAllowedAuthenticators(AUTHENTICATORS)
            .build()
        prompt.authenticate(info)
    }

    /** Hapus semua data (daftar server, pengaturan, jadwal) dari aplikasi ini. */
    fun wipe(activity: FragmentActivity) {
        try {
            androidx.work.WorkManager.getInstance(activity.applicationContext).cancelAllWork()
        } catch (_: Exception) {
        }
        ServerStore(activity).clearAll()
        Prefs(activity).wipeAll()
    }
}
