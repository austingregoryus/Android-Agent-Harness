package dev.symbiosis.runtime.capabilities

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.net.Uri
import android.provider.CalendarContract
import android.provider.MediaStore
import android.provider.Telephony
import dev.symbiosis.runtime.policy.ActionClass
import dev.symbiosis.runtime.policy.RateClass
import java.io.File
import java.util.TimeZone
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The capability families the contract names (docs/protocol.md §8) that droid-mcp does not cover:
 * `calendar.*`, `sensors.*`, `files.*`, and MMS under `messaging.*`. Plain content-provider and framework
 * reads, each classified the way the rest of the surface is: reads that expose personal data are
 * SENSITIVE_READ, local mutations are LOW_RISK with the MUTATION rate class.
 *
 * Deliberately not here: `camera.*`. Capturing from the background needs a camera foreground-service type and
 * Android 12+ blocks it unless the app is visible; that is a product decision, not a provider query.
 */
object DeviceExtras {

  fun all(context: Context): List<Capability> = calendar(context) + sensors(context) + files(context) + mms(context)

  // ---------------------------------------------------------------- calendar

  private const val P_CAL_READ = "android.permission.READ_CALENDAR"
  private const val P_CAL_WRITE = "android.permission.WRITE_CALENDAR"

  fun calendar(context: Context): List<Capability> = listOf(
    Capability(
      name = "calendar.list_events",
      description = "Events on the device's calendars in a time window (default: the next 7 days). Returns title, start/end (epoch ms and ISO), location, calendar name, all-day flag.",
      params = listOf(
        Param("days", "integer", "Window length in days from now (default 7, max 90)"),
        Param("from_ms", "integer", "Window start as epoch ms (default now)"),
        Param("limit", "integer", "Max events (default 50)"),
      ),
      actionClass = ActionClass.SENSITIVE_READ, requiredPermissions = listOf(P_CAL_READ),
    ) { args, _ ->
      val days = ((args["days"] as? Number)?.toInt() ?: 7).coerceIn(1, 90)
      val from = (args["from_ms"] as? Number)?.toLong() ?: System.currentTimeMillis()
      val limit = ((args["limit"] as? Number)?.toInt() ?: 50).coerceIn(1, 500)
      val to = from + days * 86_400_000L
      val uri = CalendarContract.Instances.CONTENT_URI.buildUpon().let { b -> ContentUris.appendId(b, from); ContentUris.appendId(b, to); b.build() }
      val cols = arrayOf(
        CalendarContract.Instances.EVENT_ID, CalendarContract.Instances.TITLE, CalendarContract.Instances.BEGIN,
        CalendarContract.Instances.END, CalendarContract.Instances.ALL_DAY, CalendarContract.Instances.EVENT_LOCATION,
        CalendarContract.Instances.CALENDAR_DISPLAY_NAME, CalendarContract.Instances.DESCRIPTION,
      )
      val out = mutableListOf<Map<String, Any?>>()
      context.contentResolver.query(uri, cols, null, null, "${CalendarContract.Instances.BEGIN} ASC")?.use { c ->
        while (c.moveToNext() && out.size < limit) {
          val begin = c.getLong(2); val end = c.getLong(3)
          out += mapOf(
            "event_id" to c.getLong(0), "title" to c.getString(1), "begin_ms" to begin, "end_ms" to end,
            "begin" to iso(begin), "end" to iso(end), "all_day" to (c.getInt(4) == 1),
            "location" to c.getString(5), "calendar" to c.getString(6), "description" to c.getString(7)?.take(500),
          )
        }
      }
      CapabilityResult.ok(mapOf("events" to out, "from_ms" to from, "to_ms" to to, "count" to out.size))
    },
    Capability(
      name = "calendar.create_event",
      description = "Create a calendar event on the first writable calendar (or calendar_id). Times are epoch ms; default duration 60 minutes.",
      params = listOf(
        Param("title", "string", "Event title", required = true),
        Param("start_ms", "integer", "Start, epoch ms", required = true),
        Param("end_ms", "integer", "End, epoch ms (default start + 60 min)"),
        Param("location", "string", "Location"),
        Param("description", "string", "Notes"),
        Param("calendar_id", "integer", "Calendar to write to (default: first writable)"),
        Param("all_day", "boolean", "All-day event (default false)"),
      ),
      actionClass = ActionClass.LOW_RISK, rateClass = RateClass.MUTATION, requiredPermissions = listOf(P_CAL_READ, P_CAL_WRITE),
    ) { args, _ ->
      val title = args["title"]?.toString()?.trim().orEmpty()
      val start = (args["start_ms"] as? Number)?.toLong()
      if (title.isEmpty() || start == null) return@Capability CapabilityResult.fail("invalid_params", "title and start_ms are required")
      val end = (args["end_ms"] as? Number)?.toLong() ?: (start + 3_600_000L)
      val calId = (args["calendar_id"] as? Number)?.toLong() ?: firstWritableCalendar(context)
        ?: return@Capability CapabilityResult.fail("capability.unavailable", "no writable calendar on this device")
      val values = ContentValues().apply {
        put(CalendarContract.Events.CALENDAR_ID, calId)
        put(CalendarContract.Events.TITLE, title)
        put(CalendarContract.Events.DTSTART, start)
        put(CalendarContract.Events.DTEND, end)
        put(CalendarContract.Events.EVENT_TIMEZONE, TimeZone.getDefault().id)
        put(CalendarContract.Events.ALL_DAY, if (args["all_day"] == true) 1 else 0)
        args["location"]?.toString()?.let { put(CalendarContract.Events.EVENT_LOCATION, it) }
        args["description"]?.toString()?.let { put(CalendarContract.Events.DESCRIPTION, it) }
      }
      val uri = context.contentResolver.insert(CalendarContract.Events.CONTENT_URI, values)
        ?: return@Capability CapabilityResult.fail("capability.failed", "insert returned null")
      CapabilityResult.ok(mapOf("event_id" to ContentUris.parseId(uri), "calendar_id" to calId, "title" to title, "start_ms" to start, "end_ms" to end))
    },
  )

