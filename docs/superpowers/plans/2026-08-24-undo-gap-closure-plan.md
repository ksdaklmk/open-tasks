# Undo Gap Closure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use
> superpowers:subagent-driven-development to implement this plan task-by-task.
> Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make child-project moves and safe-detaching Bin restores fully
undoable in both repository engines.

**Architecture:** Extend the two existing inverse command shapes with one
optional, repository-only parent ID. Reuse `SubtaskRules` for relationship
validation, then apply the parent change inside the existing atomic command
write; do not add a coordinator, persistence field, or UI path.

**Tech Stack:** Kotlin, coroutines, Android Room, JUnit 4, Gradle.

**Spec:**
`docs/superpowers/specs/2026-08-24-undo-gap-closure-design.md`
(approved in commit `e125faa`).

**Execution status:** Subagent-driven execution was selected on 2026-08-24,
then explicitly paused before Task 1. This is the next plan in the approved
sequence; do not initialize its SDD workspace or dispatch an agent until the
owner resumes execution.

## Global Constraints

- Preserve the current forward rules: moving a child alone detaches it, and
  restoring beneath a missing, binned, or cross-project parent detaches it.
- `restoreParentTaskId` is repository-produced Undo metadata; UI callers
  leave it null.
- Keep in-memory and Room behavior, rejection reasons, activity, revisions,
  and journal generation symmetric.
- No UI, Room schema, backup format or fixture, dependency, permission,
  signing, version, or release-artifact change.
- Follow strict RED-GREEN: add each behavioral assertion and observe the
  expected host failure before changing production code.
- Never run connected tests on the protected emulator. A connected run is
  permitted only when an explicitly safe disposable target is the sole
  selected device; otherwise compile the Android tests and record that limit.
- Stage only the exact files named by each task. Preserve every unrelated
  modified, deleted, and untracked workspace entry.
- Work directly on `main`, as required by the repository guidance, with a
  focused commit after each independently green behavior.

## File Map

- Modify
  `core/domain/src/main/kotlin/app/opentasks/core/domain/VaultRepository.kt`:
  add optional parent metadata to the two existing inverse command shapes.
- Modify
  `core/domain/src/main/kotlin/app/opentasks/core/domain/SubtaskRules.kt`:
  add the historical-link variant of the existing one-level guard.
- Modify
  `core/domain/src/test/kotlin/app/opentasks/core/domain/SubtaskRulesTest.kt`:
  prove that historical binned/cross-project links are representable while
  missing and nested links are rejected.
- Modify
  `core/data/src/main/kotlin/app/opentasks/core/data/InMemoryVaultRepository.kt`:
  capture, validate, and replay parent metadata in the in-memory engine.
- Modify
  `core/data/src/main/kotlin/app/opentasks/core/data/RoomVaultRepository.kt`:
  implement the same behavior with authoritative-row rechecks.
- Modify
  `core/data/src/test/kotlin/app/opentasks/core/data/InMemoryVaultRepositoryTest.kt`:
  host RED-GREEN coverage for both Undo gaps and delayed rejection.
- Modify
  `core/data/src/androidTest/kotlin/app/opentasks/core/data/RoomVaultRepositoryInstrumentedTest.kt`:
  Room parity coverage using the existing real repository fixture.
- Modify `HANDOFF.md`: record the closed gaps, proof, and next approved
  programme step.

---

### Task 1: Restore the parent when undoing a detached project move

**Files:**

- Modify:
  `core/domain/src/main/kotlin/app/opentasks/core/domain/VaultRepository.kt:222-242`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/InMemoryVaultRepository.kt:1760-1845,2548-2790,4348-4378`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/RoomVaultRepository.kt:1854-1955,2620-2840,4410-4442`
- Test:
  `core/data/src/test/kotlin/app/opentasks/core/data/InMemoryVaultRepositoryTest.kt:2260-2345`
- Test:
  `core/data/src/androidTest/kotlin/app/opentasks/core/data/RoomVaultRepositoryInstrumentedTest.kt:2990-3135`

**Interfaces:**

- Consumes: `DomainCommand.UpdateTask`, `SubtaskRules.parentViolation`, each
  repository's `Task.toUpdateCommand`, and `UndoBatch` preflight.
- Produces:
  `DomainCommand.UpdateTask.restoreParentTaskId: TaskId? = null` and identical
  move-Undo semantics in both repository engines.

- [ ] **Step 1: Extend the in-memory move test with the missing round trip**

In the child-only half of
`movingAParentCarriesChildrenAndMovingAChildAloneDetaches`, retain the
successful command and append these assertions after the existing detach
checks:

