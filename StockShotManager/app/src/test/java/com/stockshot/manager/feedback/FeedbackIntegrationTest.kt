package com.stockshot.manager.feedback

import android.app.Activity
import android.app.Application
import android.content.ComponentName
import android.net.Uri
import com.androidshared.feedback.DefaultFeedbackStorage
import com.androidshared.feedback.FeedbackActivity
import com.androidshared.feedback.FeedbackFileProvider
import com.androidshared.feedback.FeedbackKit
import com.androidshared.feedback.FeedbackRepository
import com.stockshot.manager.BuildConfig
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class FeedbackIntegrationTest {
    private val context: Application get() = RuntimeEnvironment.getApplication()

    @Test
    fun privateComponents() {
        assertEquals(BuildConfig.APPLICATION_ID, context.packageName)
        val authority = "${context.packageName}.feedback.files"
        val provider = requireNotNull(context.packageManager.resolveContentProvider(authority, 0))
        assertEquals(FeedbackFileProvider::class.java.name, provider.name)
        assertEquals(authority, provider.authority)
        assertFalse(provider.exported)
        assertTrue(provider.grantUriPermissions)
        val activity = context.packageManager.getActivityInfo(
            ComponentName(context, FeedbackActivity::class.java), 0,
        )
        assertFalse(activity.exported)
        assertEquals(context.packageName, activity.packageName)
    }

    @Test
    fun listAndQuickEntry() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        val activity = controller.get()
        try {
            FeedbackKit.openList(activity)
            val listIntent = shadowOf(activity).nextStartedActivity
            assertEquals(ComponentName(context, FeedbackActivity::class.java), listIntent.component)
            assertFalse(listIntent.getBooleanExtra(FeedbackKit.QUICK_COMPOSE, false))

            FeedbackKit.newIdea(activity, "设置页")
            val ideaIntent = shadowOf(activity).nextStartedActivity
            assertEquals(ComponentName(context, FeedbackActivity::class.java), ideaIntent.component)
            assertTrue(ideaIntent.getBooleanExtra(FeedbackKit.QUICK_COMPOSE, false))
            assertEquals("设置页", ideaIntent.getStringExtra(FeedbackKit.PAGE_LABEL))
            assertEquals(context.packageName, FeedbackKit.origin(context, "设置页").getString("appId"))
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun backupRoundTrip() {
        val storage = DefaultFeedbackStorage(context)
        val repo = FeedbackKit.repository(context)
        val note = saveNote(repo, "原始建议")
        val draft = repo.beginDraft(FeedbackKit.origin(context, "设置页"), null)
        repo.updateDraft(draft.getString("id"), JSONObject().put("body", "未完成草稿"))
        val archive = export(storage)
        val generation = storage.generation()

        repo.discardDraft(draft.getString("id"))
        val extra = saveNote(repo, "备份后新增")
        assertNotNull(repo.get(extra.getString("id")))
        restore(storage, archive)

        // Recreate the same default repository used by the module's pages after replacement.
        val restored = FeedbackKit.repository(context)
        assertTrue(storage.generation() > generation)
        assertEquals("原始建议", restored.get(note.getString("id")).getString("body"))
        assertEquals(listOf(note.getString("id")), restored.list("", "", 100, 0).map { it.getString("id") })
        assertEquals(draft.getString("id"), restored.draft().getString("id"))
        assertEquals("未完成草稿", restored.draft().getJSONObject("note").getString("body"))
    }

    @Test
    fun corruptBackupKeepsData() {
        val storage = DefaultFeedbackStorage(context)
        val repo = FeedbackKit.repository(context)
        val note = saveNote(repo, "原始建议")
        val archive = export(storage)
        val generation = storage.generation()
        val before = repo.get(note.getString("id")).toString()
        val corrupt = alterRecord(archive)

        try {
            restore(storage, corrupt)
            fail("A ZIP with modified records must fail its backup checksum")
        } catch (_: Exception) {
            // The real AAR validates a separate staging database before replacing live data.
        }
        assertEquals(generation, storage.generation())
        assertEquals(before, repo.get(note.getString("id")).toString())
        assertEquals(1, repo.list("", "", 100, 0).size)
        assertNull(repo.draft())
    }

    private fun saveNote(repo: FeedbackRepository, body: String): JSONObject {
        val draft = repo.beginDraft(FeedbackKit.origin(context, "设置页"), null)
        repo.updateDraft(draft.getString("id"), JSONObject().put("body", body))
        return repo.saveDraft(draft.getString("id"))
    }

    private fun export(storage: DefaultFeedbackStorage): ByteArray {
        val uri = Uri.parse("content://backup.test/document/feedback.zip")
        val output = ByteArrayOutputStream()
        shadowOf(context.contentResolver).registerOutputStream(uri, output)
        storage.exportBackup(uri)
        return output.toByteArray().also { assertTrue(it.isNotEmpty()) }
    }

    private fun restore(storage: DefaultFeedbackStorage, archive: ByteArray) {
        val uri = Uri.parse("content://backup.test/document/restore.zip")
        shadowOf(context.contentResolver).registerInputStream(uri, ByteArrayInputStream(archive))
        storage.restoreBackup(uri)
    }

    private fun alterRecord(archive: ByteArray): ByteArray {
        val result = ByteArrayOutputStream()
        var changed = false
        ZipOutputStream(result).use { output ->
            ZipInputStream(ByteArrayInputStream(archive)).use { input ->
                while (true) {
                    val entry = input.nextEntry ?: break
                    val original = input.readBytes()
                    val content = if (entry.name == "records.jsonl") {
                        val text = original.toString(Charsets.UTF_8)
                        assertTrue(text.contains("原始建议"))
                        changed = true
                        text.replace("原始建议", "篡改建议").toByteArray(Charsets.UTF_8)
                    } else original
                    output.putNextEntry(ZipEntry(entry.name))
                    output.write(content)
                    output.closeEntry()
                }
            }
        }
        assertTrue(changed)
        return result.toByteArray()
    }
}