  private fun firstWritableCalendar(context: Context): Long? =
    context.contentResolver.query(
      CalendarContract.Calendars.CONTENT_URI,
      arrayOf(CalendarContract.Calendars._ID, CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL, CalendarContract.Calendars.IS_PRIMARY),
      "${CalendarContract.Calendars.CALENDAR_ACCESS_LEVEL} >= ${CalendarContract.Calendars.CAL_ACCESS_CONTRIBUTOR}", null,
      "${CalendarContract.Calendars.IS_PRIMARY} DESC",
    )?.use { c -> if (c.moveToFirst()) c.getLong(0) else null }

  // ---------------------------------------------------------------- sensors

  private val SENSOR_TYPES = mapOf(
    "accelerometer" to Sensor.TYPE_ACCELEROMETER, "gyroscope" to Sensor.TYPE_GYROSCOPE, "magnetometer" to Sensor.TYPE_MAGNETIC_FIELD,
    "light" to Sensor.TYPE_LIGHT, "pressure" to Sensor.TYPE_PRESSURE, "proximity" to Sensor.TYPE_PROXIMITY,
    "gravity" to Sensor.TYPE_GRAVITY, "rotation" to Sensor.TYPE_ROTATION_VECTOR, "step_counter" to Sensor.TYPE_STEP_COUNTER,
    "humidity" to Sensor.TYPE_RELATIVE_HUMIDITY, "temperature" to Sensor.TYPE_AMBIENT_TEMPERATURE,
  )