```kotlin
val moved = repository.execute(
    DomainCommand.MoveTasksToProject(listOf(child.id), destination.id),
) as CommandResult.Success
val afterMove = repository.currentWorkspace().tasks
assertEquals(destination.id, afterMove.first { it.id == child.id }.projectId)
assertNull(afterMove.first { it.id == child.id }.parentTaskId)
assertEquals(project.id, afterMove.first { it.id == parent.id }.projectId)

repository.execute(requireNotNull(moved.undo))
val afterUndo = repository.currentWorkspace().tasks.first { it.id == child.id }
assertEquals(child.projectId, afterUndo.projectId)
assertEquals(child.statusId, afterUndo.statusId)
assertEquals(parent.id, afterUndo.parentTaskId)
```

Add a separate delayed-Undo test. Its mutation check is removing the parent
validation: the Undo would move the child and return success.

```kotlin
@Test
fun movingAChildUndoRejectsAfterFormerParentIsPurged() = runBlocking {
    withTimeout(5_000) {
        val repository = InMemoryVaultRepository()
        val project = OpenTasksFixtures.studioProject
        val destination = OpenTasksFixtures.taxProject
        val candidates = repository.currentWorkspace().tasks.filter {
            it.projectId == project.id && it.deletedAt == null &&
                !it.isCompleted && it.parentTaskId == null && !it.isBlocked
        }
        val parent = candidates[0]
        val child = candidates[1]
        repository.execute(DomainCommand.SetTaskParent(child.id, parent.id))
        val moved = repository.execute(
            DomainCommand.MoveTasksToProject(listOf(child.id), destination.id),
        ) as CommandResult.Success
        repository.execute(DomainCommand.DeleteTask(parent.id))
        repository.execute(DomainCommand.PermanentlyDeleteTask(parent.id))
        val beforeUndo = repository.currentWorkspace().tasks.first { it.id == child.id }

        val rejected = repository.execute(requireNotNull(moved.undo))
            as CommandResult.Rejected

        assertEquals(RejectionReason.SUBTASK_PARENT_INVALID, rejected.reason)
        assertEquals(
            beforeUndo,
            repository.currentWorkspace().tasks.first { it.id == child.id },
        )
    }
}
```

- [ ] **Step 2: Run the focused host tests and verify RED**

Run:

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests "*movingAParentCarriesChildrenAndMovingAChildAloneDetaches" \
  --tests "*movingAChildUndoRejectsAfterFormerParentIsPurged" \
  --console=plain
```

Expected: FAIL. The round trip leaves `parentTaskId` null, and the delayed
Undo succeeds instead of returning `SUBTASK_PARENT_INVALID`.

- [ ] **Step 3: Add the matching Room assertions before production changes**

In the child-only half of the Room test, retain `movedChildAlone` as a
`CommandResult.Success`, then append:

```kotlin
repository!!.execute(requireNotNull((movedChildAlone as CommandResult.Success).undo))
val afterChildUndo = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
    repository!!.observeWorkspace().first { snapshot ->
        snapshot.tasks.firstOrNull { it.id == aloneChild.id }?.let { task ->
            task.projectId == aloneChild.projectId &&
                task.statusId == aloneChild.statusId &&
                task.parentTaskId == aloneParent.id
        } == true
    }
}.tasks.first { it.id == aloneChild.id }
assertEquals(aloneChild.projectId, afterChildUndo.projectId)
assertEquals(aloneChild.statusId, afterChildUndo.statusId)
assertEquals(aloneParent.id, afterChildUndo.parentTaskId)
```

Add the real-Room delayed test:

```kotlin
@Test
fun movingAChildUndoRejectsAfterFormerParentIsPurged() = runBlocking {
    openRepository(now = { Instant.parse("2026-08-24T05:00:00Z") })
    val destination = OpenTasksFixtures.taxProject
    repository!!.execute(DomainCommand.CreateTask("Purged move parent"))
    repository!!.execute(DomainCommand.CreateTask("Purged move child"))
    val pair = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
        repository!!.observeWorkspace().first { snapshot ->
            snapshot.tasks.any { it.title == "Purged move parent" } &&
                snapshot.tasks.any { it.title == "Purged move child" }
        }
    }.tasks.associateBy { it.title }
    val parent = checkNotNull(pair["Purged move parent"])
    val child = checkNotNull(pair["Purged move child"])
    repository!!.execute(DomainCommand.SetTaskParent(child.id, parent.id))
    val moved = repository!!.execute(
        DomainCommand.MoveTasksToProject(listOf(child.id), destination.id),
    ) as CommandResult.Success
    repository!!.execute(DomainCommand.DeleteTask(parent.id))
    repository!!.execute(DomainCommand.PermanentlyDeleteTask(parent.id))
    val beforeUndo = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
        repository!!.observeWorkspace().first { snapshot ->
            snapshot.tasks.none { it.id == parent.id }
        }
    }.tasks.first { it.id == child.id }

    val rejected = repository!!.execute(requireNotNull(moved.undo))
        as CommandResult.Rejected

    assertEquals(RejectionReason.SUBTASK_PARENT_INVALID, rejected.reason)
    assertEquals(
        beforeUndo,
        repository!!.currentWorkspace().tasks.first { it.id == child.id },
    )
}
```

- [ ] **Step 4: Compile the Room test and conditionally observe its RED state**

Run:

```bash
./gradlew :core:data:compileDebugAndroidTestKotlin --console=plain
```

Expected: PASS compilation. If `adb devices -l` confirms an authorized
disposable target is the sole selected device, run the two Room methods and
expect the same behavioral failures as the host tests. Do not substitute the
protected emulator for that evidence.

- [ ] **Step 5: Add the minimal move-Undo metadata and validation**

In `DomainCommand.UpdateTask`, add the field immediately after
`restoreStatusId` and document that only repositories set it:

```kotlin
/**
 * [restoreParentTaskId] is repository-produced Undo metadata. Normal UI
 * code must leave it null.
 */
