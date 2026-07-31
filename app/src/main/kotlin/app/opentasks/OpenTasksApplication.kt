package app.opentasks

import android.app.Application
import android.app.LocaleManager
import android.os.LocaleList
import app.opentasks.core.data.DefaultVaultRuntimeManager
import app.opentasks.reminders.ReminderNotifications
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

@HiltAndroidApp
class OpenTasksApplication : Application() {
    @Inject
    lateinit var vaultRuntimeManager: DefaultVaultRuntimeManager

    @Inject
    lateinit var activeVaultServices: ActiveVaultServices

    private val applicationScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        val localeManager = getSystemService(LocaleManager::class.java)
        if (localeManager.applicationLocales.toLanguageTags() != UK_ENGLISH_LANGUAGE_TAG) {
            localeManager.applicationLocales =
                LocaleList.forLanguageTags(UK_ENGLISH_LANGUAGE_TAG)
        }
        ReminderNotifications.createChannel(this)
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
