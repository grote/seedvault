package com.stevesoltys.seedvault

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log

private val TAG = BackupIntentReceiver::class.java.simpleName

private const val BACKUP_FINISHED_ACTION = "android.intent.action.BACKUP_FINISHED"
private const val BACKUP_FINISHED_PACKAGE_EXTRA = "packageName"

class BackupIntentReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
//        val action = intent.action ?: return

        Log.e(TAG, "INTENT: ${intent.action} $intent")

        if (intent.action == BACKUP_FINISHED_ACTION) {
            Log.e(TAG, "package: ${intent.extras?.getString(BACKUP_FINISHED_PACKAGE_EXTRA)}")
        }
    }
}