data class UpdateTask(
    val taskId: TaskId,
    val title: String,
    val description: String,
    val projectId: ProjectId?,
    val priority: Priority,
    val start: ZonedMoment?,
    val due: ZonedMoment?,
    val recurrence: RecurrenceRule?,
    val estimate: Duration?,
    val milestoneId: MilestoneId? = null,
    val recurrenceMetadata: RecurrenceSeriesMetadata? = null,
    val restoreStatusId: WorkflowStatusId? = null,
    val restoreParentTaskId: TaskId? = null,
    val reminder: Reminder? = null,
    val restorePastReminder: Boolean = false,
) : DomainCommand
```

In both repositories, add `targetParentTaskId: TaskId?` to
`TaskUpdateValidation.Valid`:

```kotlin
data class Valid(
    val task: Task,
    val existingReminder: Reminder?,
    val requestedReminder: Reminder?,
    val requestedMilestone: Milestone?,
    val recurrenceMetadata: RecurrenceSeriesMetadata?,
    val targetStatus: WorkflowStatus,
    val targetParentTaskId: TaskId?,
) : TaskUpdateValidation
```

Keep Room's existing `requestedProject: ProjectEntity?` field in its `Valid`
shape; its complete field list becomes:

```kotlin
data class Valid(
    val task: Task,
    val existingReminder: Reminder?,
    val requestedReminder: Reminder?,
    val requestedProject: ProjectEntity?,
    val requestedMilestone: MilestoneEntity?,
    val recurrenceMetadata: RecurrenceSeriesMetadata?,
    val targetStatus: WorkflowStatus,
    val targetParentTaskId: TaskId?,
) : TaskUpdateValidation
```

In in-memory validation, compute and validate the parent before returning the
plan:

```kotlin
val targetParentTaskId = command.restoreParentTaskId ?: task.parentTaskId
command.restoreParentTaskId?.let { parentId ->
    val projectedTasks = current.tasks.map { candidate ->
        if (candidate.id == task.id) {
            candidate.copy(projectId = command.projectId)
        } else {
            candidate
        }
    }
    SubtaskRules.parentViolation(projectedTasks, task.id, parentId)?.let { violation ->
        return invalidTaskUpdate(
            RejectionReason.SUBTASK_PARENT_INVALID,
            subtaskViolationMessage(violation),
        )
    }
}
```

Pass it through the named constructor:

```kotlin
return TaskUpdateValidation.Valid(
    task = task,
    existingReminder = existingReminder,
    requestedReminder = requestedReminder,
    requestedMilestone = requestedMilestone,
    recurrenceMetadata = recurrenceMetadata,
    targetStatus = targetStatus,
    targetParentTaskId = targetParentTaskId,
)
```

At the start of `updateTaskDetails`, read
`val targetParentTaskId = plan.targetParentTaskId`. Include it in the no-op
comparison and the updated task:

```kotlin
task.parentTaskId == targetParentTaskId &&
```

```kotlin
parentTaskId = targetParentTaskId,
```

Room performs the same projected `SubtaskRules.parentViolation` check, then
uses the established live-row defence before returning the valid plan:

```kotlin
val targetParentTaskId = command.restoreParentTaskId ?: task.parentTaskId
command.restoreParentTaskId?.let { parentId ->
    val projectedTasks = mutableWorkspace.value.tasks.map { candidate ->
        if (candidate.id == task.id) {
            candidate.copy(projectId = command.projectId)
        } else {
            candidate
        }
    }
    val snapshotViolation = SubtaskRules.parentViolation(
        projectedTasks,
        task.id,
        parentId,
    )
    if (snapshotViolation != null) {
        return invalidTaskUpdate(
            RejectionReason.SUBTASK_PARENT_INVALID,
            subtaskViolationMessage(snapshotViolation),
        )
    }
    val liveParent = database.taskDao().getById(parentId.value)
    val liveViolation = when {
        liveParent == null || liveParent.deletedAtEpochMillis != null ->
            SubtaskViolation.PARENT_MISSING_OR_BINNED
        liveParent.projectId != command.projectId?.value ->
            SubtaskViolation.CROSS_PROJECT
        liveParent.parentTaskId != null -> SubtaskViolation.PARENT_IS_A_SUBTASK
        database.taskDao().liveChildren(task.id.value).isNotEmpty() ->
            SubtaskViolation.TASK_HAS_SUBTASKS
        else -> null
    }
    if (liveViolation != null) {
        return invalidTaskUpdate(
            RejectionReason.SUBTASK_PARENT_INVALID,
            subtaskViolationMessage(liveViolation),
        )
    }
}
```

Room's named `TaskUpdateValidation.Valid` call includes all of its resolved
entities plus the new value:

```kotlin
return TaskUpdateValidation.Valid(
    task = task,
    existingReminder = existingReminder,
    requestedReminder = requestedReminder,
    requestedProject = requestedProject,
    requestedMilestone = requestedMilestone,
    recurrenceMetadata = recurrenceMetadata,
    targetStatus = targetStatus,
    targetParentTaskId = targetParentTaskId,
)
```

Update both `Task.toUpdateCommand` helpers:

```kotlin
private fun Task.toUpdateCommand(
    reminder: Reminder? = null,
    restoreParentTaskId: TaskId? = null,
): DomainCommand.UpdateTask = DomainCommand.UpdateTask(
    taskId = id,
    title = title,
    description = description,
    projectId = projectId,
    priority = priority,
    start = start,
    due = due,
    recurrence = recurrence,
    estimate = estimate,
    milestoneId = milestoneId,
    restoreStatusId = statusId,
    restoreParentTaskId = restoreParentTaskId,
    reminder = reminder,
    restorePastReminder = true,
    recurrenceMetadata = recurrence?.let {
        val seriesId = recurrenceSeriesId
        val anchor = recurrenceAnchor
        val occurrenceIndex = recurrenceOccurrenceIndex
        if (seriesId != null && anchor != null && occurrenceIndex != null) {
            RecurrenceSeriesMetadata(seriesId, anchor, occurrenceIndex)
        } else {
            null
        }
    },
)
```

In each `MoveTasksToProject` inverse call, pass the captured relationship only
for the existing detach plan:

```kotlin
restoreParentTaskId = if (plan.detach) task.parentTaskId else null,
```

- [ ] **Step 6: Run the focused tests and verify GREEN**

Run the host command from Step 2, then:

```bash
./gradlew :core:data:compileDebugAndroidTestKotlin --console=plain
```

Expected: both host tests PASS and Room Android-test Kotlin compiles.

- [ ] **Step 7: Run Room parity only on a safe disposable target**

If the safety condition from Step 4 is satisfied, run:

```bash
./gradlew :core:data:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=app.opentasks.core.data.RoomVaultRepositoryInstrumentedTest \
  --console=plain
