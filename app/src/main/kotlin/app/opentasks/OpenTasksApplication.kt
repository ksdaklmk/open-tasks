package app.opentasks

import android.app.Application
import android.app.LocaleManager
import android.os.LocaleList
import androidx.work.Configuration
import app.opentasks.backup.RemoteBackupWorkerFactory
import app.opentasks.core.data.DefaultVaultRuntimeManager
import app.opentasks.digest.DailyDigestNotifications
import app.opentasks.reminders.ReminderNotifications
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class OpenTasksApplication : Application(), Configuration.Provider {
    @Inject
    lateinit var vaultRuntimeManager: DefaultVaultRuntimeManager

    @Inject
    lateinit var activeVaultServices: ActiveVaultServices

    @Inject
    lateinit var remoteBackupWorkerFactory: RemoteBackupWorkerFactory

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /**
     * WorkManager initializes on demand from here rather than at startup, so a
     * remote-backup worker is always constructed with the runner of the vault
     * that is open when it runs. The manifest removes WorkManager's default
     * startup initializer for exactly that reason.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(remoteBackupWorkerFactory)
            .build()

    override fun onCreate() {
        super.onCreate()
        val localeManager = getSystemService(LocaleManager::class.java)
        if (localeManager.applicationLocales.toLanguageTags() != UK_ENGLISH_LANGUAGE_TAG) {
            localeManager.applicationLocales =
                LocaleList.forLanguageTags(UK_ENGLISH_LANGUAGE_TAG)
        }
        ReminderNotifications.createChannel(this)
        // A separate channel from reminders, and created here for the same
        // reason: a channel must exist before anything can post to it, and
        // neither one needs a vault runtime to be declared.
        DailyDigestNotifications.createChannel(this)
        // Android backup services exist only while a vault runtime is active,
        // and are closed again before the runtime's slot can be replaced.
        vaultRuntimeManager.setActiveServiceQuiescer(activeVaultServices::quiesce)
        applicationScope.launch {
            vaultRuntimeManager.state.collect(activeVaultServices::onVaultRuntimeState)
        }
        applicationScope.launch {
            vaultRuntimeManager.initialize()
        }
    }

    private companion object {
        const val UK_ENGLISH_LANGUAGE_TAG = "en-GB"
    }
}
