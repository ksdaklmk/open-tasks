package app.opentasks

import android.app.Application
import android.app.LocaleManager
import android.os.LocaleList
import app.opentasks.backup.AndroidBackupRuntime
import app.opentasks.reminders.ReminderNotifications
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

@HiltAndroidApp
class OpenTasksApplication : Application() {
    @Inject
    lateinit var androidBackupRuntime: AndroidBackupRuntime

    override fun onCreate() {
        super.onCreate()
        val localeManager = getSystemService(LocaleManager::class.java)
        if (localeManager.applicationLocales.toLanguageTags() != UK_ENGLISH_LANGUAGE_TAG) {
            localeManager.applicationLocales =
                LocaleList.forLanguageTags(UK_ENGLISH_LANGUAGE_TAG)
        }
        ReminderNotifications.createChannel(this)
        androidBackupRuntime.start()
    }

    private companion object {
        const val UK_ENGLISH_LANGUAGE_TAG = "en-GB"
    }
}
