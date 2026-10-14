package dev.symbiosis.runtime.storage

import android.content.Context
import android.content.SharedPreferences
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import dev.symbiosis.runtime.core.Ids
import java.io.File

/**
 * Keystore-backed secrets: the admin token (stored hashed AND plaintext-encrypted so the UI can
 * re-show it on demand), the audit chain seed, and the device id.
 *
 * Opening the encrypted store is retried, and the plaintext fallback is only for a device whose Keystore has
 * NEVER worked for us (some emulators). It used to fall back on the first exception: one transient Keystore
 * hiccup on boot and the runtime came up on empty plain prefs, minting a new device id, a new admin token and a
 * new audit seed (every paired client broken, the audit chain unverifiable), and wrote those to disk where they
 * then competed with the real ones on the next boot. Now, once encrypted prefs have existed, failing to open them
 * throws — loudly, and the service retries and says so in its notification — rather than becoming someone else.
 */
class SecureStore(context: Context) {
  /** False when EncryptedSharedPreferences could not be created and plain private prefs are in use. */
  @Volatile var usingKeystore: Boolean = true
    private set

  private val prefs: SharedPreferences = open(context.applicationContext)

  private fun open(app: Context): SharedPreferences {
    val meta = app.getSharedPreferences(META_PREFS, Context.MODE_PRIVATE)
    var last: Throwable? = null
    for (attempt in 0 until OPEN_ATTEMPTS) {
      try {
        val enc = openEncrypted(app)
        // Remember that the Keystore works on this install, so a later failure is never mistaken for "no Keystore".
        if (!meta.getBoolean(K_EVER_ENCRYPTED, false)) meta.edit().putBoolean(K_EVER_ENCRYPTED, true).commit()
        migrateFallback(app, enc)
        return enc
      } catch (t: Throwable) {
        last = t
        android.util.Log.w(TAG, "EncryptedSharedPreferences open failed (attempt ${attempt + 1}/$OPEN_ATTEMPTS)", t)
        if (attempt < OPEN_ATTEMPTS - 1) Thread.sleep(RETRY_DELAYS_MS[attempt])
      }
    }
    if (encryptedEverExisted(app, meta)) {
      // Refuse to become a different device. The caller (RuntimeService) retries with backoff and shows this.
      throw IllegalStateException("secure storage (Keystore) unavailable; refusing to fall back and mint a new identity", last)
    }
    // Never had a working Keystore here (e.g. an emulator image without one): the old behaviour, and say so.
    android.util.Log.w(TAG, "EncryptedSharedPreferences unavailable on this device, using private prefs", last)
    usingKeystore = false
    return app.getSharedPreferences(FALLBACK_PREFS, Context.MODE_PRIVATE)
  }

  private fun openEncrypted(app: Context): SharedPreferences {
    val key = MasterKey.Builder(app).setKeyScheme(MasterKey.KeyScheme.AES256_GCM).build()
    return EncryptedSharedPreferences.create(
      app, ENCRYPTED_PREFS, key,
      EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
      EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM,
    )
  }

  /**
   * Evidence the encrypted store was in use before: our own marker, or the prefs file on disk (installs that
   * predate the marker). Either means a fresh identity would orphan a real one.
   */
  private fun encryptedEverExisted(app: Context, meta: SharedPreferences): Boolean =
    meta.getBoolean(K_EVER_ENCRYPTED, false) || File(app.dataDir, "shared_prefs/$ENCRYPTED_PREFS.xml").let { it.exists() && it.length() > 0 }

  /**
   * An install that ran on the plaintext fallback keeps its identity when the Keystore starts working: its values
   * move into the encrypted store (only if that store has no identity of its own yet) and the fallback is cleared.
   * Without this, a working Keystore would silently mint a new device id and admin token and strand that install.
   */
  private fun migrateFallback(app: Context, enc: SharedPreferences) {
    val fb = app.getSharedPreferences(FALLBACK_PREFS, Context.MODE_PRIVATE)
    val old = fb.all
    if (old.isEmpty()) return
    if (enc.getString(K_DEVICE_ID, null) == null) {
      val e = enc.edit()
      for ((k, v) in old) if (v is String) e.putString(k, v)
      if (!e.commit()) return // keep the fallback; try again next start
      android.util.Log.i(TAG, "migrated ${old.size} secret(s) from plaintext fallback into the Keystore-backed store")
    } else {
      android.util.Log.w(TAG, "plaintext fallback prefs exist beside an encrypted identity; keeping the encrypted one")
    }
    fb.edit().clear().commit()
  }

  private fun getOrCreate(key: String, gen: () -> String): String = synchronized(this) {
    prefs.getString(key, null) ?: gen().also { prefs.edit().putString(key, it).commit() }
  }

  val deviceId: String get() = getOrCreate(K_DEVICE_ID) { Ids.device() }
  val auditSeed: String get() = getOrCreate(K_AUDIT_SEED) { Ids.hex(32) }

  /** The admin bearer token. Created on first access. */
  val adminToken: String get() = getOrCreate(K_ADMIN_TOKEN) { Ids.token() }

  fun rotateAdminToken(): String = Ids.token().also { prefs.edit().putString(K_ADMIN_TOKEN, it).apply() }

  private companion object {
    const val TAG = "SecureStore"
    const val ENCRYPTED_PREFS = "symbiosis.secure"
    const val FALLBACK_PREFS = "symbiosis.secure.fallback"
    /** Plain, non-secret bookkeeping: only whether the encrypted store has ever opened on this install. */
    const val META_PREFS = "symbiosis.secure.meta"
    const val K_EVER_ENCRYPTED = "encrypted_ever_opened"
    const val OPEN_ATTEMPTS = 4
    val RETRY_DELAYS_MS = longArrayOf(250, 750, 1500)
    const val K_DEVICE_ID = "device_id"
    const val K_AUDIT_SEED = "audit_seed"
    const val K_ADMIN_TOKEN = "admin_token"
  }
}
