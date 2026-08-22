package app.opentasks.lock

import android.app.PendingIntent
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppLockExpiryInstrumentedTest {
    private val context: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun appLockExpiryReceiverIsNotExportedAndHasNoIntentFilter() {
        val component = ComponentName(context, AppLockExpiryReceiver::class.java)

        val receiver = context.packageManager.getReceiverInfo(component, 0)

        assertFalse(receiver.exported)
        val implicit = Intent(AppLockExpiryIntents.ACTION_DELIVER)
            .setData(AppLockExpiryIntents.deliveryData())
        val matches = context.packageManager.queryBroadcastReceivers(
            implicit,
            PackageManager.ResolveInfoFlags.of(0L),
        )
        assertTrue(matches.none { it.activityInfo.name == component.className })
    }

    @Test
    fun canonicalDeliveryUsesOneExplicitImmutablePayloadFreeIdentity() {
        val firstIntent = AppLockExpiryIntents.deliveryIntent(context)
        val secondIntent = AppLockExpiryIntents.deliveryIntent(context)

        assertEquals(AppLockExpiryIntents.ACTION_DELIVER, firstIntent.action)
        assertEquals(AppLockExpiryIntents.deliveryData(), firstIntent.data)
        assertEquals(AppLockExpiryReceiver::class.java.name, firstIntent.component?.className)
        assertEquals(context.packageName, firstIntent.component?.packageName)
        assertNull(firstIntent.extras)
        assertTrue(firstIntent.filterEquals(secondIntent))

        val first = PendingIntent.getBroadcast(
            context,
            0,
            firstIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val second = PendingIntent.getBroadcast(
            context,
            0,
            secondIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        assertEquals(first, second)
        assertTrue(first.isImmutable)
    }

    @Test
    fun deliveryValidationRejectsWrongActionAndData() {
        assertTrue(isAppLockExpiryDelivery(AppLockExpiryIntents.deliveryIntent(context)))
        assertFalse(
            isAppLockExpiryDelivery(
                AppLockExpiryIntents.deliveryIntent(context).setAction("wrong"),
            ),
        )
        assertFalse(
            isAppLockExpiryDelivery(
                AppLockExpiryIntents.deliveryIntent(context).setData(null),
            ),
        )
    }
}