```

Expected: PASS. Otherwise retain the compilation receipt and defer the
connected receipt without touching the protected emulator.

- [ ] **Step 8: Commit the move-Undo slice**

```bash
git add core/domain/src/main/kotlin/app/opentasks/core/domain/VaultRepository.kt \
  core/data/src/main/kotlin/app/opentasks/core/data/InMemoryVaultRepository.kt \
  core/data/src/main/kotlin/app/opentasks/core/data/RoomVaultRepository.kt \
  core/data/src/test/kotlin/app/opentasks/core/data/InMemoryVaultRepositoryTest.kt \
  core/data/src/androidTest/kotlin/app/opentasks/core/data/RoomVaultRepositoryInstrumentedTest.kt
git diff --cached --check
git commit -m "fix: restore subtask parent on move undo"
```

---

### Task 2: Preserve the parent when undoing a safe-detaching restore

**Files:**

- Modify:
  `core/domain/src/main/kotlin/app/opentasks/core/domain/VaultRepository.kt:360-370`
- Modify:
  `core/domain/src/main/kotlin/app/opentasks/core/domain/SubtaskRules.kt:20-48`
- Test:
  `core/domain/src/test/kotlin/app/opentasks/core/domain/SubtaskRulesTest.kt:1-68`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/InMemoryVaultRepository.kt:2300-2420`
