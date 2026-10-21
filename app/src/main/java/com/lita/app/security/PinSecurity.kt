package com.lita.app.security

import com.lita.app.crypto.VaultCrypto
import com.lita.app.data.KeyValueStore
import java.security.MessageDigest
import android.util.Base64

/** Salted PBKDF2-HMAC-SHA-256 PIN verifier. The PIN itself is never stored. */
object PinHasher {
    const val ITERATIONS = 60_000

    class Stored(val salt: ByteArray, val hash: ByteArray, val iterations: Int)

    fun create(pin: CharArray): Stored {
        val salt = VaultCrypto.randomBytes(16)
        return Stored(salt, VaultCrypto.pbkdf2(pin, salt, ITERATIONS), ITERATIONS)
    }

    fun verify(pin: CharArray, stored: Stored): Boolean {
        val h = VaultCrypto.pbkdf2(pin, stored.salt, stored.iterations)
        return MessageDigest.isEqual(h, stored.hash)
    }

    fun isValidFormat(pin: CharArray) = pin.size in 4..8 && pin.all { it in '0'..'9' }
}

/** Exponential lockout after repeated wrong PINs (5 free attempts, then 30 s doubling up to 15 min). */
class AttemptLimiter(private val store: KeyValueStore, private val clock: () -> Long = System::currentTimeMillis) {
    private var failures = store.get(K_FAIL)?.toIntOrNull() ?: 0
    private var lockedUntil = store.get(K_UNTIL)?.toLongOrNull() ?: 0L

    val failureCount: Int get() = failures
    fun remainingLockMs(): Long = maxOf(0L, lockedUntil - clock())

    fun recordFailure() {
        failures++
        if (failures >= FREE_ATTEMPTS) {
            val step = failures - FREE_ATTEMPTS
            val ms = minOf(MAX_LOCK_MS, BASE_LOCK_MS shl minOf(step, 10))
            lockedUntil = clock() + ms
        }
        persist()
    }

    fun recordSuccess() { failures = 0; lockedUntil = 0; persist() }

    private fun persist() { store.put(K_FAIL, failures.toString()); store.put(K_UNTIL, lockedUntil.toString()) }

    companion object {
        const val FREE_ATTEMPTS = 5
        const val BASE_LOCK_MS = 30_000L
        const val MAX_LOCK_MS = 15 * 60_000L
        private const val K_FAIL = "pin.failures"
        private const val K_UNTIL = "pin.lockedUntil"
    }
}

/** Typed app settings on top of a [KeyValueStore]. */
class AppPrefs(val store: KeyValueStore) {
    var vaultUri: String?
        get() = store.get("vault.uri")
        set(v) = store.put("vault.uri", v)
    var wrappedKey: ByteArray?
        get() = store.get("vault.wrappedKey")?.let { Base64.decode(it, Base64.NO_WRAP) }
        set(v) = store.put("vault.wrappedKey", v?.let { Base64.encodeToString(it, Base64.NO_WRAP) })

    fun pin(): PinHasher.Stored? {
        val s = store.get("pin.salt") ?: return null
        val h = store.get("pin.hash") ?: return null
        val it = store.get("pin.iter")?.toIntOrNull() ?: return null
        return PinHasher.Stored(Base64.decode(s, Base64.NO_WRAP), Base64.decode(h, Base64.NO_WRAP), it)
    }
    fun setPin(p: PinHasher.Stored?) {
        store.put("pin.salt", p?.let { Base64.encodeToString(it.salt, Base64.NO_WRAP) })
        store.put("pin.hash", p?.let { Base64.encodeToString(it.hash, Base64.NO_WRAP) })
        store.put("pin.iter", p?.iterations?.toString())
    }

    private fun bool(k: String, d: Boolean) = store.get(k)?.toBooleanStrictOrNull() ?: d
    var biometricEnabled: Boolean
        get() = bool("lock.biometric", false)
        set(v) = store.put("lock.biometric", v.toString())
    /** Seconds in background before relock: 0 = immediately, -1 = never (until the app process dies). */
    var relockSeconds: Int
        get() = store.get("lock.relock")?.toIntOrNull() ?: 0
        set(v) = store.put("lock.relock", v.toString())
    /** Length of the saved PIN, so the lock screen can unlock automatically; 0 = unknown (show OK button). */
    var pinLength: Int
        get() = store.get("pin.length")?.toIntOrNull() ?: 0
        set(v) = store.put("pin.length", v.toString())
    /** Lita's own playback volume, 0..100 in 1% steps (applied on top of the phone's media volume). */
    var appVolume: Int
        get() = store.get("player.volume")?.toIntOrNull()?.coerceIn(0, 100) ?: 100
        set(v) = store.put("player.volume", v.coerceIn(0, 100).toString())
    var language: String
        get() = store.get("ui.lang") ?: "en"
        set(v) = store.put("ui.lang", v)
    var showcasePreviews: Boolean
        get() = bool("ui.previews", false)
        set(v) = store.put("ui.previews", v.toString())
    var blockScreenshots: Boolean
        get() = bool("ui.secure", true)
        set(v) = store.put("ui.secure", v.toString())
    var muted: Boolean
        get() = bool("player.muted", false)
        set(v) = store.put("player.muted", v.toString())
    var shuffleState: String?
        get() = store.get("shuffle.state")
        set(v) = store.put("shuffle.state", v)
}
