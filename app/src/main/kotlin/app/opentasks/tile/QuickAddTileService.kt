package app.opentasks.tile

import android.app.PendingIntent
import android.content.Intent
import android.service.quicksettings.TileService
import app.opentasks.MainActivity

class QuickAddTileService : TileService() {

    override fun onClick() {
        val intent = Intent(this, MainActivity::class.java)
            .setAction(MainActivity.QUICK_ADD_ACTION)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        startActivityAndCollapse(
            PendingIntent.getActivity(
                this,
                0,
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            ),
        )
    }
}