- Modify:
  `core/data/src/main/kotlin/app/opentasks/core/data/RoomVaultRepository.kt:2410-2530`
- Test:
  `core/data/src/test/kotlin/app/opentasks/core/data/InMemoryVaultRepositoryTest.kt:2200-2390`
- Test:
  `core/data/src/androidTest/kotlin/app/opentasks/core/data/RoomVaultRepositoryInstrumentedTest.kt:2940-3180`

**Interfaces:**

- Consumes: the move slice's command conventions, `RestoreTask`, `DeleteTask`,
  `SubtaskViolation`, and the existing repository transaction boundaries.
- Produces:
  `SubtaskRules.historicalParentViolation(tasks, taskId, parentTaskId)` and
  `DomainCommand.DeleteTask.restoreParentTaskId: TaskId? = null`.

- [ ] **Step 1: Specify the historical one-level rule with a failing domain test**

Append these tests to `SubtaskRulesTest`:

```kotlin
@Test
fun historicalParentAllowsBinnedCrossProjectHistory() {
    val base = OpenTasksFixtures.tasks.first { it.deletedAt == null }
    val child = base.copy(id = TaskId("history-child"), parentTaskId = null)
    val parent = base.copy(
        id = TaskId("history-parent"),
        projectId = OpenTasksFixtures.taxProject.id,
        deletedAt = Instant.parse("2026-08-24T00:00:00Z"),
        parentTaskId = null,
    )

    assertNull(
        SubtaskRules.historicalParentViolation(
            listOf(child, parent),
            child.id,
            parent.id,
        ),
    )
}

@Test
fun historicalParentRejectsMissingOrNestedRelationships() {
    val base = OpenTasksFixtures.tasks.first { it.deletedAt == null }
    val task = base.copy(id = TaskId("history-task"), parentTaskId = null)
    val parent = base.copy(id = TaskId("history-parent"), parentTaskId = null)
    val nestedParent = base.copy(
        id = TaskId("history-nested-parent"),
        parentTaskId = TaskId("history-grandparent"),
    )
    val child = base.copy(
        id = TaskId("history-existing-child"),
        parentTaskId = task.id,
    )

    assertEquals(
        SubtaskViolation.SELF,
        SubtaskRules.historicalParentViolation(
            listOf(task, parent),
            task.id,
            task.id,
        ),
    )
    assertEquals(
        SubtaskViolation.PARENT_MISSING_OR_BINNED,
        SubtaskRules.historicalParentViolation(
            listOf(task, parent),
            task.id,
            TaskId("history-missing"),
        ),
    )
    assertEquals(
        SubtaskViolation.PARENT_IS_A_SUBTASK,
        SubtaskRules.historicalParentViolation(
            listOf(task, nestedParent),
            task.id,
            nestedParent.id,
        ),
    )
    assertEquals(
        SubtaskViolation.TASK_HAS_SUBTASKS,
        SubtaskRules.historicalParentViolation(
            listOf(task, parent, child),
            task.id,
            parent.id,
        ),
    )
}
```

- [ ] **Step 2: Run the domain test and verify RED**

Run:

```bash
./gradlew :core:domain:testDebugUnitTest --tests "*SubtaskRulesTest" --console=plain
```

Expected: FAIL to compile because `historicalParentViolation` does not exist.

- [ ] **Step 3: Add the smallest shared historical guard and verify GREEN**

Add this method to the existing `SubtaskRules` object:

```kotlin
fun historicalParentViolation(
    tasks: List<Task>,
    taskId: TaskId,
    parentTaskId: TaskId,
): SubtaskViolation? {
    if (taskId == parentTaskId) return SubtaskViolation.SELF
    val parent = tasks.firstOrNull { it.id == parentTaskId }
        ?: return SubtaskViolation.PARENT_MISSING_OR_BINNED
    if (parent.parentTaskId != null) return SubtaskViolation.PARENT_IS_A_SUBTASK
    if (tasks.any { it.parentTaskId == taskId }) {
        return SubtaskViolation.TASK_HAS_SUBTASKS
    }
    return null
}
```

Run the command from Step 2. Expected: PASS. This rule deliberately does not
check parent liveness or same-project membership because both rows are about
to be binned; ordinary live attachment continues to use `parentViolation`.

- [ ] **Step 4: Add host RED coverage for both restore-detach cases**

In `binningAParentTakesTheSubtreeAndRestoreDetaches`, capture the restore
result and append the round trip:

```kotlin
val restoreResult = repository.execute(DomainCommand.RestoreTask(child.id))
    as CommandResult.Success
val detached = repository.currentWorkspace().tasks.first { it.id == child.id }
assertNull(detached.deletedAt)
assertNull(detached.parentTaskId)

repository.execute(requireNotNull(restoreResult.undo))
val reBinned = repository.currentWorkspace().tasks.first { it.id == child.id }
assertNotNull(reBinned.deletedAt)
assertEquals(parent.id, reBinned.parentTaskId)
```