  fun sensors(context: Context): List<Capability> = listOf(
    Capability(
      name = "sensors.list",
      description = "The sensors this device has, by the names sensors.read accepts, with vendor and power.",
      params = emptyList(),
      actionClass = ActionClass.READ_ONLY, tier = 0,
    ) { _, _ ->
      val sm = context.getSystemService(SensorManager::class.java)
      val present = SENSOR_TYPES.mapNotNull { (name, type) ->
        sm.getDefaultSensor(type)?.let { mapOf("name" to name, "vendor" to it.vendor, "model" to it.name, "power_ma" to it.power, "max_range" to it.maximumRange) }
      }
      CapabilityResult.ok(mapOf("sensors" to present))
    },
    Capability(
      name = "sensors.read",
      description = "One reading from each requested sensor (default: accelerometer, light, pressure, proximity). Waits up to timeout_ms for a value; sensors that report nothing in time are listed under 'missing'.",
      params = listOf(
        Param("sensors", "array", "Names from sensors.list (default: accelerometer, light, pressure, proximity)"),
        Param("timeout_ms", "integer", "How long to wait for each sensor (default 800, max 5000)"),
      ),
      actionClass = ActionClass.READ_ONLY, tier = 0,
    ) { args, _ ->
      val sm = context.getSystemService(SensorManager::class.java)
      val wanted = (args["sensors"] as? List<*>)?.mapNotNull { it?.toString() } ?: listOf("accelerometer", "light", "pressure", "proximity")
      val timeout = ((args["timeout_ms"] as? Number)?.toLong() ?: 800L).coerceIn(50, 5000)
      val readings = HashMap<String, Any?>()
      val missing = mutableListOf<String>()
      val latch = CountDownLatch(wanted.size)
      val listeners = mutableListOf<Pair<Sensor, SensorEventListener>>()
      for (name in wanted) {
        val type = SENSOR_TYPES[name]
        val sensor = type?.let { sm.getDefaultSensor(it) }
        if (sensor == null) { missing += name; latch.countDown(); continue }
        val l = object : SensorEventListener {
          override fun onSensorChanged(e: SensorEvent) {
            synchronized(readings) {
              if (!readings.containsKey(name)) {
                readings[name] = mapOf("values" to e.values.toList(), "accuracy" to e.accuracy, "timestamp_ns" to e.timestamp)
                latch.countDown()
              }
            }
          }
          override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
        }
        // Not FASTEST: on Android 12+ a rate above 200 Hz needs HIGH_SAMPLING_RATE_SENSORS; UI rate is ~60 ms.
        sm.registerListener(l, sensor, SensorManager.SENSOR_DELAY_UI)
        listeners += sensor to l
      }
      latch.await(timeout, TimeUnit.MILLISECONDS)
      for ((_, l) in listeners) sm.unregisterListener(l)
      for (name in wanted) if (name !in readings && name !in missing) missing += name
      CapabilityResult.ok(mapOf("readings" to synchronized(readings) { HashMap(readings) }, "missing" to missing))
    },
  )

  // ---------------------------------------------------------------- files

  private const val MAX_READ_BYTES = 512 * 1024

