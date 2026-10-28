package com.roziq.zixtheserver

import android.os.Build
import android.util.Base64
import org.json.JSONObject
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

/** Enkripsi file backup dengan passphrase: PBKDF2 + AES-256-GCM. */
object BackupCrypto {

    private const val ITER = 150_000

    fun isEncrypted(raw: String): Boolean =
        try {
            JSONObject(raw).optString("zix") == "enc1"
        } catch (e: Exception) {
            false
        }

    private fun kdfName() =
        if (Build.VERSION.SDK_INT >= 26) "PBKDF2WithHmacSHA256" else "PBKDF2WithHmacSHA1"

    private fun key(pass: CharArray, salt: ByteArray, kdf: String): SecretKeySpec {
        val spec = PBEKeySpec(pass, salt, ITER, 256)
        val bytes = SecretKeyFactory.getInstance(kdf).generateSecret(spec).encoded
        return SecretKeySpec(bytes, "AES")
    }

    fun encrypt(plain: String, pass: CharArray): String {
        val rnd = SecureRandom()
        val salt = ByteArray(16).also { rnd.nextBytes(it) }
        val iv = ByteArray(12).also { rnd.nextBytes(it) }
        val kdf = kdfName()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key(pass, salt, kdf), GCMParameterSpec(128, iv))
        val data = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return JSONObject()
            .put("zix", "enc1")
            .put("kdf", kdf)
            .put("salt", Base64.encodeToString(salt, Base64.NO_WRAP))
            .put("iv", Base64.encodeToString(iv, Base64.NO_WRAP))
            .put("data", Base64.encodeToString(data, Base64.NO_WRAP))
            .toString(2)
    }

    /** Melempar exception bila passphrase salah atau file rusak. */
    fun decrypt(wrapped: String, pass: CharArray): String {
        val o = JSONObject(wrapped)
        val salt = Base64.decode(o.getString("salt"), Base64.NO_WRAP)
        val iv = Base64.decode(o.getString("iv"), Base64.NO_WRAP)
        val data = Base64.decode(o.getString("data"), Base64.NO_WRAP)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key(pass, salt, o.optString("kdf", kdfName())), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(data), Charsets.UTF_8)
    }
}
