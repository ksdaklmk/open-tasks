package app.opentasks.myday

import app.opentasks.core.domain.DomainCommand
import app.opentasks.core.domain.VaultRepository
import app.opentasks.core.domain.myDaySweepEnabled
import java.time.LocalDate
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Dispatches the idempotent My Day rollover sweep when the MY_DAY_AUTO_REMOVE
 * rule is enabled. Silent by design: the message and undo are dropped, exactly
 * as FocusCoordinator's dispatches are.
 *
 * The repository is a [Provider], never a `Lazy`, for the reason
 * `DailyDigestCoordinator` documents: the Hilt binding for [VaultRepository]
 * is deliberately unscoped so every resolution hands back the currently active
 * vault runtime, and a singleton-held `Lazy` would cache the first vault
 * across an in-process slot replacement. Resolution throws while no runtime is
 * active — a locked device, a vault awaiting recovery — and the sweep then
 * skips that one foregrounding rather than failing: it runs on every `onStart`
 * and removing already-removed entries is a no-op, so nothing is lost by
 * waiting for the next one.
 */
@Singleton
class MyDaySweeper @Inject constructor(
    private val repository: Provider<VaultRepository>,
) {
    suspend fun sweep() {
        val vault = runCatching { repository.get() }.getOrNull() ?: return
        if (!myDaySweepEnabled(vault.currentWorkspace().automationRules)) return
        val zone = ZoneId.systemDefault()
        val before = LocalDate.now(zone).atStartOfDay(zone).toInstant()
        vault.execute(DomainCommand.SweepMyDay(before))
    }
}
