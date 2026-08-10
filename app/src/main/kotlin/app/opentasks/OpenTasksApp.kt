package app.opentasks

import android.Manifest
import android.app.Activity
import android.app.AlarmManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.biometrics.BiometricManager
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.result.IntentSenderRequest
import androidx.activity.result.PickVisualMediaRequest
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.focusable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationRail
import androidx.compose.material3.NavigationRailItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusEvent
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalAccessibilityManager
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation3.runtime.NavKey
import androidx.navigation3.runtime.NavBackStack
import androidx.navigation3.runtime.entryProvider
import androidx.navigation3.runtime.rememberNavBackStack
import androidx.navigation3.ui.NavDisplay
import androidx.window.layout.FoldingFeature
import androidx.window.layout.WindowInfoTracker
import app.opentasks.backup.BackupViewModel
import app.opentasks.backup.EncryptedBackupViewModel
import app.opentasks.calendar.CalendarEventDraft
import app.opentasks.calendar.calendarEventDraft
import app.opentasks.calendar.calendarInsertIntent
import app.opentasks.core.data.export.CsvTable
import app.opentasks.core.designsystem.OpenTasksTheme
import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.domain.RecoveryPassphrasePolicy
import app.opentasks.core.domain.WorkflowMoveDirection
import app.opentasks.core.domain.arrangeTasks
import app.opentasks.core.domain.buildReviewQueue
import app.opentasks.core.domain.classifyDueBucket
import app.opentasks.core.model.Attachment
import app.opentasks.core.model.AttachmentId
import app.opentasks.core.model.MilestoneId
import app.opentasks.core.model.ProjectId
import app.opentasks.core.model.SavedViewId
import app.opentasks.core.model.SearchResult
import app.opentasks.core.model.Task
import app.opentasks.core.model.TaskArrangement
import app.opentasks.core.model.TaskId
import app.opentasks.core.model.TemplateId
import app.opentasks.core.model.TimeEntryId
import app.opentasks.core.model.WorkflowStatusId
import app.opentasks.core.model.WorkspaceSnapshot
import app.opentasks.core.model.ZonedMoment
import app.opentasks.feature.home.HomeScreen
import app.opentasks.feature.more.LockDelayOption
import app.opentasks.feature.more.MoreScreen
import app.opentasks.feature.more.ReviewScreen
import app.opentasks.feature.projects.NewProjectSheet
import app.opentasks.feature.projects.ProjectEdit
import app.opentasks.feature.projects.ProjectsScreen
import app.opentasks.feature.projects.WorkflowMove
import app.opentasks.feature.schedule.ScheduleScreen
import app.opentasks.feature.tasks.FocusPresetOption
import app.opentasks.feature.tasks.TaskEdit
import app.opentasks.feature.tasks.TasksScreen
import androidx.core.content.ContextCompat
import androidx.core.net.toUri
import app.opentasks.input.ShortcutAction
import app.opentasks.input.ShortcutHelpDialog
import app.opentasks.input.shortcutActionFor
import app.opentasks.focus.FocusNotifications
import app.opentasks.focus.FocusPhaseKind
import app.opentasks.focus.FocusPreset
import app.opentasks.focus.FocusSession
import app.opentasks.lock.AppLockSettings
import app.opentasks.lock.LockDelay
import app.opentasks.reminders.ReminderNotifications
import java.time.Duration
import java.time.Clock
import java.time.Instant
import java.time.LocalTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.serialization.Serializable

@Serializable
sealed interface WorkspaceRoute : NavKey

@Serializable
data object HomeRoute : WorkspaceRoute

@Serializable
data object TasksRoute : WorkspaceRoute

@Serializable
data object ProjectsRoute : WorkspaceRoute

@Serializable
data object ScheduleRoute : WorkspaceRoute

@Serializable
data object MoreRoute : WorkspaceRoute

@Serializable
data object ReviewRoute : WorkspaceRoute

private data class NavigationDestination(
    val route: WorkspaceRoute,
    val label: String,
    val icon: ImageVector,
)

private val destinations = listOf(
    NavigationDestination(HomeRoute, "Home", Icons.Rounded.Home),
    NavigationDestination(TasksRoute, "Tasks", Icons.Rounded.CheckCircle),
    NavigationDestination(ProjectsRoute, "Projects", Icons.Rounded.FolderOpen),
    NavigationDestination(ScheduleRoute, "Schedule", Icons.Rounded.CalendarMonth),
    NavigationDestination(MoreRoute, "More", Icons.Rounded.MoreHoriz),
)

internal data class SnackbarPresentation(
    val duration: SnackbarDuration,
    val withDismissAction: Boolean,
    val timeoutMillis: Long?,
)

/**
 * [CalendarEventDraft] itself carries plain epoch millis with no zone --
 * a provider event has none of its own -- so the preview dialog keeps the
 * originating moment's zone here purely for display formatting.
 */
private data class CalendarPreviewState(
    val draft: CalendarEventDraft,
    val beginZoneId: String,
    val endZoneId: String?,
)

internal const val UNDO_SNACKBAR_TIMEOUT_MILLIS = 8_000L

/**
 * Task 2's date-only convention: a bare date resolves to 17:00 local time.
 * Shared by every `:app` mapping that turns a picked date into a due moment.
 */
internal val DATE_ONLY_DUE_TIME: LocalTime = LocalTime.of(17, 0)

internal fun shouldShowNavigationLabels(fontScale: Float): Boolean = fontScale < 1.5f

internal fun snackbarPresentation(hasUndo: Boolean): SnackbarPresentation =
    SnackbarPresentation(
        duration = if (hasUndo) SnackbarDuration.Indefinite else SnackbarDuration.Short,
        withDismissAction = false,
        timeoutMillis = UNDO_SNACKBAR_TIMEOUT_MILLIS.takeIf { hasUndo },
    )

@Composable
internal fun rememberWorkspaceBackStack(): NavBackStack<NavKey> =
    rememberNavBackStack(HomeRoute)

