/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.ui.check

import android.os.Bundle
import android.text.format.Formatter.formatShortFileSize
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.repo.Checker
import com.stevesoltys.seedvault.repo.CheckerResult
import com.stevesoltys.seedvault.restore.RestoreSetAdapter
import com.stevesoltys.seedvault.transport.restore.RestorableBackup
import com.stevesoltys.seedvault.ui.BackupActivity
import com.stevesoltys.seedvault.ui.notification.BackupNotificationManager
import io.github.oshai.kotlinlogging.KotlinLogging
import org.koin.android.ext.android.inject

internal const val ACTION_FINISHED = "FINISHED"
internal const val ACTION_SHOW = "SHOW"

class AppCheckResultActivity : BackupActivity() {

    private val log = KotlinLogging.logger { }

    private val checker: Checker by inject()
    private val notificationManager: BackupNotificationManager by inject()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (savedInstanceState == null) when (intent.action) {
            ACTION_FINISHED -> {
                notificationManager.onCheckCompleteNotificationSeen()
                checker.clear()
                finish()
            }
            ACTION_SHOW -> {
                notificationManager.onCheckCompleteNotificationSeen()
                onActionReceived()
            }
            else -> {
                log.error { "Unknown action: ${intent.action}" }
                finish()
            }
        }
    }

    private fun onActionReceived() {
        when (val result = checker.checkerResult) {
            is CheckerResult.Success -> onSuccess(result)
            is CheckerResult.Error -> {
                // TODO
                log.info { "snapshots: ${result.snapshots.size}, errors: ${result.errors.size}" }
            }
            is CheckerResult.GeneralError, null -> {
                // TODO
                if (result == null) log.error { "No more result" }
                else log.info((result as CheckerResult.GeneralError).e) { "Error: " }
            }
        }
        checker.clear()
    }

    private fun onSuccess(result: CheckerResult.Success) {
        setContentView(R.layout.activity_check_success)
        val intro = getString(
            R.string.backup_app_check_success_intro,
            result.snapshots.size,
            result.percent,
            formatShortFileSize(this, result.size),
        )
        requireViewById<TextView>(R.id.introView).text = intro

        val listView = requireViewById<RecyclerView>(R.id.listView)
        listView.adapter = RestoreSetAdapter(
            listener = null,
            items = result.snapshots.map { snapshot ->
                RestorableBackup("", snapshot)
            }.sortedByDescending { it.time },
        )
    }

}
