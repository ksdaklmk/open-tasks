package app.opentasks.core.data.export

import app.opentasks.core.domain.DefaultInsightsEngine
import app.opentasks.core.domain.InsightsEngine
import app.opentasks.core.model.ActivityEntry
import app.opentasks.core.model.ActivityKind
import app.opentasks.core.model.Attachment
import app.opentasks.core.model.AttachmentId
import app.opentasks.core.model.HomeSnapshot
import app.opentasks.core.model.InsightsSelection
import app.opentasks.core.model.InsightsSnapshot
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.WorkspaceSnapshot
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStream
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Base64
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ExecutiveDashboardHtmlWriterTest {
    private val now = Instant.parse("2026-08-21T09:30:00Z")
    private val zoneId = ZoneId.of("Asia/Bangkok")

    @Test
    fun frozenRequestProducesOneDeterministicDocumentWithAllSections() {
        val engine = RecordingInsightsEngine()
        val writer = ExecutiveDashboardHtmlWriter(engine)
        val request = request()

        val first = write(writer, request)
        val second = write(writer, request)

        assertArrayEquals(first, second)
        assertEquals(first.size.toLong(), writer.write(request, ByteArrayOutputStream()))
        assertTrue(first.toString(Charsets.UTF_8).startsWith("<!doctype html>"))
        val html = first.toString(Charsets.UTF_8)
        assertEquals(1, Regex("<!doctype html>", RegexOption.IGNORE_CASE).findAll(html).count())
        assertTrue(html.contains("<html lang=\"en-GB\">"))
        REQUIRED_HEADINGS.forEach { heading -> assertTrue(heading, html.contains(heading)) }
        assertTrue(html.contains("Snapshot frozen at 2026-08-21T09:30:00Z"))
        assertTrue(html.contains("Budget, cost, team capacity, resource allocation, and benefits are not tracked in Open Tasks."))
        assertEquals(3, engine.requests.size)
        engine.requests.forEach { captured ->
            assertSame(request.workspace, captured.workspace)
            assertEquals(request.selection, captured.selection)
            assertEquals(now, captured.now)
            assertEquals(zoneId, captured.zoneId)
        }
    }

    @Test
    fun aggregateModeOmitsTaskAndPrivateContent() {
        val task = OpenTasksFixtures.tasks.first().copy(
            title = "PRIVATE_TASK_TITLE",
            description = "PRIVATE_DESCRIPTION",
        )
        val attachment = Attachment(
            id = AttachmentId("attachment-private"),
            taskId = task.id,
            displayName = "PRIVATE_ATTACHMENT_NAME.pdf",
            mimeType = "application/pdf",
            byteCount = 42,
            contentHash = "PRIVATE_CONTENT_HASH",
            blobSetId = null,
            chunkCount = 0,
            deletedAt = null,
            revision = task.revision,
        )
        val workspace = OpenTasksFixtures.snapshot.copy(
            tasks = listOf(task),
            projects = listOf(OpenTasksFixtures.studioProject),
            notes = OpenTasksFixtures.notes.map { it.copy(body = "PRIVATE_NOTE_BODY") },
            attachments = listOf(attachment),
            activityEntries = listOf(
                ActivityEntry(
                    id = "activity-private",
                    taskId = task.id,
                    projectId = task.projectId,
                    kind = ActivityKind.RECORD_CREATED,
                    body = "PRIVATE_ACTIVITY_BODY",
                    createdAt = now,
                ),
            ),
        )

        val html = write(
            ExecutiveDashboardHtmlWriter(DefaultInsightsEngine()),
            request(workspace = workspace, includeTaskDetails = false),
        ).toString(Charsets.UTF_8)

        listOf(
            "PRIVATE_TASK_TITLE",
            "PRIVATE_DESCRIPTION",
            "PRIVATE_NOTE_BODY",
            "PRIVATE_ATTACHMENT_NAME.pdf",
            "PRIVATE_CONTENT_HASH",
            "PRIVATE_ACTIVITY_BODY",
            OpenTasksFixtures.studioProject.summary,
        ).forEach { forbidden -> assertFalse(forbidden, html.contains(forbidden)) }
        assertTrue(data(html)["taskDetails"]!!.jsonArray.isEmpty())
        assertTrue(html.contains("Task details were not included in this aggregate export."))
    }

    @Test
    fun detailModeIncludesOnlyApprovedTaskFields() {
        val task = OpenTasksFixtures.tasks.first().copy(
            title = "Allowed task title",
            description = "FORBIDDEN_DESCRIPTION",
        )
        val blocker = OpenTasksFixtures.tasks[3].copy(title = "Allowed blocker title")
        val detailedTask = task.copy(blockedBy = setOf(blocker.id))
        val workspace = OpenTasksFixtures.snapshot.copy(
            tasks = listOf(detailedTask, blocker),
            notes = OpenTasksFixtures.notes.map { it.copy(body = "FORBIDDEN_NOTE") },
            attachments = listOf(
                Attachment(
                    id = AttachmentId("attachment-secret"),
                    taskId = detailedTask.id,
                    displayName = "FORBIDDEN_ATTACHMENT",
                    mimeType = "text/plain",
                    byteCount = 1,
                    contentHash = "FORBIDDEN_HASH",
                    blobSetId = null,
                    chunkCount = 0,
                    deletedAt = null,
                    revision = detailedTask.revision,
                ),
            ),
        )

        val html = write(
            ExecutiveDashboardHtmlWriter(DefaultInsightsEngine()),
            request(workspace = workspace, includeTaskDetails = true),
        ).toString(Charsets.UTF_8)
        val details = data(html)["taskDetails"]!!.jsonArray
        val row = details.first { element ->
            element.jsonObject["title"]!!.jsonPrimitive.content == detailedTask.title
        }.jsonObject

        assertEquals(
            setOf(
                "title",
                "project",
                "status",
                "priority",
                "start",
                "due",
                "estimateSeconds",
                "actualSeconds",
                "blockedBy",
            ),
            row.keys,
        )
        assertEquals(OpenTasksFixtures.studioProject.name, row["project"]!!.jsonPrimitive.content)
        assertEquals("STARTED", row["status"]!!.jsonPrimitive.content)
        assertEquals("HIGH", row["priority"]!!.jsonPrimitive.content)
        assertEquals(7_200L, row["estimateSeconds"]!!.jsonPrimitive.content.toLong())
        assertEquals(
            listOf(blocker.title),
            row["blockedBy"]!!.jsonArray.map { it.jsonPrimitive.content },
        )
        listOf(
            "FORBIDDEN_DESCRIPTION",
            "FORBIDDEN_NOTE",
            "FORBIDDEN_ATTACHMENT",
            "FORBIDDEN_HASH",
        ).forEach { forbidden -> assertFalse(forbidden, html.contains(forbidden)) }
    }

    @Test
    fun hostileLabelsRoundTripThroughJsonAndAreEscapedInNoScriptSummary() {
        val hostileProject = "Project </script> \"' & \u2028\u2029"
        val hostileTag = "Tag <tag> & \"quoted\" \u2028\u2029"
        val hostileTask = "Task </script> & \"quoted\" \u2028\u2029"
        val project = OpenTasksFixtures.studioProject.copy(name = hostileProject)
        val tag = OpenTasksFixtures.tags.first().copy(name = hostileTag)
        val task = OpenTasksFixtures.tasks.first().copy(
            projectId = project.id,
            tagIds = setOf(tag.id),
            title = hostileTask,
        )
        val workspace = OpenTasksFixtures.snapshot.copy(
            tasks = listOf(task),
            projects = listOf(project),
            tags = listOf(tag),
            milestones = emptyList(),
            notes = emptyList(),
            attachments = emptyList(),
        )
        val selection = InsightsSelection(
            projectIds = setOf(project.id),
            tagIds = setOf(tag.id),
        )

        val html = write(
            ExecutiveDashboardHtmlWriter(DefaultInsightsEngine()),
            request(
                workspace = workspace,
                selection = selection,
                includeTaskDetails = true,
            ),
        ).toString(Charsets.UTF_8)
        val dashboard = data(html)

        assertEquals(
            hostileProject,
            dashboard["scope"]!!.jsonObject["projects"]!!.jsonArray.single().jsonPrimitive.content,
        )
        assertEquals(
            hostileTag,
            dashboard["scope"]!!.jsonObject["tags"]!!.jsonArray.single().jsonPrimitive.content,
        )
        assertEquals(
            hostileTask,
            dashboard["taskDetails"]!!.jsonArray.single().jsonObject["title"]!!.jsonPrimitive.content,
        )
        assertTrue(html.contains("Project &lt;/script&gt; &quot;&#39; &amp; &#8232;&#8233;"))
        assertTrue(html.contains("Tag &lt;tag&gt; &amp; &quot;quoted&quot; &#8232;&#8233;"))
        assertTrue(html.contains("\\u003c/script\\u003e"))
        assertTrue(html.contains("\\u2028"))
        assertTrue(html.contains("\\u2029"))
        assertEquals(2, Regex("</script>", RegexOption.IGNORE_CASE).findAll(html).count())
        assertFalse(html.contains(hostileProject))
        assertFalse(html.contains(hostileTag))
        assertFalse(html.contains(hostileTask))
    }

    @Test
    fun cspHashesMatchExactStaticCssAndJavaScript() {
        val html = write(
            ExecutiveDashboardHtmlWriter(DefaultInsightsEngine()),
            request(),
        ).toString(Charsets.UTF_8)
        val css = capture(html, "<style>", "</style>")
        val javascript = capture(html, "<script id=\"dashboard-app\">", "</script>")
        val csp = Regex(
            "<meta http-equiv=\"Content-Security-Policy\" content=\"([^\"]+)\">",
        ).find(html)!!.groupValues[1]

        assertEquals(
            "default-src 'none'; connect-src 'none'; img-src 'none'; object-src 'none'; " +
                "base-uri 'none'; form-action 'none'; style-src 'sha256-${sha256(css)}'; " +
                "script-src 'sha256-${sha256(javascript)}'",
            csp,
        )
        assertFalse(csp.contains("unsafe-inline"))
    }

    @Test
    fun documentIsOfflineKeyboardAndPrintSafe() {
        val html = write(
            ExecutiveDashboardHtmlWriter(DefaultInsightsEngine()),
            request(),
        ).toString(Charsets.UTF_8)

        listOf(
            "https://",
            "http://",
            "fetch(",
            "WebSocket",
            "innerHTML",
            "eval(",
            "new Function",
            "@import",
            "url(",
        ).forEach { forbidden -> assertFalse(forbidden, html.contains(forbidden, ignoreCase = true)) }
        assertTrue(html.contains("id=\"project-filter\""))
        assertTrue(html.contains("id=\"status-filter\""))
        assertTrue(html.contains("id=\"risk-filter\""))
        assertTrue(html.contains("<details"))
        assertTrue(html.contains(":focus-visible"))
        assertTrue(html.contains("@media (prefers-reduced-motion: reduce)"))
        assertTrue(html.contains("@media print"))
        assertTrue(html.contains("role=\"img\""))
        assertTrue(html.contains("aria-label=\"Completion trend"))
        assertTrue(html.contains("textContent"))
    }

    @Test
    fun emptyWorkspaceRendersHonestEmptyStates() {
        val html = write(
            ExecutiveDashboardHtmlWriter(DefaultInsightsEngine()),
            request(workspace = emptyWorkspace()),
        ).toString(Charsets.UTF_8)
        val dashboard = data(html)

        assertTrue(html.contains("No active task data was available in this frozen snapshot."))
        assertEquals(JsonArray(emptyList()), dashboard["projects"])
        assertEquals(JsonArray(emptyList()), dashboard["milestones"])
        assertEquals(JsonArray(emptyList()), dashboard["blockers"])
        assertEquals(JsonArray(emptyList()), dashboard["taskDetails"])
    }

    @Test
    fun byteLimitAllowsExactBoundaryAndNeverWritesBeyondIt() {
        val destination = CountingOutputStream()
        val bounded = DashboardByteLimitOutputStream(
            delegate = destination,
            maximumBytes = ExecutiveDashboardHtmlWriter.MAX_DASHBOARD_BYTES,
        )
        val chunk = ByteArray(64 * 1024)
        repeat(
            (ExecutiveDashboardHtmlWriter.MAX_DASHBOARD_BYTES / chunk.size).toInt(),
        ) { bounded.write(chunk) }

        assertEquals(ExecutiveDashboardHtmlWriter.MAX_DASHBOARD_BYTES, bounded.byteCount)
        assertEquals(ExecutiveDashboardHtmlWriter.MAX_DASHBOARD_BYTES, destination.byteCount)
        assertThrows(IOException::class.java) { bounded.write(0) }
        assertEquals(ExecutiveDashboardHtmlWriter.MAX_DASHBOARD_BYTES, destination.byteCount)

        val writerDestination = CountingOutputStream()
        val failure = assertThrows(IOException::class.java) {
            ExecutiveDashboardHtmlWriter(
                insightsEngine = DefaultInsightsEngine(),
                maximumBytes = 8 * 1024,
            ).write(request(), writerDestination)
        }
        assertTrue(failure.message!!.contains("8,192"))
        assertTrue(writerDestination.byteCount <= 8 * 1024)
    }

    private fun request(
        workspace: WorkspaceSnapshot = OpenTasksFixtures.snapshot,
        selection: InsightsSelection = InsightsSelection(),
        includeTaskDetails: Boolean = false,
    ) = ExecutiveDashboardRequest(
        workspace = workspace,
        selection = selection,
        now = now,
        zoneId = zoneId,
        includeTaskDetails = includeTaskDetails,
    )

    private fun emptyWorkspace() = WorkspaceSnapshot(
        home = HomeSnapshot(
            today = LocalDate.of(2026, 8, 21),
            focusTasks = emptyList(),
            upcomingTasks = emptyList(),
            projects = emptyList(),
            activeTimer = null,
            overdueCount = 0,
        ),
        tasks = emptyList(),
        projects = emptyList(),
        workflowStatuses = emptyList(),
        milestones = emptyList(),
        tags = emptyList(),
    )

    private fun write(
        writer: ExecutiveDashboardHtmlWriter,
        request: ExecutiveDashboardRequest,
    ): ByteArray = ByteArrayOutputStream().also { writer.write(request, it) }.toByteArray()

    private fun data(html: String): JsonObject = Json.parseToJsonElement(
        capture(
            html,
            "<script id=\"dashboard-data\" type=\"application/json\">",
            "</script>",
        ),
    ).jsonObject

    private fun capture(html: String, prefix: String, suffix: String): String {
        val start = html.indexOf(prefix)
        require(start >= 0) { "Missing $prefix" }
        val contentStart = start + prefix.length
        val end = html.indexOf(suffix, contentStart)
        require(end >= 0) { "Missing $suffix" }
        return html.substring(contentStart, end)
    }

    private fun sha256(value: String): String = Base64.getEncoder().encodeToString(
        MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8)),
    )

    private data class CapturedRequest(
        val workspace: WorkspaceSnapshot,
        val selection: InsightsSelection,
        val now: Instant,
        val zoneId: ZoneId,
    )

    private class RecordingInsightsEngine : InsightsEngine {
        private val delegate = DefaultInsightsEngine()
        val requests = mutableListOf<CapturedRequest>()

        override fun calculate(
            workspace: WorkspaceSnapshot,
            selection: InsightsSelection,
            now: Instant,
            zoneId: ZoneId,
        ): InsightsSnapshot {
            requests += CapturedRequest(workspace, selection, now, zoneId)
            return delegate.calculate(workspace, selection, now, zoneId)
        }
    }

    private class CountingOutputStream : OutputStream() {
        var byteCount = 0L
            private set

        override fun write(value: Int) {
            byteCount++
        }

        override fun write(bytes: ByteArray, offset: Int, length: Int) {
            byteCount += length
        }
    }

    private companion object {
        val REQUIRED_HEADINGS = listOf(
            "1. Executive summary",
            "2. Portfolio health",
            "3. Milestone risk",
            "4. Overdue ageing",
            "5. Completion trend and throughput",
            "6. Estimate versus actual recorded time",
            "7. Time allocation by project and tag",
            "8. Blockers and dependency exposure",
            "9. Data quality and caveats",
            "10. Task-detail appendix",
        )
    }
}
