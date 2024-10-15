/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.repo

import androidx.annotation.WorkerThread
import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.crypto.Crypto
import org.calyxos.seedvault.core.backends.AppBackupFileType
import org.calyxos.seedvault.core.backends.TopLevelFolder

@WorkerThread
internal class Checker(
    private val crypto: Crypto,
    private val backendManager: BackendManager,
    private val snapshotManager: SnapshotManager,
) {

    suspend fun getBackupSize(): Long {
        // get all snapshots
        val folder = TopLevelFolder(crypto.repoId)
        val handles = mutableListOf<AppBackupFileType.Snapshot>()
        backendManager.backend.list(folder, AppBackupFileType.Snapshot::class) { fileInfo ->
            handles.add(fileInfo.fileHandle as AppBackupFileType.Snapshot)
        }
        val snapshots = snapshotManager.onSnapshotsLoaded(handles)

        // get total disk space used by snapshots
        val sizeMap = mutableMapOf<String, Int>()
        snapshots.forEach { snapshot ->
            // add sizes to a map first, so we don't double count
            snapshot.blobsMap.forEach { (chunkId, blob) -> sizeMap[chunkId] = blob.length }
        }
        return sizeMap.values.sumOf { it.toLong() }
    }

}
