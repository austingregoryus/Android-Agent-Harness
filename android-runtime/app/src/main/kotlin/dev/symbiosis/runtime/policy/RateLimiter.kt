package dev.symbiosis.runtime.policy

import android.content.ContentValues
import dev.symbiosis.runtime.core.Ids
import dev.symbiosis.runtime.storage.Db

/**
 * Persisted sliding-window counters so quotas survive process restarts. Usage is *reserved* at check
 * time inside one transaction (so a burst cannot walk past the quota) and released if the action never
 * executes.
 */
class RateLimiter(private val db: Db) {
  data class Check(val allowed: Boolean, val retryAfterMs: Long = 0, val hourUsed: Int = 0, val dayUsed: Int = 0, val reservationId: Long = 0)

  /** Atomically checks the quota and, if allowed, reserves one slot. */
  fun reserve(rateClass: RateClass, limit: RateLimit, now: Long = Ids.now()): Check {
    if (rateClass == RateClass.NONE) return Check(true)
    val w = db.writableDatabase
    w.beginTransactionNonExclusive()
    try {
      prune(now)
      val hourAgo = now - HOUR
      val dayAgo = now - DAY
      val hour = count(rateClass, hourAgo)
      val day = count(rateClass, dayAgo)
      if (hour >= limit.perHour) return Check(false, oldestAfter(rateClass, hourAgo)?.let { it + HOUR - now } ?: HOUR, hour, day)
      if (day >= limit.perDay) return Check(false, oldestAfter(rateClass, dayAgo)?.let { it + DAY - now } ?: DAY, hour, day)
      val id = w.insert("rate_counters", null, ContentValues().apply { put("rate_class", rateClass.name); put("ts", now) })
      w.setTransactionSuccessful()
      return Check(true, 0, hour + 1, day + 1, id)
    } finally { w.endTransaction() }
  }

  /** Gives a reserved slot back (the action was denied, failed before any side effect, or never ran). */
  fun release(reservationId: Long) {
    if (reservationId <= 0) return
    db.writableDatabase.delete("rate_counters", "rowid = ?", arrayOf(reservationId.toString()))
  }

  fun usage(rateClass: RateClass, now: Long = Ids.now()): Pair<Int, Int> = count(rateClass, now - HOUR) to count(rateClass, now - DAY)

  private fun count(rc: RateClass, since: Long): Int = db.readableDatabase.compileStatement(
    "SELECT COUNT(*) FROM rate_counters WHERE rate_class = ? AND ts > ?",
  ).use { it.bindString(1, rc.name); it.bindLong(2, since); it.simpleQueryForLong().toInt() }

  private fun oldestAfter(rc: RateClass, since: Long): Long? = db.readableDatabase.rawQuery(
    "SELECT MIN(ts) FROM rate_counters WHERE rate_class = ? AND ts > ?", arrayOf(rc.name, since.toString()),
  ).use { c -> if (c.moveToFirst() && !c.isNull(0)) c.getLong(0) else null }

  private fun prune(now: Long) {
    db.writableDatabase.delete("rate_counters", "ts < ?", arrayOf((now - DAY - HOUR).toString()))
  }

  companion object {
    const val HOUR = 60 * 60 * 1000L
    const val DAY = 24 * HOUR
  }
}