In `restoringABinnedChildDetachesFromAParentThatMovedToAnotherProject`, retain
the `CommandResult.Success`, then append:

```kotlin
val restoreResult = repository.execute(DomainCommand.RestoreTask(child.id))
    as CommandResult.Success
val restored = repository.currentWorkspace().tasks.first { it.id == child.id }
assertNull(restored.deletedAt)
assertNull(restored.parentTaskId)
assertEquals(project.id, restored.projectId)

repository.execute(requireNotNull(restoreResult.undo))
val reBinned = repository.currentWorkspace().tasks.first { it.id == child.id }
assertNotNull(reBinned.deletedAt)
assertEquals(parent.id, reBinned.parentTaskId)
```

Add the delayed-parent deletion case. Its mutation check is deleting the
metadata validation: the child would be binned with a dangling parent ID.

```kotlin
@Test
fun restoreUndoRejectsAfterFormerParentIsPurged() = runBlocking {
    withTimeout(5_000) {
        val repository = InMemoryVaultRepository()
        val project = OpenTasksFixtures.studioProject
        val candidates = repository.currentWorkspace().tasks.filter {
            it.projectId == project.id && it.deletedAt == null &&
                !it.isCompleted && it.parentTaskId == null && !it.isBlocked
        }
        val parent = candidates[0]
        val child = candidates[1]
        repository.execute(DomainCommand.SetTaskParent(child.id, parent.id))
        repository.execute(DomainCommand.DeleteTask(parent.id))
        val restored = repository.execute(DomainCommand.RestoreTask(child.id))
            as CommandResult.Success
        repository.execute(DomainCommand.PermanentlyDeleteTask(parent.id))
        val beforeUndo = repository.currentWorkspace().tasks.first { it.id == child.id }

        val rejected = repository.execute(requireNotNull(restored.undo))
            as CommandResult.Rejected

        assertEquals(RejectionReason.SUBTASK_PARENT_INVALID, rejected.reason)
        assertEquals(
            beforeUndo,
            repository.currentWorkspace().tasks.first { it.id == child.id },
        )
    }
}
```

- [ ] **Step 5: Run the focused host tests and verify RED**

Run:

```bash
./gradlew :core:data:testDebugUnitTest \
  --tests "*binningAParentTakesTheSubtreeAndRestoreDetaches" \
  --tests "*restoringABinnedChildDetachesFromAParentThatMovedToAnotherProject" \
  --tests "*restoreUndoRejectsAfterFormerParentIsPurged" \
  --console=plain
```

Expected: FAIL. Both round trips retain null parents, and the delayed Undo
does not return `SUBTASK_PARENT_INVALID`.

- [ ] **Step 6: Add equivalent real-Room assertions and delayed rejection**

In each of `binningAParentTakesTheSubtreeAndRestoreDetaches` and
`restoringABinnedChildDetachesFromAParentThatMovedToAnotherProject`, replace
the plain `RestoreTask` execution and detached lookup with this exact block:

```kotlin
val restoreResult = repository!!.execute(DomainCommand.RestoreTask(child.id))
    as CommandResult.Success
val detached = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
    repository!!.observeWorkspace().first { snapshot ->
        snapshot.tasks.firstOrNull { it.id == child.id }?.let { task ->
            task.deletedAt == null && task.parentTaskId == null
        } == true
    }
}.tasks.first { it.id == child.id }
assertNull(detached.deletedAt)
assertNull(detached.parentTaskId)

repository!!.execute(requireNotNull(restoreResult.undo))
val reBinned = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
    repository!!.observeWorkspace().first { snapshot ->
        snapshot.tasks.firstOrNull { it.id == child.id }?.let { task ->
            task.deletedAt != null && task.parentTaskId == parent.id
        } == true
    }
}.tasks.first { it.id == child.id }
assertNotNull(reBinned.deletedAt)
assertEquals(parent.id, reBinned.parentTaskId)
```

Add the delayed Room test:

```kotlin
@Test
fun restoreUndoRejectsAfterFormerParentIsPurged() = runBlocking {
    openRepository(now = { Instant.parse("2026-08-24T05:00:00Z") })
    repository!!.execute(DomainCommand.CreateTask("Purged restore parent"))
    repository!!.execute(DomainCommand.CreateTask("Purged restore child"))
    val pair = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
        repository!!.observeWorkspace().first { snapshot ->
            snapshot.tasks.any { it.title == "Purged restore parent" } &&
                snapshot.tasks.any { it.title == "Purged restore child" }
        }
    }.tasks.associateBy { it.title }
    val parent = checkNotNull(pair["Purged restore parent"])
    val child = checkNotNull(pair["Purged restore child"])
    repository!!.execute(DomainCommand.SetTaskParent(child.id, parent.id))
    repository!!.execute(DomainCommand.DeleteTask(parent.id))
    val restored = repository!!.execute(DomainCommand.RestoreTask(child.id))
        as CommandResult.Success
    repository!!.execute(DomainCommand.PermanentlyDeleteTask(parent.id))
    val beforeUndo = withTimeout(DEVICE_TEST_TIMEOUT_MILLIS) {
        repository!!.observeWorkspace().first { snapshot ->
            snapshot.tasks.none { it.id == parent.id }
        }
    }.tasks.first { it.id == child.id }

    val rejected = repository!!.execute(requireNotNull(restored.undo))
        as CommandResult.Rejected

    assertEquals(RejectionReason.SUBTASK_PARENT_INVALID, rejected.reason)
    assertEquals(
        beforeUndo,
        repository!!.currentWorkspace().tasks.first { it.id == child.id },
    )
}
```

Compile with:

```bash
./gradlew :core:data:compileDebugAndroidTestKotlin --console=plain
```

Expected: PASS compilation. Observe RED on a safe disposable target if one is
available; otherwise do not run connected tests.

- [ ] **Step 7: Add the minimal restore-Undo metadata and atomic replay**

Extend `DeleteTask` without changing normal callers:

```kotlin
/**
 * [restoreParentTaskId] is repository-produced Undo metadata used only when
 * re-binning a task whose restore safely detached it.
 */
data class DeleteTask(
    val taskId: TaskId,
    val deletedAt: Instant = Instant.now(),
    val restoreParentTaskId: TaskId? = null,
) : DomainCommand
```

In in-memory `deleteTask`, validate before computing or publishing updates:

```kotlin
command.restoreParentTaskId?.let { parentId ->
    SubtaskRules.historicalParentViolation(
        current.tasks,
        task.id,
        parentId,
    )?.let { violation ->
        return CommandResult.Rejected(
            RejectionReason.SUBTASK_PARENT_INVALID,
            subtaskViolationMessage(violation),
        )
    }
}
```

Include the historical link in the same root-row copy:

```kotlin
parentTaskId = command.restoreParentTaskId ?: task.parentTaskId,
```

Room first applies the shared snapshot rule, then rechecks rows that may have
changed before the flow snapshot caught up:

```kotlin
command.restoreParentTaskId?.let { parentId ->
    val snapshotViolation = SubtaskRules.historicalParentViolation(
        mutableWorkspace.value.tasks,
        task.id,
        parentId,
    )
    if (snapshotViolation != null) {
        return CommandResult.Rejected(
            RejectionReason.SUBTASK_PARENT_INVALID,
            subtaskViolationMessage(snapshotViolation),
        )
    }
    val liveParent = database.taskDao().getById(parentId.value)
    val liveViolation = when {
        liveParent == null -> SubtaskViolation.PARENT_MISSING_OR_BINNED
        liveParent.parentTaskId != null -> SubtaskViolation.PARENT_IS_A_SUBTASK
        database.taskDao().liveChildren(task.id.value).isNotEmpty() ->
            SubtaskViolation.TASK_HAS_SUBTASKS
        else -> null
    }
    if (liveViolation != null) {
        return CommandResult.Rejected(
            RejectionReason.SUBTASK_PARENT_INVALID,
            subtaskViolationMessage(liveViolation),
        )
    }
}
```

Add the same `parentTaskId` assignment to Room's `task.copy`. In each
`restoreTask`, replace the plain inverse with:

```kotlin
undo = DomainCommand.DeleteTask(
    taskId = task.id,
    deletedAt = deletedAt,
    restoreParentTaskId = if (detach && parent != null) parentId else null,
),
```

This intentionally omits metadata when the former parent row was already
absent; Undo must never recreate a dangling identity.

- [ ] **Step 8: Run focused GREEN verification**

Run the domain command from Step 2, the host repository command from Step 5,
and:

```bash
./gradlew :core:data:compileDebugAndroidTestKotlin --console=plain
```

Expected: all host tests PASS and Room tests compile. If a safe disposable
target is available, rerun the Room command from Task 1 Step 7 and expect
PASS.

- [ ] **Step 9: Commit the restore-Undo slice**