@Composable
fun OpenTasksApp(
    activity: Activity,
    appLockSettings: AppLockSettings,
    quickAddSignal: Int,
    quickAddPrefillText: String? = null,
    onQuickAddConsumed: () -> Unit = {},
    openTaskSignal: Int = 0,
    openTaskId: String? = null,
    onOpenRecovery: () -> Unit = {},
    viewModel: WorkspaceViewModel = viewModel(),
    backupViewModel: BackupViewModel = viewModel(),
    encryptedBackupViewModel: EncryptedBackupViewModel = viewModel(),
    attachmentViewModel: AttachmentIntakeViewModel = viewModel(),
    vaultTransferViewModel: VaultTransferViewModel = viewModel(),
    clock: Clock = Clock.systemDefaultZone(),
) {
    OpenTasksTheme {
        val snapshot by viewModel.snapshot.collectAsStateWithLifecycle()
        val backupPresentation by backupViewModel.presentation.collectAsStateWithLifecycle()
        val encryptedBackupPresentation by
            encryptedBackupViewModel.presentation.collectAsStateWithLifecycle()
        val backupAuthorizationLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.StartIntentSenderForResult(),
        ) { result ->
            result.data?.takeIf { result.resultCode == Activity.RESULT_OK }
                ?.let(encryptedBackupViewModel::acceptResolution)
        }
        val insightsSummary by viewModel.insightsSummary.collectAsStateWithLifecycle()
        val insightsUiState by viewModel.insightsUiState.collectAsStateWithLifecycle()
        val selectedTaskValue by viewModel.selectedTaskId.collectAsStateWithLifecycle()
        val selectedProjectValue by viewModel.selectedProjectId.collectAsStateWithLifecycle()
        val pendingBlocked by viewModel.pendingBlockedCompletion.collectAsStateWithLifecycle()
        val bulkSelection by viewModel.bulkSelection.collectAsStateWithLifecycle()
        val reviewedTaskIds by viewModel.reviewedTaskIds.collectAsStateWithLifecycle()
        val reviewedProjectIds by viewModel.reviewedProjectIds.collectAsStateWithLifecycle()
        val boardModeProjectIds by viewModel.boardModeProjectIds.collectAsStateWithLifecycle()
        val viewArrangement by viewModel.viewArrangement.collectAsStateWithLifecycle()
        val reviewActionPending by viewModel.reviewActionPending.collectAsStateWithLifecycle()
        val pendingBlockedBulk by
            viewModel.pendingBlockedBulkCompletion.collectAsStateWithLifecycle()
        val dependencyFeedback by viewModel.dependencyFeedback.collectAsStateWithLifecycle()
        val searchResults by viewModel.searchResults.collectAsStateWithLifecycle()
        val focusSession by viewModel.focusSession.collectAsStateWithLifecycle()
        val attachmentStates by attachmentViewModel.rowStates.collectAsStateWithLifecycle()
        val attachmentSetupRequired by
            attachmentViewModel.setupRequired.collectAsStateWithLifecycle()
        val attachmentCacheUsage by
            attachmentViewModel.cacheUsageBytes.collectAsStateWithLifecycle()
        val vaultExportInProgress by
            vaultTransferViewModel.exportInProgress.collectAsStateWithLifecycle()
        val vaultExportOutcome by
            vaultTransferViewModel.exportOutcome.collectAsStateWithLifecycle()
        val vaultExportDocumentLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("application/octet-stream"),
        ) { uri ->
            vaultTransferViewModel.onExportDocumentSelected(uri)
        }
        val vaultImportInProgress by
            vaultTransferViewModel.importInProgress.collectAsStateWithLifecycle()
        val vaultImportOutcome by
            vaultTransferViewModel.importOutcome.collectAsStateWithLifecycle()
        val vaultImportDocumentLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            vaultTransferViewModel.onImportDocumentSelected(uri)
        }
        val csvExportInProgress by
            vaultTransferViewModel.csvExportInProgress.collectAsStateWithLifecycle()
        val csvExportOutcome by
            vaultTransferViewModel.csvExportOutcome.collectAsStateWithLifecycle()
        val csvExportDocumentLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("text/csv"),
        ) { uri ->
            vaultTransferViewModel.onCsvExportDocumentSelected(uri)
        }
        val csvImportInProgress by
            vaultTransferViewModel.csvImportInProgress.collectAsStateWithLifecycle()
        val csvImportOutcome by
            vaultTransferViewModel.csvImportOutcome.collectAsStateWithLifecycle()
        val csvImportDocumentLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
        ) { uri ->
            vaultTransferViewModel.onCsvDocumentSelected(uri)
        }
        val markdownExportInProgress by
            vaultTransferViewModel.markdownExportInProgress.collectAsStateWithLifecycle()
        val markdownExportOutcome by
            vaultTransferViewModel.markdownExportOutcome.collectAsStateWithLifecycle()
        val markdownExportDocumentLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.CreateDocument("text/markdown"),
        ) { uri ->
            vaultTransferViewModel.onMarkdownDocumentSelected(uri)
        }
        val snackbarHostState = remember { SnackbarHostState() }
        val coroutineScope = rememberCoroutineScope()
        val accessibilityManager = LocalAccessibilityManager.current
        val showNavigationLabels =
            shouldShowNavigationLabels(LocalDensity.current.fontScale)
        var showQuickAdd by rememberSaveable { mutableStateOf(false) }
        // Captured from `quickAddPrefillText` inside `LaunchedEffect
        // (quickAddSignal)`, before that effect consumes it -- see the
        // `key(quickAddSignal)` block below for why the sheet's seed reads
        // both this and `quickAddPrefillText` itself.
        var quickAddSheetTitle by rememberSaveable { mutableStateOf("") }
        var showNewProject by rememberSaveable { mutableStateOf(false) }
        var showShortcutHelp by rememberSaveable { mutableStateOf(false) }
        // Not `rememberSaveable`: a `CalendarEventDraft` isn't a bundle-able
        // type and this preview is transient -- losing it to process death
        // simply closes the dialog, same as `calendarPreview` never having
        // opened.
        var calendarPreview by remember { mutableStateOf<CalendarPreviewState?>(null) }
        // Aggregated from the root: true while any descendant focus target is
        // active, not only genuinely editable ones. That is deliberately
        // conservative -- see `shortcutActionFor`'s doc comment -- so the `/`
        // and `?` shortcuts never race a focused text field's own typing.
        var editableFocused by remember { mutableStateOf(false) }

        LaunchedEffect(encryptedBackupViewModel) {
            for (pendingIntent in encryptedBackupViewModel.resolutionEffects) {
                backupAuthorizationLauncher.launch(
                    IntentSenderRequest.Builder(pendingIntent.intentSender).build(),
                )
            }
        }
        LaunchedEffect(encryptedBackupViewModel, onOpenRecovery) {
            for (ignored in encryptedBackupViewModel.recoveryEffects) onOpenRecovery()
        }
        LaunchedEffect(vaultTransferViewModel) {
            for (ignored in vaultTransferViewModel.createDocumentRequests) {
                vaultExportDocumentLauncher.launch("open_tasks_vault.otvault")
            }
        }
        LaunchedEffect(vaultTransferViewModel, vaultImportDocumentLauncher) {
            for (ignored in vaultTransferViewModel.openDocumentRequests) {
                // `.otvault` has no registered MIME type, so the picker offers
                // the generic byte-stream filter every archive is served as.
                vaultImportDocumentLauncher.launch(arrayOf("application/octet-stream"))
            }
        }
        LaunchedEffect(vaultTransferViewModel, csvExportDocumentLauncher) {
            for (table in vaultTransferViewModel.csvCreateDocumentRequests) {
                csvExportDocumentLauncher.launch(csvExportFileName(table))
            }
        }
        LaunchedEffect(vaultTransferViewModel, csvImportDocumentLauncher) {
            for (ignored in vaultTransferViewModel.csvOpenDocumentRequests) {
                csvImportDocumentLauncher.launch(
                    arrayOf("text/csv", "text/comma-separated-values", "text/plain"),
                )
            }
        }
        LaunchedEffect(vaultTransferViewModel, viewModel) {
            for (rows in vaultTransferViewModel.csvImportCommitRequests) {
                viewModel.execute(
                    DomainCommand.ImportTasks(rows),
                    vaultTransferViewModel::onCsvImportCommandResult,
                )
            }
        }
        LaunchedEffect(vaultTransferViewModel, markdownExportDocumentLauncher) {
            for (fileName in vaultTransferViewModel.markdownCreateDocumentRequests) {
                markdownExportDocumentLauncher.launch(fileName)
            }
        }
        var showSearch by rememberSaveable { mutableStateOf(false) }
        var openInsightsOnMore by rememberSaveable { mutableStateOf(false) }
        var openBackupOnMore by rememberSaveable { mutableStateOf(false) }
        // The task the picker was opened for; a process death between the
        // launch and the result simply drops the intake rather than attaching
        // to whatever is selected when the app comes back.
        var attachmentTaskValue by rememberSaveable { mutableStateOf<String?>(null) }
        val acceptPickedAttachment: (Uri?) -> Unit = { uri ->
            attachmentTaskValue?.let(::TaskId)?.let { taskId ->
                attachmentViewModel.addFromUri(taskId, uri)
            }
            attachmentTaskValue = null
        }
        val photoPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.PickVisualMedia(),
            acceptPickedAttachment,
        )
        val documentPicker = rememberLauncherForActivityResult(
            ActivityResultContracts.OpenDocument(),
            acceptPickedAttachment,
        )
        var rawFolds by remember { mutableStateOf(emptyList<RawFold>()) }
        var permissionStateVersion by remember { mutableIntStateOf(0) }
        var notificationPermissionRequested by rememberSaveable { mutableStateOf(false) }
        val lifecycleOwner = LocalLifecycleOwner.current
        val notificationPermissionLauncher = rememberLauncherForActivityResult(
            ActivityResultContracts.RequestPermission(),
        ) {
            notificationPermissionRequested = true
            permissionStateVersion++
        }
        val notificationsPermissionGranted = remember(permissionStateVersion) {
            ContextCompat.checkSelfPermission(
                activity,
                Manifest.permission.POST_NOTIFICATIONS,
            ) == PackageManager.PERMISSION_GRANTED
        }
        val notificationsEnabled = remember(permissionStateVersion) {
            ReminderNotifications.areEnabled(activity)
        }
        val preciseRemindersAvailable = remember(permissionStateVersion) {
            activity.getSystemService(AlarmManager::class.java).canScheduleExactAlarms()
        }
        // Its own channel, so a person can silence focus boundaries without
        // silencing reminders. A refusal here only costs the alert: the cycle
        // itself keeps running and the banner offers the existing
        // enable-notifications route rather than a second permission flow.
        val focusAlertsEnabled = remember(permissionStateVersion) {
            FocusNotifications.areEnabled(activity)
        }
        // Re-read on the same trigger as the permission checks above: this
        // most commonly changes when a person leaves for system settings to
        // set a screen lock and returns (ON_RESUME bumps
        // `permissionStateVersion`), the same path that already refreshes
        // notification/alarm availability.
        val lockAvailable = remember(permissionStateVersion) {
            val allowedAuthenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
                BiometricManager.Authenticators.BIOMETRIC_WEAK or
                BiometricManager.Authenticators.DEVICE_CREDENTIAL
            activity.getSystemService(BiometricManager::class.java)
                ?.canAuthenticate(allowedAuthenticators) == BiometricManager.BIOMETRIC_SUCCESS
        }

        // Mirrors `appLockSettings`, the app process's own SharedPreferences
        // wrapper, so the More screen's Privacy & lock section stays plain
        // data in / lambdas out, like every other feature composable.
        var lockEnabled by remember { mutableStateOf(appLockSettings.lockEnabled) }
        var lockDelay by remember { mutableStateOf(appLockSettings.lockDelay) }
        var titlePrivacy by remember { mutableStateOf(appLockSettings.titlePrivacy) }
        var screenshotBlocking by remember { mutableStateOf(appLockSettings.screenshotBlocking) }
        LaunchedEffect(appLockSettings) {
            appLockSettings.observe().collect {
                lockEnabled = appLockSettings.lockEnabled
                lockDelay = appLockSettings.lockDelay
                titlePrivacy = appLockSettings.titlePrivacy
                screenshotBlocking = appLockSettings.screenshotBlocking
            }
        }

        val selectedTaskId = selectedTaskValue?.let(::TaskId)
        val selectedProjectId = selectedProjectValue?.let(::ProjectId)
        val projectNames = snapshot.projects.associate { it.id to it.name }
        val selectedProject = snapshot.projects.firstOrNull { it.id == selectedProjectId }
        val tasksArrangement = viewArrangement.tasks
        val (dueBucketsByTaskId, taskGroups) = remember(
            snapshot.tasks,
            projectNames,
            tasksArrangement,
            clock,
        ) {
            val projectionClock = Clock.fixed(clock.instant(), clock.zone)
            snapshot.tasks.associate { task ->
                task.id to classifyDueBucket(task.due, projectionClock)
            } to arrangeTasks(
                tasks = snapshot.tasks,
                arrangement = tasksArrangement,
                projectNames = projectNames,
                clock = projectionClock,
            )
        }
        val workbenchArrangement = selectedProject?.let {
            viewArrangement.workbenchFor(it.id)
        } ?: TaskArrangement()
        val workbenchTaskGroups = remember(
            snapshot.tasks,
            selectedProject,
            projectNames,
            workbenchArrangement,
            clock,
        ) {
            selectedProject?.let { project ->
                val projectionClock = Clock.fixed(clock.instant(), clock.zone)
                arrangeTasks(
                    tasks = snapshot.tasks.filter {
                        it.projectId == project.id && it.deletedAt == null
                    },
                    arrangement = workbenchArrangement,
                    projectNames = projectNames,
                    clock = projectionClock,
                )
            }.orEmpty()
        }
        val selectedTask = selectedTaskId?.let { id -> snapshot.tasks.firstOrNull { it.id == id } }
        // Inbox tasks pass `null`, not "Inbox": `calendarEventDraft`'s empty-description
        // case is keyed on a null project name, and `projectNames` has no Inbox entry.
        fun calendarPreviewFor(task: Task): CalendarPreviewState? {
            val projectName = task.projectId?.let(projectNames::get)
            val draft = calendarEventDraft(task, projectName) ?: return null
            val beginZoneId = (task.start ?: task.due)?.zoneId ?: return null
            val endZoneId = task.due?.zoneId.takeIf { draft.endEpochMillis != null }
            return CalendarPreviewState(draft, beginZoneId, endZoneId)
        }
        val calendarEligibleTaskIds = snapshot.tasks
            .filter { task -> calendarPreviewFor(task) != null }
            .mapTo(hashSetOf(), Task::id)
        val backStack = rememberWorkspaceBackStack()
        val currentRoute = backStack.lastOrNull() ?: HomeRoute

        fun navigate(route: WorkspaceRoute) {
            if (backStack.lastOrNull() == route) return
            if (route != MoreRoute) {
                openInsightsOnMore = false
                openBackupOnMore = false
            }
            backStack.clear()
            backStack.add(route)
            if (route != TasksRoute) viewModel.closeTask()
            if (route != ProjectsRoute) viewModel.closeProject()
        }

        fun navigateFromPrimaryNavigation(route: WorkspaceRoute) {
            if (route == MoreRoute) {
                openInsightsOnMore = false
                openBackupOnMore = false
            }
            navigate(route)
        }

        /**
         * `Esc` target for [ShortcutAction.DISMISS_TOP]: closes the topmost
         * shortcut-reachable surface -- the help dialog, then either sheet,
         * then expanded search -- and falls through to nothing when none of
         * those are open. Never calls `activity.finish()`.
         */
        fun dismissTopShortcutSurface() {
            when {
                showShortcutHelp -> showShortcutHelp = false
                showQuickAdd -> showQuickAdd = false
                showNewProject -> showNewProject = false
                showSearch -> {
                    showSearch = false
                    viewModel.clearSearch()
                }
            }
        }

        /**
         * Attachments never fork a second backup setup: the one route is the
         * existing Backup & recovery screen.
         */
        fun openBackupRecovery() {
            openInsightsOnMore = false
            openBackupOnMore = true
            navigate(MoreRoute)
        }

        fun pickAttachment(taskId: TaskId, fromPhotos: Boolean) {
            if (attachmentSetupRequired) {
                openBackupRecovery()
                return
            }
            attachmentTaskValue = taskId.value
            if (fromPhotos) {
                photoPicker.launch(
                    PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo),
                )
            } else {
                documentPicker.launch(arrayOf("*/*"))
            }
        }

        fun openNotificationSettings() {
            activity.startActivity(
                Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS)
                    .putExtra(Settings.EXTRA_APP_PACKAGE, activity.packageName),
            )
        }

        fun openSystemSettings() {
            activity.startActivity(Intent(Settings.ACTION_SETTINGS))
        }

        fun enableNotifications() {
            when {
                notificationsPermissionGranted -> openNotificationSettings()
                !notificationPermissionRequested -> {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                activity.shouldShowRequestPermissionRationale(
                    Manifest.permission.POST_NOTIFICATIONS,
                ) -> {
                    notificationPermissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
                }
                else -> openNotificationSettings()
            }
        }

        fun enablePreciseReminders() {
            activity.startActivity(
                Intent(
                    Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM,
                    "package:${activity.packageName}".toUri(),
                ),
            )
        }

        DisposableEffect(lifecycleOwner) {
            val observer = LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        permissionStateVersion++
                        viewModel.setInsightsForegrounded(true)
                        // A boundary the system delivered while this process
                        // was gone, or an alarm a force-stop cancelled, is
                        // settled here -- through the same ownership decision
                        // the boundary receiver uses.
                        viewModel.reconcileFocus()
                    }
                    Lifecycle.Event.ON_PAUSE -> viewModel.setInsightsForegrounded(false)
                    else -> Unit
                }
            }
            lifecycleOwner.lifecycle.addObserver(observer)
            viewModel.setInsightsForegrounded(
                lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED),
            )
            onDispose {
                lifecycleOwner.lifecycle.removeObserver(observer)
                viewModel.setInsightsForegrounded(false)
            }
        }

        LaunchedEffect(activity) {
            WindowInfoTracker.getOrCreate(activity)
                .windowLayoutInfo(activity)
                .collect { layout ->
                    rawFolds = layout.displayFeatures
                        .filterIsInstance<FoldingFeature>()
                        .map { feature ->
                            RawFold(
                                leftPx = feature.bounds.left,
                                topPx = feature.bounds.top,
                                widthPx = feature.bounds.width(),
                                heightPx = feature.bounds.height(),
                                isSeparating = feature.isSeparating,
                            )
                        }
                }
        }

        LaunchedEffect(quickAddSignal) {
            if (quickAddSignal > 0) {
                // Captured ahead of `onQuickAddConsumed()` below: the sheet
                // opening for the first time under this signal value (a
                // share arriving while Quick Add is closed) mounts on the
                // *next* composition, by which point MainActivity's copy is
                // already cleared -- `quickAddSheetTitle` is what that mount
                // reads. See the `key(quickAddSignal)` block for the other
                // mount path, where `quickAddPrefillText` itself is still
                // populated.
                quickAddSheetTitle = quickAddPrefillText.orEmpty()
                showQuickAdd = true
                onQuickAddConsumed()
            }
        }

        LaunchedEffect(openTaskSignal) {
            val taskId = openTaskId?.let(::TaskId)
            if (openTaskSignal > 0 && taskId != null) {
                viewModel.selectTask(taskId)
                navigate(TasksRoute)
            }
        }

        LaunchedEffect(attachmentViewModel, activity) {
            for (intent in attachmentViewModel.deliveries) {
                try {
                    activity.startActivity(intent)
                } catch (_: ActivityNotFoundException) {
                    snackbarHostState.showSnackbar(
                        activity.getString(R.string.attachment_no_viewer),
                    )
                }
            }
        }

        LaunchedEffect(attachmentViewModel, activity) {
            for (message in attachmentViewModel.messages) {
                snackbarHostState.showSnackbar(activity.getString(message))
            }
        }

        LaunchedEffect(viewModel, accessibilityManager) {
            viewModel.events.collect { event ->
                when (event) {
                    is WorkspaceEvent.Message -> {
                        val presentation = snackbarPresentation(hasUndo = event.undo != null)
                        val showMessage = suspend {
                            snackbarHostState.showSnackbar(
                                message = event.text,
                                actionLabel = event.undo?.let { "Undo" },
                                withDismissAction = presentation.withDismissAction,
                                duration = presentation.duration,
                            )
                        }
                        val timeoutMillis = presentation.timeoutMillis?.let { baseTimeout ->
                            accessibilityManager?.calculateRecommendedTimeoutMillis(
                                originalTimeoutMillis = baseTimeout,
                                containsIcons = false,
                                containsText = true,
                                containsControls = event.undo != null,
                            ) ?: baseTimeout
                        }
                        val result =
                            if (timeoutMillis == null || timeoutMillis == Long.MAX_VALUE) {
                                showMessage()
                            } else {
                                withTimeoutOrNull(timeoutMillis) { showMessage() }
                            }
                        if (
                            result == androidx.compose.material3.SnackbarResult.ActionPerformed &&
                            event.undo != null
                        ) {
                            viewModel.execute(event.undo)
                        }
                    }
                }
            }
        }

        BoxWithConstraints(
            modifier = Modifier
                .fillMaxSize()
                .onFocusEvent { focusState -> editableFocused = focusState.hasFocus }
                // `Ctrl` combinations run in the preview (top-down) pass, ahead
                // of any focused text field, so they always fire. Gated on
                // `isCtrlPressed` so a bare `/` -- which resolves to the same
                // `OPEN_SEARCH` action as `Ctrl+K` -- is never handled here;
                // it is left for the bubbling handler below.
                .onPreviewKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || !event.isCtrlPressed) {
                        return@onPreviewKeyEvent false
                    }
                    when (
                        shortcutActionFor(
                            key = event.key,
                            isCtrlPressed = true,
                            isShiftPressed = event.isShiftPressed,
                            inProjectsRoute = currentRoute == ProjectsRoute,
                            editableFocused = editableFocused,
                        )
                    ) {
                        ShortcutAction.OPEN_SEARCH -> showSearch = true
                        ShortcutAction.QUICK_ADD -> showQuickAdd = true
                        ShortcutAction.NEW_PROJECT -> showNewProject = true
                        else -> return@onPreviewKeyEvent false
                    }
                    true
                }
                // Single-key shortcuts (`/`, `?`, `Esc`) run in the bubbling
                // (bottom-up) pass, so a focused text field claims ordinary
                // typing before the root ever sees it. Gated on
                // `!isCtrlPressed` so this never re-handles a `Ctrl` combo --
                // those are fully claimed by the preview handler above.
                .onKeyEvent { event ->
                    if (event.type != KeyEventType.KeyDown || event.isCtrlPressed) {
                        return@onKeyEvent false
                    }
                    when (
                        shortcutActionFor(
                            key = event.key,
                            isCtrlPressed = false,
                            isShiftPressed = event.isShiftPressed,
                            inProjectsRoute = currentRoute == ProjectsRoute,
                            editableFocused = editableFocused,
                        )
                    ) {
                        ShortcutAction.OPEN_SEARCH -> showSearch = true
                        ShortcutAction.SHOW_HELP -> showShortcutHelp = true
                        ShortcutAction.DISMISS_TOP -> dismissTopShortcutSurface()
                        else -> return@onKeyEvent false
                    }
                    true
                }
                .focusable(),
        ) {
            val density = LocalDensity.current.density
            val layout = WorkspaceLayoutPolicy.calculate(
                WindowPostureMapper.map(
                    widthDp = maxWidth.value.toInt().coerceAtLeast(1),
                    heightDp = maxHeight.value.toInt().coerceAtLeast(1),
                    density = density,
                    folds = rawFolds,
                ),
            )
            val compact = layout.windowClass == WorkspaceWindowClass.COMPACT
            val showDetailPane = layout.showDetailPane
            val boardColumnWidth = WorkspaceLayoutPolicy.boardColumnWidthDp(layout).dp

            Scaffold(
                modifier = Modifier.fillMaxSize(),
                containerColor = MaterialTheme.colorScheme.background,
                contentWindowInsets = WindowInsets.safeDrawing,
                snackbarHost = { SnackbarHost(snackbarHostState) },
                bottomBar = {
                    Column {
                        focusSession?.let { session ->
                            FocusBanner(
                                session = session,
                                notificationsEnabled = focusAlertsEnabled,
                                hasNavigationBarBelow = compact,
                                onEnableNotifications = ::enableNotifications,
                                onStop = viewModel::stopFocus,
                            )
                        }
                        if (compact) {
                            NavigationBar {
                                destinations.forEach { destination ->
                                    NavigationBarItem(
                                        selected = currentRoute == destination.route,
                                        onClick = {
                                            navigateFromPrimaryNavigation(destination.route)
                                        },
                                        icon = {
                                            Icon(
                                                destination.icon,
                                                contentDescription = destination.label
                                                    .takeUnless { showNavigationLabels },
                                            )
                                        },
                                        label = if (showNavigationLabels) {
                                            { Text(destination.label) }
                                        } else {
                                            null
                                        },
                                    )
                                }
                            }
                        }
                    }
                },
                floatingActionButton = {
                    if (selectedTaskId == null && selectedProjectId == null) {
                        val addsProject = currentRoute == ProjectsRoute
                        val actionLabel = if (addsProject) "New project" else "Quick add"
                        val actionContentDescription =
                            if (addsProject) "Create a new project" else "Quick add task"
                        val onAction = {
                            if (addsProject) {
                                showNewProject = true
                            } else {
                                showQuickAdd = true
                            }
                        }
                        if (layout.useExtendedQuickAdd) {
                            ExtendedFloatingActionButton(
                                onClick = onAction,
                                icon = { Icon(Icons.Rounded.Add, contentDescription = null) },
                                text = { Text(actionLabel) },
                            )
                        } else {
                            FloatingActionButton(onClick = onAction) {
                                Icon(
                                    Icons.Rounded.Add,
                                    contentDescription = actionContentDescription,
                                )
                            }
                        }
                    }
                },
            ) { contentPadding ->
                val layoutDirection = LocalLayoutDirection.current
                val contentStartDp =
                    (if (layout.showNavigationRail) 80 else 0) +
                        contentPadding
                            .calculateStartPadding(layoutDirection)
                            .value
                            .toInt()
                val contentEndDp = contentPadding
                    .calculateEndPadding(layoutDirection)
                    .value
                    .toInt()
                val listPaneFraction = layout.paneSplit?.let { split ->
                    WorkspaceLayoutPolicy.contentListFraction(
                        split = split,
                        windowWidthDp = maxWidth.value.toInt(),
                        contentStartDp = contentStartDp,
                        contentEndDp = contentEndDp,
                    )
                } ?: 0.42f
                Row(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(contentPadding)
                        .consumeWindowInsets(contentPadding),
                ) {
                    if (layout.showNavigationRail) {
                        NavigationRail {
                            destinations.forEach { destination ->
                                NavigationRailItem(
                                    selected = currentRoute == destination.route,
                                    onClick = {
                                        navigateFromPrimaryNavigation(destination.route)
                                    },
                                    icon = {
                                        Icon(
                                            destination.icon,
                                            contentDescription = destination.label
                                                .takeUnless { showNavigationLabels },
                                        )
                                    },
                                    label = if (showNavigationLabels) {
                                        { Text(destination.label) }
                                    } else {
                                        null
                                    },
                                )
                            }
                        }
                    }

                    NavDisplay(
                        backStack = backStack,
                        modifier = Modifier.fillMaxSize(),
                        onBack = {
                            when {
                                selectedTaskId != null -> viewModel.closeTask()
                                selectedProjectId != null -> viewModel.closeProject()
                            }
                        },
                        entryProvider = entryProvider {
                            entry<HomeRoute> {
                                HomeScreen(
                                    snapshot = snapshot.home,
                                    projectNames = projectNames,
                                    onOpenSearch = { showSearch = true },
                                    onPlanToday = { navigate(TasksRoute) },
                                    onOpenTask = { taskId ->
                                        viewModel.selectTask(taskId)
                                        navigate(TasksRoute)
                                    },
                                    onCompleteTask = viewModel::completeTask,
                                    onOpenProject = { projectId ->
                                        viewModel.selectProject(projectId)
                                        navigate(ProjectsRoute)
                                    },
                                    insightsSummary = insightsSummary,
                                    onOpenInsights = {
                                        openInsightsOnMore = true
                                        navigate(MoreRoute)
                                    },
                                    onToggleTimer = viewModel::stopActiveTimer,
                                )
                            }
                            entry<TasksRoute> {
                                TasksScreen(
                                    tasks = snapshot.tasks,
                                    dueBucketsByTaskId = dueBucketsByTaskId,
                                    taskGroups = taskGroups,
                                    taskSort = tasksArrangement.sort,
                                    taskGroupBy = tasksArrangement.groupBy,
                                    onTaskSortChange = { sort ->
                                        viewModel.setTasksArrangement(
                                            viewModel.viewArrangement.value.tasks.copy(sort = sort),
                                        )
                                    },
                                    onTaskGroupChange = { groupBy ->
                                        viewModel.setTasksArrangement(
                                            viewModel.viewArrangement.value.tasks.copy(groupBy = groupBy),
                                        )
                                    },
                                    reminders = snapshot.reminders,
                                    projectNames = projectNames,
                                    activeProjectIds = snapshot.projects
                                        .filter { it.archivedAt == null }
                                        .mapTo(hashSetOf()) { it.id },
                                    workflowStatuses = snapshot.workflowStatuses,
                                    tags = snapshot.tags,
                                    milestones = snapshot.milestones,
                                    selectedTaskId = selectedTaskId,
                                    showDetailPane = showDetailPane,
                                    listPaneFraction = listPaneFraction,
                                    hingeExclusionBandDp = layout.hingeExclusionBandDp,
                                    onSelectTask = viewModel::selectTask,
                                    onCloseDetail = viewModel::closeTask,
                                    onCompleteTask = viewModel::completeTask,
                                    onChangeTaskStatus = viewModel::changeTaskStatus,
                                    onDeleteTask = viewModel::deleteTask,
                                    selectedBulkIds = bulkSelection,
                                    onToggleBulkSelection = viewModel::toggleBulkSelection,
                                    onClearBulkSelection = viewModel::clearBulkSelection,
                                    onBulkComplete = viewModel::completeBulkSelection,
                                    onBulkReschedule = { date ->
                                        val zone = ZoneId.systemDefault()
                                        viewModel.executeBulk(
                                            DomainCommand.RescheduleTasks(
                                                taskIds = bulkSelection.toList(),
                                                due = ZonedMoment(
                                                    instant = date
                                                        .atTime(DATE_ONLY_DUE_TIME)
                                                        .atZone(zone)
                                                        .toInstant(),
                                                    zoneId = zone.id,
                                                ),
                                            ),
                                        )
                                    },
                                    onBulkMoveToProject = { projectId ->
                                        viewModel.executeBulk(
                                            DomainCommand.MoveTasksToProject(
                                                taskIds = bulkSelection.toList(),
                                                projectId = projectId,
                                            ),
                                        )
                                    },
                                    onBulkSetTag = { tagId, present ->
                                        viewModel.executeBulk(
                                            DomainCommand.SetTasksTag(
                                                taskIds = bulkSelection.toList(),
                                                tagId = tagId,
                                                present = present,
                                            ),
                                        )
                                    },
                                    onBulkDelete = {
                                        viewModel.executeBulk(
                                            DomainCommand.DeleteTasks(bulkSelection.toList()),
                                        )
                                    },
                                    activeTimerTaskId = snapshot.home.activeTimer?.taskId,
                                    onToggleTimer = viewModel::toggleTimer,
                                    onUpdateTask = { taskId, edit ->
                                        viewModel.execute(edit.toCommand(taskId))
                                    },
                                    onAddChecklistItem = { taskId, text ->
                                        viewModel.execute(
                                            DomainCommand.AddChecklistItem(taskId, text),
                                        )
                                    },
                                    onUpdateChecklistItem = { taskId, item ->
                                        viewModel.execute(
                                            DomainCommand.UpdateChecklistItem(
                                                taskId = taskId,
                                                itemId = item.id,
                                                text = item.text,
                                                completed = item.completed,
                                            ),
                                        )
                                    },
                                    onDeleteChecklistItem = { taskId, itemId ->
                                        viewModel.execute(
                                            DomainCommand.DeleteChecklistItem(taskId, itemId),
                                        )
                                    },
                                    onSetTaskTag = { taskId, tagId, present ->
                                        viewModel.execute(
                                            DomainCommand.SetTaskTag(taskId, tagId, present),
                                        )
                                    },
                                    onCreateAndAssignTag = { taskId, name ->
                                        viewModel.execute(
                                            DomainCommand.CreateAndAssignTag(taskId, name),
                                        )
                                    },
                                    dependencyError = dependencyFeedback
                                        ?.takeIf { it.taskId == selectedTaskId }
                                        ?.message,
                                    onSetTaskDependency = viewModel::setTaskDependency,
                                    onClearDependencyError = viewModel::clearDependencyFeedback,
                                    timeEntries = snapshot.timeEntries,
                                    timeEntryConflicts = snapshot.timeEntryConflicts,
                                    onAddTimeEntry = { taskId, edit ->
                                        viewModel.execute(
                                            DomainCommand.AddTimeEntry(
                                                entryId = TimeEntryId.new(),
                                                taskId = taskId,
                                                startedAt = edit.startedAt,
                                                stoppedAt = edit.stoppedAt,
                                                note = edit.note,
                                            ),
                                        )
                                    },
                                    onUpdateTimeEntry = { entryId, edit ->
                                        viewModel.execute(
                                            DomainCommand.UpdateTimeEntry(
                                                entryId = entryId,
                                                startedAt = edit.startedAt,
                                                stoppedAt = edit.stoppedAt,
                                                note = edit.note,
                                            ),
                                        )
                                    },
                                    onDeleteTimeEntry = { entryId ->
                                        viewModel.execute(
                                            DomainCommand.DeleteTimeEntry(entryId),
                                        )
                                    },
                                    notificationsEnabled = notificationsEnabled,
                                    preciseRemindersAvailable = preciseRemindersAvailable,
                                    onEnableNotifications = ::enableNotifications,
                                    onEnablePreciseReminders = ::enablePreciseReminders,
                                    notes = snapshot.notes,
                                    activityEntries = snapshot.activityEntries,
                                    onAddNote = { taskId, body ->
                                        viewModel.execute(
                                            DomainCommand.AddNote(
                                                taskId = taskId,
                                                projectId = null,
                                                body = body,
                                            ),
                                        )
                                    },
                                    onUpdateNote = { noteId, body ->
                                        viewModel.execute(
                                            DomainCommand.UpdateNote(noteId, body),
                                        )
                                    },
                                    onDeleteNote = { noteId ->
                                        viewModel.execute(DomainCommand.DeleteNote(noteId))
                                    },
                                    attachments = snapshot.attachments,
                                    attachmentStates = attachmentStates,
                                    attachmentSetupRequired = attachmentSetupRequired,
                                    onAddAttachmentFromPhotos = { taskId ->
                                        pickAttachment(taskId, fromPhotos = true)
                                    },
                                    onAddAttachmentFromFiles = { taskId ->
                                        pickAttachment(taskId, fromPhotos = false)
                                    },
                                    onOpenAttachment = { attachmentId ->
                                        snapshot.attachment(attachmentId)
                                            ?.let(attachmentViewModel::open)
                                    },
                                    onShareAttachment = { attachmentId ->
                                        snapshot.attachment(attachmentId)
                                            ?.let(attachmentViewModel::share)
                                    },
                                    onDeleteAttachment = { attachmentId ->
                                        viewModel.execute(
                                            DomainCommand.DeleteAttachment(attachmentId),
                                        )
                                    },
                                    onRetryAttachment = { attachmentId ->
                                        snapshot.attachment(attachmentId)
                                            ?.let(attachmentViewModel::retry)
                                    },
                                    onOpenAttachmentSetup = ::openBackupRecovery,
                                    onAddToCalendar = selectedTask
                                        ?.let(::calendarPreviewFor)
                                        ?.let { preview -> { calendarPreview = preview } },
                                    onStartFocus = selectedTask?.let { task ->
                                        { option: FocusPresetOption ->
                                            viewModel.startFocus(
                                                task.id,
                                                option.toFocusPreset(),
                                            )
                                        }
                                    },
                                )
                            }
                            entry<ProjectsRoute> {
                                ProjectsScreen(
                                    projects = snapshot.projects,
                                    tasks = snapshot.tasks,
                                    milestones = snapshot.milestones,
                                    workflowStatuses = snapshot.workflowStatuses,
                                    selectedProjectId = selectedProjectId,
                                    showDetailPane = showDetailPane,
                                    listPaneFraction = listPaneFraction,
                                    boardMode = selectedProjectId in boardModeProjectIds,
                                    boardColumnWidth = boardColumnWidth,
                                    workbenchTaskGroups = workbenchTaskGroups,
                                    workbenchSort = workbenchArrangement.sort,
                                    workbenchGroupBy = workbenchArrangement.groupBy,
                                    onBoardModeChange = { enabled ->
                                        selectedProjectId?.let {
                                            viewModel.setBoardMode(it, enabled)
                                        }
                                    },
                                    onWorkbenchSortChange = { sort ->
                                        selectedProject?.let { project ->
                                            viewModel.setWorkbenchArrangement(
                                                project.id,
                                                viewModel.viewArrangement.value
                                                    .workbenchFor(project.id)
                                                    .copy(sort = sort),
                                            )
                                        }
                                    },
                                    onWorkbenchGroupChange = { groupBy ->
                                        selectedProject?.let { project ->
                                            viewModel.setWorkbenchArrangement(
                                                project.id,
                                                viewModel.viewArrangement.value
                                                    .workbenchFor(project.id)
                                                    .copy(groupBy = groupBy),
                                            )
                                        }
                                    },
                                    onChangeTaskStatus = { taskId, statusId ->
                                        viewModel.execute(
                                            DomainCommand.ChangeTaskStatus(taskId, statusId),
                                        )
                                    },
                                    onSelectProject = viewModel::selectProject,
                                    onCloseDetail = viewModel::closeProject,
                                    onUpdateProject = { projectId, edit ->
                                        viewModel.execute(edit.toCommand(projectId))
                                    },
                                    onArchiveProject = viewModel::archiveProject,
                                    onCreateWorkflowStatus = { projectId, name, semantic ->
                                        viewModel.execute(
                                            DomainCommand.CreateWorkflowStatus(
                                                statusId = WorkflowStatusId.new(),
                                                projectId = projectId,
                                                name = name,
                                                semanticStatus = semantic,
                                            ),
                                        )
                                    },
                                    onRenameWorkflowStatus = { statusId, name ->
                                        viewModel.execute(
                                            DomainCommand.RenameWorkflowStatus(statusId, name),
                                        )
                                    },
                                    onMoveWorkflowStatus = { statusId, direction ->
                                        viewModel.execute(
                                            DomainCommand.MoveWorkflowStatus(
                                                statusId = statusId,
                                                direction = when (direction) {
                                                    WorkflowMove.EARLIER ->
                                                        WorkflowMoveDirection.EARLIER
                                                    WorkflowMove.LATER ->
                                                        WorkflowMoveDirection.LATER
                                                },
                                            ),
                                        )
                                    },
                                    onArchiveWorkflowStatus = { statusId ->
                                        viewModel.execute(
                                            DomainCommand.ArchiveWorkflowStatus(statusId),
                                        )
                                    },
                                    onRestoreWorkflowStatus = { statusId ->
                                        viewModel.execute(
                                            DomainCommand.RestoreArchivedWorkflowStatus(statusId),
                                        )
                                    },
                                    onCreateMilestone = { projectId, name, dueDate ->
                                        viewModel.execute(
                                            DomainCommand.CreateMilestone(
                                                milestoneId = MilestoneId.new(),
                                                projectId = projectId,
                                                name = name,
                                                dueDate = dueDate,
                                            ),
                                        )
                                    },
                                    onUpdateMilestone = { milestoneId, name, dueDate, completedAt ->
                                        viewModel.execute(
                                            DomainCommand.UpdateMilestone(
                                                milestoneId = milestoneId,
                                                name = name,
                                                dueDate = dueDate,
                                                completedAt = completedAt,
                                            ),
                                        )
                                    },
                                    onDeleteMilestone = { milestoneId ->
                                        viewModel.execute(
                                            DomainCommand.DeleteMilestone(milestoneId),
                                        )
                                    },
                                    onCaptureTemplate = { projectId, name ->
                                        viewModel.execute(
                                            DomainCommand.CaptureProjectTemplate(
                                                templateId = TemplateId.new(),
                                                projectId = projectId,
                                                name = name,
                                            ),
                                        )
                                    },
                                    onOpenTask = { taskId ->
                                        viewModel.selectTask(taskId)
                                        navigate(TasksRoute)
                                    },
                                    notes = snapshot.notes,
                                    activityEntries = snapshot.activityEntries,
                                    onAddNote = { projectId, body ->
                                        viewModel.execute(
                                            DomainCommand.AddNote(
                                                taskId = null,
                                                projectId = projectId,
                                                body = body,
                                            ),
                                        )
                                    },
                                    onUpdateNote = { noteId, body ->
                                        viewModel.execute(
                                            DomainCommand.UpdateNote(noteId, body),
                                        )
                                    },
                                    onDeleteNote = { noteId ->
                                        viewModel.execute(DomainCommand.DeleteNote(noteId))
                                    },
                                )
                            }
                            entry<ScheduleRoute> {
                                ScheduleScreen(
                                    tasks = snapshot.tasks,
                                    projectNames = projectNames,
                                    expanded = layout.showNavigationRail,
                                    reminders = snapshot.reminders,
                                    today = snapshot.home.today,
                                    onOpenTask = { taskId ->
                                        viewModel.selectTask(taskId)
                                        navigate(TasksRoute)
                                    },
                                    calendarEligibleTaskIds = calendarEligibleTaskIds,
                                    onAddToCalendar = { taskId ->
                                        snapshot.tasks
                                            .firstOrNull { it.id == taskId }
                                            ?.let(::calendarPreviewFor)
                                            ?.let { preview -> calendarPreview = preview }
                                    },
                                )
                            }
                            entry<MoreRoute> {
                                MoreScreen(
                                    tasks = snapshot.tasks,
                                    projects = snapshot.projects,
                                    templates = snapshot.templates,
                                    insightsState = insightsUiState,
                                    insightsSummary = insightsSummary,
                                    openInsights = openInsightsOnMore,
                                    onInsightsClosed = {
                                        openInsightsOnMore = false
                                    },
                                    openBackupRecovery = openBackupOnMore,
                                    onBackupRecoveryClosed = {
                                        openBackupOnMore = false
                                    },
                                    onInsightsRangeChange = viewModel::setInsightsRange,
                                    onInsightsProjectFilter =
                                        viewModel::setInsightsProjectFilter,
                                    onInsightsTagFilter = viewModel::setInsightsTagFilter,
                                    onInsightsIncludeConflictedTimeChange =
                                        viewModel::setInsightsIncludeConflictedTime,
                                    onInsightsPresentationChange =
                                        viewModel::setInsightsPresentation,
                                    onOpenReview = {
                                        viewModel.startReview()
                                        navigate(ReviewRoute)
                                    },
                                    today = snapshot.home.today,
                                    onRestoreProject = { projectId ->
                                        viewModel.execute(
                                            DomainCommand.RestoreArchivedProject(projectId),
                                        )
                                    },
                                    onRestoreTask = { taskId ->
                                        viewModel.execute(DomainCommand.RestoreTask(taskId))
                                    },
                                    onPermanentlyDeleteTask = { taskId ->
                                        viewModel.execute(
                                            DomainCommand.PermanentlyDeleteTask(taskId),
                                        )
                                    },
                                    onUseTemplate = { templateId, name, anchorDate ->
                                        viewModel.addProjectFromTemplate(
                                            templateId = templateId,
                                            name = name,
                                            anchorDate = anchorDate,
                                            onCreated = { navigate(ProjectsRoute) },
                                        )
                                    },
                                    onDeleteTemplate = { templateId ->
                                        viewModel.execute(
                                            DomainCommand.DeleteTemplate(templateId),
                                        )
                                    },
                                    backupStatus = backupPresentation.status,
                                    remoteBackupStatus = encryptedBackupPresentation.status,
                                    canBackUpNow = encryptedBackupPresentation.canBackUpNow,
                                    canRestoreRemoteBackup =
                                        encryptedBackupPresentation.canRestore,
                                    canReauthoriseRemoteBackup =
                                        encryptedBackupPresentation.canReauthorise,
                                    canTakeOverRemoteBackup =
                                        encryptedBackupPresentation.canTakeOver,
                                    canPreserveRemoteBackup =
                                        encryptedBackupPresentation.canPreserveAsNewLineage,
                                    canChangeRemotePassphrase =
                                        encryptedBackupPresentation.canChangePassphrase,
                                    canDisconnectRemoteBackup =
                                        encryptedBackupPresentation.canDisconnect,
                                    canDeleteRemoteHistory =
                                        encryptedBackupPresentation.canDeleteHistory,
                                    passphraseChangeDisclosureVisible =
                                        encryptedBackupPresentation.passphraseChangeDisclosureVisible,
                                    canReprepareInitialBackup =
                                        backupPresentation.canReprepareInitialPackage,
                                    validateBackupPassphrase =
                                        RecoveryPassphrasePolicy::validate,
                                    onPrepareBackup = backupViewModel::prepare,
                                    onRetryBackup = backupViewModel::retry,
                                    onOpenSystemSettings = ::openSystemSettings,
                                    onConnectRemoteBackup = encryptedBackupViewModel::connect,
                                    onBackUpNow = encryptedBackupViewModel::backUpNow,
                                    onRestoreRemoteBackup =
                                        encryptedBackupViewModel::restoreOrTakeOver,
                                    onReauthoriseRemoteBackup =
                                        encryptedBackupViewModel::reauthorise,
                                    onTakeOverRemoteBackup =
                                        encryptedBackupViewModel::restoreOrTakeOver,
                                    onPreserveRemoteBackup =
                                        encryptedBackupViewModel::preserveAsNewLineage,
                                    onChangeRemotePassphrase =
                                        encryptedBackupViewModel::changePassphrase,
                                    onDisconnectRemoteBackup =
                                        encryptedBackupViewModel::disconnect,
                                    onDeleteRemoteHistory =
                                        encryptedBackupViewModel::deleteHistory,
                                    attachmentCacheUsageBytes = attachmentCacheUsage,
                                    onDeleteAttachmentContent =
                                        attachmentViewModel::deleteRemoteContent,
                                    vaultExportInProgress = vaultExportInProgress,
                                    vaultExportOutcome = vaultExportOutcome,
                                    onExportVaultPassphraseConfirmed =
                                        vaultTransferViewModel::beginExport,
                                    onDismissVaultExportOutcome =
                                        vaultTransferViewModel::dismissOutcome,
                                    vaultImportInProgress = vaultImportInProgress,
                                    vaultImportOutcome = vaultImportOutcome,
                                    onImportVaultPassphraseConfirmed =
                                        vaultTransferViewModel::beginImport,
                                    onConfirmVaultImport =
                                        vaultTransferViewModel::confirmImport,
                                    onDismissVaultImport =
                                        vaultTransferViewModel::dismissImport,
                                    csvExportInProgress = csvExportInProgress,
                                    csvExportOutcome = csvExportOutcome,
                                    onExportCsv = vaultTransferViewModel::beginCsvExport,
                                    onDismissCsvExportOutcome =
                                        vaultTransferViewModel::dismissCsvExportOutcome,
                                    csvImportInProgress = csvImportInProgress,
                                    csvImportOutcome = csvImportOutcome,
                                    onImportCsv = vaultTransferViewModel::beginCsvImport,
                                    onConfirmCsvImport =
                                        vaultTransferViewModel::confirmCsvImport,
                                    onCancelCsvImport =
                                        vaultTransferViewModel::cancelCsvImport,
                                    onDismissCsvImportOutcome =
                                        vaultTransferViewModel::dismissCsvImportOutcome,
                                    markdownExportInProgress = markdownExportInProgress,
                                    markdownExportOutcome = markdownExportOutcome,
                                    onExportMarkdown = vaultTransferViewModel::beginMarkdownExport,
                                    onDismissMarkdownExportOutcome =
                                        vaultTransferViewModel::dismissMarkdownExportOutcome,
                                    lockEnabled = lockEnabled,
                                    lockAvailable = lockAvailable,
                                    onLockEnabledChange = { appLockSettings.lockEnabled = it },
                                    lockDelayOption = lockDelay.toLockDelayOption(),
                                    onLockDelayOptionChange = { option ->
                                        appLockSettings.lockDelay = option.toLockDelay()
                                    },
                                    titlePrivacyEnabled = titlePrivacy,
                                    onTitlePrivacyChange = { appLockSettings.titlePrivacy = it },
                                    screenshotBlockingEnabled = screenshotBlocking,
                                    onScreenshotBlockingChange = { value ->
                                        appLockSettings.screenshotBlocking = value
                                    },
                                )
                            }
                            entry<ReviewRoute> {
                                ReviewScreen(
                                    queue = buildReviewQueue(snapshot, Instant.now()),
                                    projectNames = projectNames,
                                    reviewedTaskIds = reviewedTaskIds,
                                    reviewedProjectIds = reviewedProjectIds,
                                    actionPending = reviewActionPending,
                                    onBack = {
                                        viewModel.finishReview()
                                        navigate(MoreRoute)
                                    },
                                    onCompleteTask = { taskId, acknowledgeBlocked ->
                                        viewModel.executeReview(
                                            DomainCommand.CompleteTask(taskId, acknowledgeBlocked),
                                            taskId = taskId,
                                        )
                                    },
                                    onRescheduleTask = { taskId, date ->
                                        val zone = ZoneId.systemDefault()
                                        viewModel.executeReview(
                                            DomainCommand.RescheduleTasks(
                                                listOf(taskId),
                                                ZonedMoment(
                                                    date.atTime(DATE_ONLY_DUE_TIME)
                                                        .atZone(zone)
                                                        .toInstant(),
                                                    zone.id,
                                                ),
                                            ),
                                            taskId = taskId,
                                        )
                                    },
                                    onKeepTask = { taskId ->
                                        viewModel.executeReview(
                                            DomainCommand.MarkReviewed(taskId = taskId),
                                            taskId = taskId,
                                        )
                                    },
                                    onBinTask = { taskId ->
                                        viewModel.executeReview(
                                            DomainCommand.DeleteTask(taskId),
                                            taskId = taskId,
                                        )
                                    },
                                    onKeepProject = { projectId ->
                                        viewModel.executeReview(
                                            DomainCommand.MarkReviewed(projectId = projectId),
                                            projectId = projectId,
                                        )
                                    },
                                    onArchiveProject = { projectId ->
                                        viewModel.executeReview(
                                            DomainCommand.ArchiveProject(projectId),
                                            projectId = projectId,
                                        )
                                    },
                                )
                            }
                        },
                    )
                }
            }

            BackHandler(
                enabled = (selectedTaskId != null || selectedProjectId != null) && compact,
            ) {
                when {
                    selectedTaskId != null -> viewModel.closeTask()
                    selectedProjectId != null -> viewModel.closeProject()
                }
            }
        }

        if (showQuickAdd) {
            // Keyed on `quickAddSignal` so a second share or text-selection
            // intent arriving while the sheet is already visible restarts
            // its `rememberSaveable` title/due state, even when the shared
            // text is identical to what is already showing.
            //
            // `initialTitle` reads two sources because the `key` block's
            // first mount under a given `quickAddSignal` value happens on
            // one of two different composition passes, depending on
            // whether the sheet was already open:
            //  - Share while closed: this pass skips the block entirely
            //    (`showQuickAdd` is still false); the mount happens on the
            //    *next* pass, once `LaunchedEffect(quickAddSignal)` has
            //    already run and cleared `quickAddPrefillText` via
            //    `onQuickAddConsumed()`. `quickAddSheetTitle` -- captured by
            //    that same effect before consuming -- is what carries the
            //    text across to that mount.
            //  - Second share while already open: the remount happens in
            //    THIS pass, synchronously, before the effect for the new
            //    signal value has had a chance to run -- `quickAddPrefillText`
            //    is still populated here and wins over the (stale, one
            //    share behind) `quickAddSheetTitle`.
            // Neither source alone covers both mounts, hence the `?:`.
            key(quickAddSignal) {
                QuickAddSheet(
                    onDismiss = {
                        showQuickAdd = false
                        quickAddSheetTitle = ""
                    },
                    onAdd = { title, due ->
                        viewModel.addTask(title, due)
                        showQuickAdd = false
                        quickAddSheetTitle = ""
                    },
                    initialTitle = quickAddPrefillText ?: quickAddSheetTitle,
                )
            }
        }

        if (showNewProject) {
            NewProjectSheet(
                onDismiss = { showNewProject = false },
                onCreate = { name, summary ->
                    viewModel.addProject(name, summary)
                    showNewProject = false
                },
                existingProjectNames = snapshot.projects
                    .filter { it.archivedAt == null }
                    .mapTo(linkedSetOf()) { it.name },
            )
        }

        if (showSearch) {
            SearchSurface(
                results = searchResults,
                onQueryChange = viewModel::search,
                onDismiss = {
                    showSearch = false
                    viewModel.clearSearch()
                },
                onOpenTask = { id ->
                    showSearch = false
                    viewModel.clearSearch()
                    viewModel.selectTask(id)
                    navigate(TasksRoute)
                },
                onOpenProject = { projectId ->
                    showSearch = false
                    viewModel.clearSearch()
                    viewModel.selectProject(projectId)
                    navigate(ProjectsRoute)
                },
                savedViews = snapshot.savedViews,
                onSaveView = { name, query ->
                    viewModel.execute(
                        DomainCommand.CreateSavedView(SavedViewId.new(), name, query),
                    )
                },
                onRenameView = { id, name ->
                    viewModel.execute(DomainCommand.RenameSavedView(id, name))
                },
                onDeleteView = { id ->
                    viewModel.execute(DomainCommand.DeleteSavedView(id))
                },
            )
        }

        if (showShortcutHelp) {
            ShortcutHelpDialog(onDismiss = { showShortcutHelp = false })
        }

        if (pendingBlocked != null) {
            val blockerNames = pendingBlocked?.task?.blockedBy
                .orEmpty()
                .mapNotNull { blockerId ->
                    snapshot.tasks.firstOrNull { it.id == blockerId }?.title
                }
            AlertDialog(
                onDismissRequest = viewModel::dismissBlockedCompletion,
                icon = { Icon(Icons.Rounded.CheckCircle, contentDescription = null) },
                title = { Text("Complete blocked task?") },
                text = {
                    Text(
                        if (blockerNames.isEmpty()) {
                            "“${pendingBlocked?.task?.title}” is in a blocked workflow state. " +
                                "Complete it only if that state no longer reflects the work."
                        } else {
                            "“${pendingBlocked?.task?.title}” is still waiting for " +
                                blockerNames.joinToString(limit = 3) { "“$it”" } +
                                ". Completing it will preserve those links for review."
                        },
                    )
                },
                confirmButton = {
                    TextButton(onClick = viewModel::confirmBlockedCompletion) {
                        Text("Complete anyway")
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissBlockedCompletion) {
                        Text("Keep open")
                    }
                },
            )
        }

        if (pendingBlockedBulk) {
            AlertDialog(
                onDismissRequest = viewModel::dismissBlockedBulkCompletion,
                icon = { Icon(Icons.Rounded.CheckCircle, contentDescription = null) },
                title = { Text(stringResource(R.string.bulk_blocked_title)) },
                text = { Text(stringResource(R.string.bulk_blocked_body)) },
                confirmButton = {
                    TextButton(onClick = viewModel::confirmBlockedBulkCompletion) {
                        Text(stringResource(R.string.bulk_blocked_confirm))
                    }
                },
                dismissButton = {
                    TextButton(onClick = viewModel::dismissBlockedBulkCompletion) {
                        Text(stringResource(R.string.bulk_blocked_dismiss))
                    }
                },
            )
        }

        calendarPreview?.let { preview ->
            CalendarPreviewDialog(
                preview = preview,
                onDismiss = { calendarPreview = null },
                onInsert = {
                    try {
                        activity.startActivity(calendarInsertIntent(preview.draft))
                    } catch (_: ActivityNotFoundException) {
                        coroutineScope.launch {
                            snackbarHostState.showSnackbar(
                                activity.getString(R.string.calendar_no_provider),
                            )
                        }
                    }
                    calendarPreview = null
                },
            )
        }
    }
}

