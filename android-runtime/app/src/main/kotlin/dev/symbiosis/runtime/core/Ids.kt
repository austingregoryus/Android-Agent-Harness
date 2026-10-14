package dev.symbiosis.runtime.core

import java.security.MessageDigest
import java.security.SecureRandom

/** Identifier and hashing helpers shared across the runtime. */
object Ids {
  private val rng = SecureRandom()
  private const val HEX = "0123456789abcdef"

  fun hex(bytes: Int): String {
    val b = ByteArray(bytes).also { rng.nextBytes(it) }
    return buildString(bytes * 2) { for (x in b) { append(HEX[(x.toInt() shr 4) and 0xf]); append(HEX[x.toInt() and 0xf]) } }
  }

  fun correlation(): String = "corr_" + hex(8)
  fun peer(): String = "peer_" + hex(6)
  fun subscription(): String = "sub_" + hex(6)
  fun session(kind: String): String = "${kind}_" + hex(8)
  fun pendingAction(): String = "pa_" + hex(6)
  fun device(): String = "dev_" + hex(6)

  /** 32 random bytes, base64url without padding — the bearer token format. */
  fun token(): String {
    val b = ByteArray(32).also { rng.nextBytes(it) }
    return android.util.Base64.encodeToString(b, android.util.Base64.URL_SAFE or android.util.Base64.NO_WRAP or android.util.Base64.NO_PADDING)
  }

  /** Human-typeable one-time pairing code: XXXX-XXXX from an unambiguous alphabet. */
  fun pairingCode(): String {
    val alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    val s = (0 until 8).map { alphabet[rng.nextInt(alphabet.length)] }.joinToString("")
    return s.substring(0, 4) + "-" + s.substring(4)
  }

  fun sha256Hex(input: String): String = sha256Hex(input.toByteArray(Charsets.UTF_8))

  fun sha256Hex(input: ByteArray): String {
    val d = MessageDigest.getInstance("SHA-256").digest(input)
    return buildString(64) { for (x in d) { append(HEX[(x.toInt() shr 4) and 0xf]); append(HEX[x.toInt() and 0xf]) } }
  }

  fun now(): Long = System.currentTimeMillis()
}
