package dev.symbiosis.runtime

import android.content.Context
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import dev.symbiosis.runtime.capabilities.CallContext
import dev.symbiosis.runtime.capabilities.DeviceExtras
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class FileScopeReviewTest {
  @Test fun `file reads cannot borrow permissions for unrelated content providers`() = runBlocking {
    val context = ApplicationProvider.getApplicationContext<Context>()
    val read = DeviceExtras.files(context).single { it.name == "files.read" }
    val ctx = CallContext("files-only", "scope-test")
    val outside = listOf(
      "content://mms/part/1",
      "content://com.android.contacts/contacts/1/photo",
      "content://media/external/images/media/1",
      "content://media/external/downloads",
      "content://media/external/downloads/1/extra",
      "content://media/external/downloads/1?redirect=1",
    )
    for (path in outside) {
      shadowOf(context.contentResolver).registerInputStream(Uri.parse(path), "private".byteInputStream())
      val result = read.execute(mapOf("path" to path), ctx)
      assertEquals(path, "policy.denied", result.errorCode)
    }
    val download = "content://media/external/downloads/1"
    shadowOf(context.contentResolver).registerInputStream(Uri.parse(download), "download".byteInputStream())
    val permitted = read.execute(mapOf("path" to download), ctx)
    assertTrue(permitted.ok)
    assertEquals("download", permitted.data["text"])
  }
}
