package app.opentasks.digest

import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.Priority
import app.opentasks.core.model.Revision
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.WorkspaceSnapshot
import app.opentasks.core.model.ZonedMoment
import app.opentasks.lock.FakeSharedPreferences
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DailyDigestSystemTest {
    private val revision = Revision(DeviceId("digest-test-device"), 1, 0)
    private val canonicalKeys = setOf("enabled", "minute_of_day", "last_handled_epoch_day")

    private fun task(
        id: String,
        title: String,
        due: ZonedMoment? = null,
    ): Task = Task(
        id = TaskId(id),
        workspaceId = OpenTasksFixtures.workspaceId,
        projectId = OpenTasksFixtures.studioProject.id,
        statusId = OpenTasksFixtures.statusId(
            OpenTasksFixtures.studioProject.id,
            SemanticStatus.PLANNED,
        ),
        semanticStatus = SemanticStatus.PLANNED,
        title = title,
        priority = Priority.NONE,
        due = due,
        revision = revision,
    )

    private fun snapshotOf(tasks: List<Task>): WorkspaceSnapshot =
        OpenTasksFixtures.snapshot.copy(tasks = tasks)

    /**
     * Every store test must leave `prefs.all` holding only the three
     * canonical keys, with only Boolean/Int/Long values -- never a fourth
     * key and never a value of any other type.
     */
    private fun assertCanonicalVocabulary(prefs: FakeSharedPreferences) {
        assertTrue(prefs.all.keys.all { it in canonicalKeys })
        assertTrue(prefs.all.values.all { it is Boolean || it is Int || it is Long })
    }

    @Test
    fun defaultsOffAndUses0800WhenFirstEnabled() {
        val prefs = FakeSharedPreferences()
        val store = DailyDigestSettingsStore(prefs)

        assertEquals(DailyDigestSettings(), store.load())
        assertTrue(prefs.all.isEmpty())

        val enabled = store.setEnabled(true)

        assertEquals(DailyDigestSettings(enabled = true, minuteOfDay = 8 * 60), enabled)
        assertEquals(true, prefs.all["enabled"])
        assertCanonicalVocabulary(prefs)
    }

    @Test
    fun outOfRangeOrWrongTypeTimeFailsClosed() {
        val wrongTypePrefs = FakeSharedPreferences()
        wrongTypePrefs.edit()
            .putBoolean("enabled", true)
            .putString("minute_of_day", "480")
            .apply()
        assertEquals(DailyDigestSettings(), DailyDigestSettingsStore(wrongTypePrefs).load())
        assertCanonicalVocabulary(wrongTypePrefs)

        val outOfRangePrefs = FakeSharedPreferences()
        outOfRangePrefs.edit()
            .putBoolean("enabled", true)
            .putInt("minute_of_day", 1440)
            .apply()
        assertEquals(DailyDigestSettings(), DailyDigestSettingsStore(outOfRangePrefs).load())
        assertCanonicalVocabulary(outOfRangePrefs)
    }

    @Test
    fun unknownPreferenceKeyIsRemovedAndFailsClosed() {
        val prefs = FakeSharedPreferences()
        prefs.edit()
            .putBoolean("enabled", true)
            .putInt("minute_of_day", 540)
            .putBoolean("mystery_flag", true)
            .apply()

        val loaded = DailyDigestSettingsStore(prefs).load()

        assertEquals(DailyDigestSettings(), loaded)
        assertFalse(prefs.all.containsKey("mystery_flag"))
        assertCanonicalVocabulary(prefs)
    }

    @Test
    fun disablingRetainsLastHandledDay() {
        val prefs = FakeSharedPreferences()
        val store = DailyDigestSettingsStore(prefs)
        store.setEnabled(true)
        store.setMinuteOfDay(540)
        val handledDay = LocalDate.of(2026, 8, 10).toEpochDay()
        store.markHandled(handledDay)

        val disabled = store.setEnabled(false)

        assertEquals(
            DailyDigestSettings(
                enabled = false,
                minuteOfDay = 540,
                lastHandledEpochDay = handledDay,
            ),
            disabled,
        )
        assertEquals(540, prefs.all["minute_of_day"])
        assertEquals(handledDay, prefs.all["last_handled_epoch_day"])
        assertCanonicalVocabulary(prefs)
    }

    @Test
    fun nextOccurrenceBeforeConfiguredTimeUsesToday() {
        val zone = ZoneId.of("Europe/London")
        val now = Instant.parse("2026-08-15T06:00:00Z")

        val occurrence = nextDailyDigestOccurrence(minuteOfDay = 8 * 60, now = now, zone = zone)

        assertEquals(Instant.parse("2026-08-15T07:00:00Z"), occurrence)
    }

    @Test
    fun atOrAfterConfiguredTimeUsesTomorrow() {
        val zone = ZoneId.of("Europe/London")
        val exactlyAtConfiguredTime = Instant.parse("2026-08-15T07:00:00Z")
        val afterConfiguredTime = Instant.parse("2026-08-15T08:00:00Z")

        assertEquals(
            Instant.parse("2026-08-16T07:00:00Z"),
            nextDailyDigestOccurrence(
                minuteOfDay = 8 * 60,
                now = exactlyAtConfiguredTime,
                zone = zone,
            ),
        )
        assertEquals(
            Instant.parse("2026-08-16T07:00:00Z"),
            nextDailyDigestOccurrence(minuteOfDay = 8 * 60, now = afterConfiguredTime, zone = zone),
        )
    }

    @Test
    fun springGapMovesForwardByTheGap() {
        // Europe/London, 2026-03-29: clocks jump 01:00 -> 02:00, so local
        // 01:30 does not exist and must resolve forward by the one-hour gap.
        val zone = ZoneId.of("Europe/London")
        val now = Instant.parse("2026-03-29T00:00:00Z")

        val occurrence = nextDailyDigestOccurrence(minuteOfDay = 90, now = now, zone = zone)

        assertEquals(Instant.parse("2026-03-29T01:30:00Z"), occurrence)
    }

    @Test
    fun autumnOverlapUsesTheEarlierOffset() {
        // Europe/London, 2026-10-25: clocks fall back 02:00 -> 01:00, so
        // local 01:30 occurs twice and must resolve to the earlier (BST)
        // offset -- the first, not the second, occurrence.
        val zone = ZoneId.of("Europe/London")
        val now = Instant.parse("2026-10-24T23:30:00Z")

        val occurrence = nextDailyDigestOccurrence(minuteOfDay = 90, now = now, zone = zone)

        assertEquals(Instant.parse("2026-10-25T00:30:00Z"), occurrence)
    }

    @Test
    fun lastHandledDayBoundsARewoundClock() {
        val zone = ZoneId.of("Europe/London")
        val now = Instant.parse("2026-08-10T06:00:00Z")
        val lastHandledEpochDay = LocalDate.of(2026, 8, 12).toEpochDay()

        val occurrence = nextDailyDigestOccurrence(
            minuteOfDay = 8 * 60,
            now = now,
            zone = zone,
            lastHandledEpochDay = lastHandledEpochDay,
        )

        assertEquals(Instant.parse("2026-08-13T07:00:00Z"), occurrence)
    }

    @Test
    fun zeroCountsProduceNoNotificationPlan() {
        val zone = ZoneId.of("Europe/London")
        val now = Instant.parse("2026-08-15T09:00:00Z")

        val plan = dailyDigestNotificationPlan(snapshotOf(emptyList()), now, zone)

        assertNull(plan)
    }

    @Test
    fun notificationPlanContainsCountsOnlyAndSuppressesTitles() {
        val zone = ZoneId.of("Europe/London")
        val today = LocalDate.of(2026, 8, 15)
        val now = today.atTime(12, 0).atZone(zone).toInstant()

        fun momentAt(hour: Int, date: LocalDate = today): ZonedMoment =
            ZonedMoment(date.atTime(hour, 0).atZone(zone).toInstant(), zone.id)

        val dueEarlierToday = task(id = "task-a", title = "Secret earlier", due = momentAt(9))
        val dueLaterToday = task(id = "task-b", title = "Secret later", due = momentAt(18))
        val overdueYesterday = task(
            id = "task-c",
            title = "Secret yesterday",
            due = momentAt(9, today.minusDays(1)),
        )
        val snapshot = snapshotOf(listOf(dueEarlierToday, dueLaterToday, overdueYesterday))

        val plan = dailyDigestNotificationPlan(snapshot, now, zone)

        assertEquals(DailyDigestNotificationPlan(openTodayCount = 2, overdueCount = 2), plan)
        assertFalse(plan.toString().contains("Secret"))
    }
}