/**
 * "Formatted times in the moment's stored zone": begin and end are shown in
 * whichever [ZonedMoment][app.opentasks.core.model.ZonedMoment] zone
 * produced them, per [CalendarPreviewState], not the device's current zone.
 */
@Composable
private fun CalendarPreviewDialog(
    preview: CalendarPreviewState,
    onDismiss: () -> Unit,
    onInsert: () -> Unit,
) {
    val beginText = CALENDAR_PREVIEW_TIME_FORMAT.format(
        Instant.ofEpochMilli(preview.draft.beginEpochMillis).atZone(ZoneId.of(preview.beginZoneId)),
    )
    val endText = preview.endZoneId?.let { zoneId ->
        CALENDAR_PREVIEW_TIME_FORMAT.format(
            Instant.ofEpochMilli(checkNotNull(preview.draft.endEpochMillis))
                .atZone(ZoneId.of(zoneId)),
        )
    }
    AlertDialog(
        onDismissRequest = onDismiss,
        icon = { Icon(Icons.Rounded.CalendarMonth, contentDescription = null) },
        title = { Text(preview.draft.title) },
        text = {
            Column {
                Text(stringResource(R.string.calendar_preview_starts, beginText))
                endText?.let { Text(stringResource(R.string.calendar_preview_ends, it)) }
                if (preview.draft.description.isNotEmpty()) {
                    Spacer(Modifier.height(8.dp))
                    Text(preview.draft.description)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = onInsert) {
                Text(stringResource(R.string.calendar_insert_action))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.calendar_cancel_action))
            }
        },
    )
}