```bash
git add core/domain/src/main/kotlin/app/opentasks/core/domain/VaultRepository.kt \
  core/domain/src/main/kotlin/app/opentasks/core/domain/SubtaskRules.kt \
  core/domain/src/test/kotlin/app/opentasks/core/domain/SubtaskRulesTest.kt \
  core/data/src/main/kotlin/app/opentasks/core/data/InMemoryVaultRepository.kt \
  core/data/src/main/kotlin/app/opentasks/core/data/RoomVaultRepository.kt \
  core/data/src/test/kotlin/app/opentasks/core/data/InMemoryVaultRepositoryTest.kt \
  core/data/src/androidTest/kotlin/app/opentasks/core/data/RoomVaultRepositoryInstrumentedTest.kt
git diff --cached --check
git commit -m "fix: preserve subtask parent on restore undo"
```

---

### Task 3: Run the full gate and advance the handoff

**Files:**

- Modify: `HANDOFF.md:1-12,731-754`

**Interfaces:**

- Consumes: the two focused implementation commits and their RED-GREEN
  receipts.
- Produces: an authoritative checkpoint that closes the Undo slice and names
  migration friction as the next bounded programme item.

- [ ] **Step 1: Run every host and compilation gate from a clean production diff**

Run these commands separately so each receipt is unambiguous:

```bash
./gradlew :core:domain:testDebugUnitTest --tests "*SubtaskRulesTest" --console=plain
```

```bash
./gradlew :core:data:testDebugUnitTest --tests "*InMemoryVaultRepositoryTest" --console=plain
```

```bash
./gradlew :core:data:compileDebugAndroidTestKotlin --console=plain
```

```bash
./gradlew testDebugUnitTest lintDebug :app:assembleDebug --console=plain
```

Expected: every command exits 0 with no test failure or lint error.

- [ ] **Step 2: Run or explicitly bound the connected Room proof**

Inspect the connected-device list without changing device state:

```bash
/Users/kk/Library/Android/sdk/platform-tools/adb devices -l
```

If an authorized disposable target is the sole selected device, run:

```bash
./gradlew :core:data:connectedDebugAndroidTest --console=plain
```

Expected: PASS. If only the protected emulator is available, do not run it;
the handoff must say that Room tests compiled and that connected execution was
not authorized on the protected target.

- [ ] **Step 3: Review the exact diff and protected workspace state**

Run:

```bash
git diff --check
git diff --stat HEAD~2..HEAD
git status --short
```

Confirm the implementation touches only the files in Tasks 1-2, no schema or
backup fixture changed, and all unrelated user-owned workspace entries remain
unstaged and unmodified by this work.

- [ ] **Step 4: Update the authoritative handoff with literal outcomes**

Prepend a dated current-state paragraph to `HANDOFF.md` that records these
facts in plain language:

```markdown
## Current state — Task 9 Undo gaps closed, 24 August 2026

This section is authoritative. Older checkpoints below are historical and
are superseded wherever they conflict with this one.

The two bounded subtask Undo asymmetries are closed in both repository
engines. Undoing a child-only project move restores its former project,
workflow status, and parent. Undoing a restore that safely detached a child
returns it to the Bin with its former parent link when that parent record is
still representable; delayed invalid Undo rejects before any partial write.

The change is command-only Undo metadata. It adds no UI, database migration,
backup-format change, dependency, permission, or version bump. The focused
domain and in-memory repository tests, Room Android-test compilation, and the
full host gate passed. The connected Room result is recorded separately only
if an authorized disposable target was used.

The next bounded programme item is migration friction. Brainstorm and approve
that design before implementation; do not bundle the version/trust footer or
Play internal beta work into it.
```

Rename the former top heading to
`## Previous checkpoint — release 1.4.0 tagged, pushed, and closed, 24 August 2026`
and remove its now-stale “This section is authoritative” sentence. Replace
backlog item 5 and its resume paragraph with:

```markdown
5. Product polish: ~~restore-detach not undoable / detaching move one-way~~
   **DONE in the current checkpoint**; inert-rule visibility in the
   automations editor; remove render-dead `HomeSnapshot.focusTasks`;
   `parentViolation` defence-in-depth on the candidate's `deletedAt`.

The Task 9 Undo asymmetries are closed in the current checkpoint. Resume with
a bounded design approval for migration friction. Do not bundle the remaining
backlog polish, version/trust footer, or Play internal beta into that slice.
```

Add the actual connected-test sentence selected in Step 2; do not claim a run
that did not happen.

- [ ] **Step 5: Verify and commit only the handoff**

```bash
git diff --check -- HANDOFF.md
git add HANDOFF.md
git diff --cached --check
git commit -m "docs: record undo gap closure"
```

- [ ] **Step 6: Final completion check**

Run:

```bash
git log -3 --oneline
git status --short
```

Report the two implementation commits, the handoff commit, the four host and
compile receipts, the connected Room receipt or explicit safety deferral, and
the preserved user-owned workspace entries. Do not call the wider programme
complete; only the Undo slice is complete.
