package app.opentasks.digest

import app.opentasks.core.domain.CommandResult
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.domain.VaultRepository
import app.opentasks.core.model.DeviceId
import app.opentasks.core.model.HomeSnapshot
import app.opentasks.core.model.OpenTasksFixtures
import app.opentasks.core.model.Priority
import app.opentasks.core.model.Revision
import app.opentasks.core.model.SearchQuery
import app.opentasks.core.model.SearchResult
import app.opentasks.core.model.SemanticStatus
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.WorkspaceSnapshot
import app.opentasks.core.model.ZonedMoment
import app.opentasks.lock.FakeSharedPreferences
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Provider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.yield
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

    @Test
    fun sameOrEarlierEpochDaySkipsDeliveryAndOnlyReconciles() {
        val alreadyHandled = DigestHarness(lastHandledEpochDay = DELIVERY_EPOCH_DAY)
        alreadyHandled.vaults += { openTodayRepository() }

        alreadyHandled.deliver()

        assertEquals(listOf("rearm"), alreadyHandled.events)
        assertEquals(DELIVERY_EPOCH_DAY, alreadyHandled.armedSettings?.lastHandledEpochDay)
        assertTrue(alreadyHandled.posted.isEmpty())

        // A rewound device clock: "today" is behind the handled day, which is
        // still no reason to deliver a second digest for a day already done.
        val rewound = DigestHarness(lastHandledEpochDay = DELIVERY_EPOCH_DAY + 2)
        rewound.vaults += { openTodayRepository() }

        rewound.deliver()

        assertEquals(listOf("rearm"), rewound.events)
        assertTrue(rewound.posted.isEmpty())
    }

    @Test
    fun deliveryMarksAndRearmsBeforeVaultLookup() {
        val harness = DigestHarness()
        harness.vaults += { openTodayRepository() }

        harness.deliver()

        assertEquals(listOf("mark", "rearm", "vault", "post"), harness.events)
        assertEquals(DELIVERY_EPOCH_DAY, harness.coordinator.settings.value.lastHandledEpochDay)
        // The re-arm saw the marked day, so it can only have run after the mark.
        assertEquals(DELIVERY_EPOCH_DAY, harness.armedSettings?.lastHandledEpochDay)
        assertEquals(
            listOf(DailyDigestNotificationPlan(openTodayCount = 1, overdueCount = 1)),
            harness.posted,
        )
    }

    @Test
    fun missingActiveVaultLeavesTodayHandledAndTomorrowArmed() {
        val harness = DigestHarness()
        harness.vaults += { throw IllegalStateException("no active vault runtime") }

        harness.deliver()

        assertEquals(listOf("mark", "rearm", "vault"), harness.events)
        assertTrue(harness.posted.isEmpty())
        assertEquals(DELIVERY_EPOCH_DAY, harness.coordinator.settings.value.lastHandledEpochDay)
        val armed = harness.armedSettings!!
        assertEquals(
            Instant.parse("2026-08-16T07:00:00Z"),
            nextDailyDigestOccurrence(
                minuteOfDay = armed.minuteOfDay,
                now = harness.armedNow!!,
                zone = harness.armedZone!!,
                lastHandledEpochDay = armed.lastHandledEpochDay,
            ),
        )
    }

    @Test
    fun deliveryResolvesTheCurrentVaultForEachHandledDay() {
        val harness = DigestHarness()
        harness.vaults += { openTodayRepository() }
        harness.vaults += { nextDayRepository() }

        harness.deliver()
        harness.now = Instant.parse("2026-08-16T07:00:00Z")
        harness.deliver()

        assertEquals(2, harness.events.count { it == "vault" })
        assertEquals(
            listOf(
                DailyDigestNotificationPlan(openTodayCount = 1, overdueCount = 1),
                DailyDigestNotificationPlan(openTodayCount = 2, overdueCount = 0),
            ),
            harness.posted,
        )
        assertEquals(DELIVERY_EPOCH_DAY + 1, harness.coordinator.settings.value.lastHandledEpochDay)
    }

    @Test
    fun disablingSerialisesWithDeliveryAndLeavesTheAlarmCancelled() {
        val harness = DigestHarness()
        val gate = CompletableDeferred<Unit>()
        harness.vaults += { openTodayRepository(gate) }

        runBlocking {
            withTimeout(TEST_TIMEOUT_MILLIS) {
                val delivery = launch { harness.coordinator.handleDelivery() }
                while (!harness.events.contains("vault")) yield()
                val disable = launch { harness.coordinator.setEnabled(false) }
                repeat(TURNS_WITHOUT_PROGRESS) { yield() }

                assertFalse(disable.isCompleted)
                assertTrue(harness.coordinator.settings.value.enabled)

                gate.complete(Unit)
                delivery.join()
                disable.join()
            }
        }

        assertEquals(listOf("mark", "rearm", "vault", "post", "cancel"), harness.events)
        assertFalse(harness.coordinator.settings.value.enabled)
        assertEquals(DELIVERY_EPOCH_DAY, harness.coordinator.settings.value.lastHandledEpochDay)
    }

    private fun DigestHarness.deliver() {
        runBlocking { withTimeout(TEST_TIMEOUT_MILLIS) { coordinator.handleDelivery() } }
    }

    /** One open task due later today plus one overdue since yesterday. */
    private fun openTodayRepository(
        gate: CompletableDeferred<Unit>? = null,
    ): VaultRepository = SnapshotVaultRepository(
        snapshot = snapshotOf(
            listOf(
                task(id = "task-a", title = "Secret today", due = londonMoment(2026, 8, 15, 9)),
                task(id = "task-b", title = "Secret overdue", due = londonMoment(2026, 8, 14, 9)),
            ),
        ),
        gate = gate,
    )

    /** A different vault: two open tasks on the following day, none overdue. */
    private fun nextDayRepository(): VaultRepository = SnapshotVaultRepository(
        snapshot = snapshotOf(
            listOf(
                task(id = "task-c", title = "Secret tomorrow", due = londonMoment(2026, 8, 16, 9)),
                task(id = "task-d", title = "Secret later", due = londonMoment(2026, 8, 16, 18)),
            ),
        ),
    )

    private fun londonMoment(year: Int, month: Int, day: Int, hour: Int): ZonedMoment =
        ZonedMoment(
            LocalDate.of(year, month, day).atTime(hour, 0).atZone(LONDON).toInstant(),
            LONDON.id,
        )

    /**
     * Drives [DailyDigestCoordinator] through its internal fixed-clock and
     * callback constructor. The production scheduler and notifier both need a
     * real `Context`, and the Android unit-test jar stubs every framework
     * method body, so these seams are the only way a host test can observe
     * the coordinator's own sequencing -- and there is no mocking library.
     *
     * `mark` is deliberately not a seam: it is recorded from the preference
     * file itself, so the ordering assertions see the store's real write
     * rather than a stand-in for it.
     */
    private class DigestHarness(
        enabled: Boolean = true,
        minuteOfDay: Int = 8 * 60,
        lastHandledEpochDay: Long? = null,
    ) {
        val events = mutableListOf<String>()
        val posted = mutableListOf<DailyDigestNotificationPlan>()
        val vaults = mutableListOf<() -> VaultRepository>()
        var now: Instant = DELIVERY_INSTANT
        var zone: ZoneId = LONDON
        var armedSettings: DailyDigestSettings? = null
        var armedNow: Instant? = null
        var armedZone: ZoneId? = null

        private val prefs = FakeSharedPreferences()
        private var vaultLookups = 0

        val coordinator: DailyDigestCoordinator

        init {
            prefs.edit()
                .putBoolean("enabled", enabled)
                .putInt("minute_of_day", minuteOfDay)
                .apply()
            if (lastHandledEpochDay != null) {
                prefs.edit().putLong("last_handled_epoch_day", lastHandledEpochDay).apply()
            }
            prefs.registerOnSharedPreferenceChangeListener { _, key ->
                if (key == "last_handled_epoch_day") events += "mark"
            }
            coordinator = DailyDigestCoordinator(
                store = DailyDigestSettingsStore(prefs),
                repository = Provider {
                    events += "vault"
                    vaults[vaultLookups++]()
                },
                reconcileAlarm = { settings, armNow, armZone ->
                    events += "rearm"
                    armedSettings = settings
                    armedNow = armNow
                    armedZone = armZone
                },
                cancelAlarm = { events += "cancel" },
                post = { plan ->
                    events += "post"
                    posted += plan
                },
                now = { now },
                zone = { zone },
            )
        }
    }

    /**
     * Serves one fixed snapshot and nothing else: the digest only ever reads
     * the current workspace, so every other repository member would be dead
     * weight here, and failing loudly keeps it that way.
     */
    private class SnapshotVaultRepository(
        private val snapshot: WorkspaceSnapshot,
        private val gate: CompletableDeferred<Unit>? = null,
    ) : VaultRepository {
        override fun observeHome(): Flow<HomeSnapshot> = throw UnsupportedOperationException()

        override fun observeWorkspace(): StateFlow<WorkspaceSnapshot> = MutableStateFlow(snapshot)

        override fun observeTask(id: TaskId): Flow<Task?> = throw UnsupportedOperationException()

        override suspend fun currentWorkspace(): WorkspaceSnapshot {
            gate?.await()
            return snapshot
        }

        override suspend fun execute(command: DomainCommand): CommandResult =
            throw UnsupportedOperationException()

        override suspend fun search(query: SearchQuery): List<SearchResult> =
            throw UnsupportedOperationException()
    }

    private companion object {
        val LONDON: ZoneId = ZoneId.of("Europe/London")

        // 08:00 on 2026-08-15 in Europe/London, the configured digest time.
        val DELIVERY_INSTANT: Instant = Instant.parse("2026-08-15T07:00:00Z")
        val DELIVERY_EPOCH_DAY: Long = LocalDate.of(2026, 8, 15).toEpochDay()

        const val TEST_TIMEOUT_MILLIS = 5_000L
        const val TURNS_WITHOUT_PROGRESS = 10
    }
}