private val CALENDAR_PREVIEW_TIME_FORMAT: DateTimeFormatter =
    DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.UK)

private fun csvExportFileName(table: CsvTable): String = when (table) {
    CsvTable.TASKS -> "open_tasks_tasks.csv"
    CsvTable.PROJECTS -> "open_tasks_projects.csv"
    CsvTable.TIME_ENTRIES -> "open_tasks_time_entries.csv"
    CsvTable.NOTES -> "open_tasks_notes.csv"
}

/**
 * `:app` is the only side that may reference both `:app`'s own
 * [app.opentasks.lock.LockDelay] and `feature:more`'s UI-facing
 * [LockDelayOption] -- `feature:more` depends only on `:core:model` and
 * `:core:designsystem`, never on `:app`, so MoreScreen's picker uses its
 * own small enum instead, the same way it already has its own
 * `CsvExportTable`.
 */
private fun LockDelay.toLockDelayOption(): LockDelayOption = when (this) {
    LockDelay.IMMEDIATE -> LockDelayOption.IMMEDIATE
    LockDelay.ONE_MINUTE -> LockDelayOption.ONE_MINUTE
    LockDelay.FIVE_MINUTES -> LockDelayOption.FIVE_MINUTES
    LockDelay.FIFTEEN_MINUTES -> LockDelayOption.FIFTEEN_MINUTES
}