  fun files(context: Context): List<Capability> = listOf(
    Capability(
      name = "files.list",
      description = "List files in an area the runtime can reach: 'app' (its own external files dir) or 'downloads' (the device Downloads collection via MediaStore; on scoped storage only files this app created are visible).",
      params = listOf(
        Param("area", "string", "app (default) or downloads", enum = listOf("app", "downloads")),
        Param("limit", "integer", "Max entries (default 200)"),
      ),
      actionClass = ActionClass.SENSITIVE_READ,
    ) { args, _ ->
      val area = args["area"]?.toString() ?: "app"
      val limit = ((args["limit"] as? Number)?.toInt() ?: 200).coerceIn(1, 2000)
      val out = mutableListOf<Map<String, Any?>>()
      if (area == "downloads") {
        val cols = arrayOf(MediaStore.Downloads._ID, MediaStore.Downloads.DISPLAY_NAME, MediaStore.Downloads.SIZE, MediaStore.Downloads.MIME_TYPE, MediaStore.Downloads.DATE_MODIFIED)
        context.contentResolver.query(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cols, null, null, "${MediaStore.Downloads.DATE_MODIFIED} DESC")?.use { c ->
          while (c.moveToNext() && out.size < limit) {
            val id = c.getLong(0)
            out += mapOf("uri" to ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id).toString(), "name" to c.getString(1), "size" to c.getLong(2), "mime" to c.getString(3), "modified_s" to c.getLong(4))
          }
        }
      } else {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        dir.walkTopDown().filter { it.isFile }.take(limit).forEach { f ->
          out += mapOf("path" to f.absolutePath, "name" to f.name, "size" to f.length(), "modified_s" to f.lastModified() / 1000)
        }
      }
      CapabilityResult.ok(mapOf("area" to area, "files" to out, "count" to out.size))
    },
    Capability(
      name = "files.read",
      description = "Read a text file: a path from files.list (area app) or a content:// uri (area downloads). Capped at 512 KB; binary content comes back base64 when as_base64 is true.",
      params = listOf(
        Param("path", "string", "Absolute path under the app area, or a content:// uri", required = true),
        Param("as_base64", "boolean", "Return bytes as base64 instead of text (default false)"),
        Param("max_bytes", "integer", "Cap (default and max 524288)"),
      ),
      actionClass = ActionClass.SENSITIVE_READ,
    ) { args, _ ->
      val path = args["path"]?.toString()?.trim().orEmpty()
      if (path.isEmpty()) return@Capability CapabilityResult.fail("invalid_params", "path is required")
      val cap = ((args["max_bytes"] as? Number)?.toInt() ?: MAX_READ_BYTES).coerceIn(1, MAX_READ_BYTES)
      val bytes: ByteArray = if (path.startsWith("content://")) {
        val uri = Uri.parse(path)
        // A files-only peer must not borrow the runtime's READ_SMS/READ_CONTACTS permissions by passing
        // another provider's URI. Accept exactly the individual Downloads entries returned by files.list.
        val segments = uri.pathSegments
        if (uri.authority != MediaStore.AUTHORITY || segments.size != 3 ||
          segments[0] != "external" || segments[1] != "downloads" ||
          segments[2].toLongOrNull()?.let { it >= 0 } != true || uri.query != null || uri.fragment != null) {
          return@Capability CapabilityResult.fail("policy.denied", "content URI is outside the Downloads area")
        }
        context.contentResolver.openInputStream(uri)?.use { readUpTo(it, cap) }
          ?: return@Capability CapabilityResult.fail("not_found", path)
      } else {
        val root = (context.getExternalFilesDir(null) ?: context.filesDir).canonicalFile
        val f = File(path).canonicalFile
        // Path-component containment, not a string prefix: "<files>-other/x" must not pass as inside "<files>".
        if (!f.toPath().normalize().startsWith(root.toPath().normalize())) {
          return@Capability CapabilityResult.fail("policy.denied", "path is outside the app area")
        }
        if (!f.isFile) return@Capability CapabilityResult.fail("not_found", path)
        f.inputStream().use { readUpTo(it, cap) }
      }
      val b64 = args["as_base64"] == true
      CapabilityResult.ok(
        mapOf(
          "path" to path, "bytes" to bytes.size, "truncated" to (bytes.size >= cap),
          if (b64) "base64" to android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP) else "text" to String(bytes, Charsets.UTF_8),
        ),
      )
    },
    Capability(
      name = "files.write",
      description = "Write a text file. area 'app' (default) writes under the app's external files dir; 'downloads' creates it in Downloads/Symbiosis via MediaStore where the user can see it.",
      params = listOf(
        Param("name", "string", "File name (no directories)", required = true),
        Param("text", "string", "Contents", required = true),
        Param("area", "string", "app (default) or downloads", enum = listOf("app", "downloads")),
        Param("mime", "string", "MIME type for downloads (default text/plain)"),
      ),
      actionClass = ActionClass.LOW_RISK, rateClass = RateClass.MUTATION,
    ) { args, _ ->
      val name = args["name"]?.toString()?.trim().orEmpty()
      val text = args["text"]?.toString() ?: ""
      if (name.isEmpty() || name.contains('/') || name.contains('\\') || name.startsWith(".")) return@Capability CapabilityResult.fail("invalid_params", "name must be a plain file name")
      if (args["area"]?.toString() == "downloads") {
        val values = ContentValues().apply {
          put(MediaStore.Downloads.DISPLAY_NAME, name)
          put(MediaStore.Downloads.MIME_TYPE, args["mime"]?.toString() ?: "text/plain")
          put(MediaStore.Downloads.RELATIVE_PATH, "Download/Symbiosis")
        }
        val uri = context.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
          ?: return@Capability CapabilityResult.fail("capability.failed", "MediaStore insert failed")
        context.contentResolver.openOutputStream(uri)?.use { it.write(text.toByteArray()) }
          ?: return@Capability CapabilityResult.fail("capability.failed", "could not open $uri for writing")
        CapabilityResult.ok(mapOf("uri" to uri.toString(), "name" to name, "bytes" to text.toByteArray().size))
      } else {
        val dir = context.getExternalFilesDir(null) ?: context.filesDir
        val f = File(dir, name)
        f.writeText(text)
        CapabilityResult.ok(mapOf("path" to f.absolutePath, "name" to name, "bytes" to f.length()))
      }
    },
  )

  // ---------------------------------------------------------------- mms

  fun mms(context: Context): List<Capability> = listOf(
    Capability(
      name = "messaging.read_mms",
      description = "Recent MMS messages with their text parts and attachment metadata (name, MIME type, part uri). Inbox and sent.",
      params = listOf(Param("limit", "integer", "Max messages (default 20, max 100)")),
      actionClass = ActionClass.SENSITIVE_READ, requiredPermissions = listOf("android.permission.READ_SMS"),
    ) { args, _ ->
      val limit = ((args["limit"] as? Number)?.toInt() ?: 20).coerceIn(1, 100)
      val out = mutableListOf<Map<String, Any?>>()
      val cr = context.contentResolver
      cr.query(Telephony.Mms.CONTENT_URI, arrayOf(Telephony.Mms._ID, Telephony.Mms.DATE, Telephony.Mms.MESSAGE_BOX, Telephony.Mms.SUBJECT, Telephony.Mms.READ), null, null, "${Telephony.Mms.DATE} DESC")?.use { c ->
        while (c.moveToNext() && out.size < limit) {
          val id = c.getLong(0)
          val texts = mutableListOf<String>()
          val attachments = mutableListOf<Map<String, Any?>>()
          cr.query(Uri.parse("content://mms/part"), arrayOf("_id", "ct", "name", "text", "_data"), "mid = ?", arrayOf(id.toString()), null)?.use { p ->
            while (p.moveToNext()) {
              val ct = p.getString(1) ?: ""
              val pid = p.getLong(0)
              when {
                ct == "text/plain" -> {
                  val t = p.getString(3) ?: runCatching { cr.openInputStream(Uri.parse("content://mms/part/$pid"))?.use { String(it.readBytes(), Charsets.UTF_8) } }.getOrNull()
                  if (!t.isNullOrBlank()) texts += t
                }
                ct == "application/smil" -> {}
                else -> attachments += mapOf("uri" to "content://mms/part/$pid", "mime" to ct, "name" to p.getString(2))
              }
            }
          }
          var from: String? = null
          cr.query(Uri.parse("content://mms/$id/addr"), arrayOf("address", "type"), null, null, null)?.use { a ->
            while (a.moveToNext()) if (a.getInt(1) == 137) from = a.getString(0)
          }
          out += mapOf(
            "id" to id, "date_ms" to c.getLong(1) * 1000, "date" to iso(c.getLong(1) * 1000),
            "box" to when (c.getInt(2)) { 1 -> "inbox"; 2 -> "sent"; 3 -> "draft"; 4 -> "outbox"; else -> "other" },
            "from" to from, "subject" to c.getString(3), "read" to (c.getInt(4) == 1),
            "text" to texts.joinToString("\n"), "attachments" to attachments,
          )
        }
      }
      CapabilityResult.ok(mapOf("messages" to out, "count" to out.size))
    },
  )

  private fun iso(ms: Long): String = java.time.Instant.ofEpochMilli(ms).toString()
}

/**
 * Read at most [cap] bytes. `InputStream.readNBytes(int)` would do this, but it only exists on Android 13+
 * (API 33), and this app runs on Android 10 up (minSdk 29): on a OnePlus 7 Pro running Android 11 it threw
 * `NoSuchMethodError` and `files.read` failed outright. The Pixel is on Android 16, so it never showed.
 */
private fun readUpTo(input: java.io.InputStream, cap: Int): ByteArray {
  val out = java.io.ByteArrayOutputStream(minOf(cap, 64 * 1024))
  val buf = ByteArray(minOf(cap, 16 * 1024))
  var left = cap
  while (left > 0) {
    val n = input.read(buf, 0, minOf(buf.size, left))
    if (n < 0) break
    out.write(buf, 0, n)
    left -= n
  }
  return out.toByteArray()
}
