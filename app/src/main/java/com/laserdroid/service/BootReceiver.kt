package com.laserdroid.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.laserdroid.MainActivity

/**
 * Starts LaserDroid when the phone boots (option in SETTINGS).
 *
 * The foreground service may always be started from BOOT_COMPLETED.
 * Since Android 10, opening the screen from the background needs
 * the "Display over other apps" permission, or LaserDroid set as
 * the home app (kiosk), which starts it anyway.
 *
 * Never connects or starts a job: the user still presses Connect.
 */
class BootReceiver : BroadcastReceiver() {

    companion object {
        private const val PREFERENCES = "laserdroid_boot"
        private const val KEY_START_ON_BOOT = "start_on_boot"

        fun setStartOnBoot(context: Context, enabled: Boolean) {
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .edit()
                .putBoolean(KEY_START_ON_BOOT, enabled)
                .apply()
        }

        private fun isStartOnBoot(context: Context): Boolean =
            context.getSharedPreferences(PREFERENCES, Context.MODE_PRIVATE)
                .getBoolean(KEY_START_ON_BOOT, false)
    }

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != "android.intent.action.QUICKBOOT_POWERON"
        ) {
            return
        }
        if (!isStartOnBoot(context)) return

        runCatching {
            ContextCompat.startForegroundService(
                context,
                Intent(context, MachineService::class.java)
            )
        }

        runCatching {
            context.startActivity(
                Intent(context, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
        }
    }
}