private fun LockDelayOption.toLockDelay(): LockDelay = when (this) {
    LockDelayOption.IMMEDIATE -> LockDelay.IMMEDIATE
    LockDelayOption.ONE_MINUTE -> LockDelay.ONE_MINUTE
    LockDelayOption.FIVE_MINUTES -> LockDelay.FIVE_MINUTES
    LockDelayOption.FIFTEEN_MINUTES -> LockDelay.FIFTEEN_MINUTES
}

/** The same one-sided mapping as [toLockDelay], for `feature:tasks`. */
private fun FocusPresetOption.toFocusPreset(): FocusPreset = when (this) {
    FocusPresetOption.TWENTY_FIVE_FIVE -> FocusPreset.TWENTY_FIVE_FIVE
    FocusPresetOption.FIFTY_TEN -> FocusPreset.FIFTY_TEN
}

/**
 * A compact strip above the bottom bar while a focus cycle runs: which phase
 * it is in, how long that phase has left, and the one way out. There is no
 * skip control -- a cycle is either running or stopped.
 *
 * The remaining time ticks once a second off the phase end, so it stays right
 * across a recomposition and needs no state of its own beyond the tick.
 */
@Composable
private fun FocusBanner(
    session: FocusSession,
    notificationsEnabled: Boolean,
    hasNavigationBarBelow: Boolean,
    onEnableNotifications: () -> Unit,
    onStop: () -> Unit,
) {
    var now by remember(session) { mutableStateOf(Instant.now()) }
    LaunchedEffect(session) {
        while (true) {
            delay(1_000L)
            now = Instant.now()
        }
    }
    val remaining = Duration.between(now, session.phaseEndsAt).coerceAtLeast(Duration.ZERO)
    // The navigation bar consumes the bottom inset itself when it is there;
    // without it this strip is the bottom-most surface and must do so.
    val insetSides = if (hasNavigationBarBelow) {
        WindowInsetsSides.Horizontal
    } else {
        WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom
    }
    Surface(
        tonalElevation = 3.dp,
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        modifier = Modifier
            .fillMaxWidth()
            .testTag("focus-banner"),
    ) {
        Row(
            modifier = Modifier
                .windowInsetsPadding(WindowInsets.safeDrawing.only(insetSides))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    stringResource(
                        when (session.phase) {
                            FocusPhaseKind.FOCUS -> R.string.focus_banner_focus_phase
                            FocusPhaseKind.REST -> R.string.focus_banner_rest_phase
                        },
                    ),
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    stringResource(
                        R.string.focus_banner_remaining,
                        formatFocusRemaining(remaining),
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            if (!notificationsEnabled) {
                TextButton(
                    onClick = onEnableNotifications,
                    modifier = Modifier.heightIn(min = 48.dp),
                ) {
                    Text(stringResource(R.string.focus_banner_enable_notifications))
                }
            }
            TextButton(
                onClick = onStop,
                modifier = Modifier
                    .heightIn(min = 48.dp)
                    .testTag("focus-stop"),
            ) {
                Text(stringResource(R.string.focus_banner_stop))
            }
        }
    }
}

private fun formatFocusRemaining(remaining: Duration): String = String.format(
    Locale.UK,
    "%d:%02d",
    remaining.toMinutes(),
    remaining.toSecondsPart(),
)

private fun WorkspaceSnapshot.attachment(attachmentId: AttachmentId): Attachment? =
    attachments.firstOrNull { it.id == attachmentId }

private fun TaskEdit.toCommand(taskId: TaskId): DomainCommand.UpdateTask =
    DomainCommand.UpdateTask(
        taskId = taskId,
        title = title,
        description = description,
        projectId = projectId,
        priority = priority,
        due = due,
        recurrence = recurrence,
        estimate = estimate,
        milestoneId = milestoneId,
        reminder = reminder,
    )

private fun ProjectEdit.toCommand(projectId: ProjectId): DomainCommand.UpdateProject =
    DomainCommand.UpdateProject(
        projectId = projectId,
        name = name,
        summary = summary,
        health = health,
        dueDate = dueDate,
    )
