package app.opentasks

import android.app.Application
import android.app.LocaleManager
import android.os.LocaleList
import app.opentasks.reminders.ReminderNotifications
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class OpenTasksApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        val localeManager = getSystemService(LocaleManager::class.java)
        if (localeManager.applicationLocales.toLanguageTags() != UK_ENGLISH_LANGUAGE_TAG) {
            localeManager.applicationLocales =
                LocaleList.forLanguageTags(UK_ENGLISH_LANGUAGE_TAG)
        }
        ReminderNotifications.createChannel(this)
    }

    private companion object {
        const val UK_ENGLISH_LANGUAGE_TAG = "en-GB"
    }
}
