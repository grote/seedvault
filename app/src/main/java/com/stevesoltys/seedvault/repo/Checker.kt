/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.repo

import androidx.annotation.WorkerThread
import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.crypto.Crypto
import com.stevesoltys.seedvault.proto.Snapshot
import com.stevesoltys.seedvault.proto.Snapshot.Blob
import io.github.oshai.kotlinlogging.KotlinLogging
import org.calyxos.seedvault.core.backends.AppBackupFileType
import org.calyxos.seedvault.core.backends.TopLevelFolder
import org.calyxos.seedvault.core.toHexString
import org.koin.core.time.measureTimedValue
import java.security.DigestInputStream
import java.security.GeneralSecurityException
import java.security.MessageDigest
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.time.Duration.Companion.milliseconds

@WorkerThread
internal class Checker(
    private val crypto: Crypto,
    private val backendManager: BackendManager,
    private val snapshotManager: SnapshotManager,
    private val loader: Loader,
) {
    private val log = KotlinLogging.logger { }

    private var snapshots: List<Snapshot>? = null

    suspend fun getBackupSize(): Long {
        // get all snapshots
        val folder = TopLevelFolder(crypto.repoId)
        val handles = mutableListOf<AppBackupFileType.Snapshot>()
        backendManager.backend.list(folder, AppBackupFileType.Snapshot::class) { fileInfo ->
            handles.add(fileInfo.fileHandle as AppBackupFileType.Snapshot)
        }
        val snapshots = snapshotManager.onSnapshotsLoaded(handles)
        this.snapshots = snapshots // remember loaded snapshots

        // get total disk space used by snapshots
        val sizeMap = mutableMapOf<String, Int>()
        snapshots.forEach { snapshot ->
            // add sizes to a map first, so we don't double count
            snapshot.blobsMap.forEach { (chunkId, blob) -> sizeMap[chunkId] = blob.length }
        }
        return sizeMap.values.sumOf { it.toLong() }
    }

    suspend fun check(percent: Int) {
        check(percent in 0..100) { "Percent $percent out of bounds." }

        if (snapshots == null) getBackupSize() // just get size again to be sure we get snapshots
        val snapshots = snapshots ?: error("Snapshots still null")

        val messageDigest = MessageDigest.getInstance("SHA-256")
        val (checkedSize, time) = measureTimedValue {
            checkBlobs(snapshots, percent) { chunkId, blob ->
                val storageId = blob.id.hexFromProto()
                log.info { "Checking blob $storageId..." }
                val handle = AppBackupFileType.Blob(crypto.repoId, storageId)
                loader.loadFile(handle, null).close()
                val readChunkId = loader.loadFile(handle, null).use { inputStream ->
                    DigestInputStream(inputStream, messageDigest).use { digestStream ->
                        digestStream.readAllBytes()
                        digestStream.messageDigest.digest().toHexString()
                    }
                }
                if (readChunkId != chunkId) throw GeneralSecurityException("ChunkId doesn't match")
            }
        }
        val bandwidth = (checkedSize / 1024 / (time / 1000)).roundToInt()
        log.info { "Took ${time.milliseconds} for $checkedSize bytes, $bandwidth KB/s" }
    }

    private suspend fun checkBlobs(
        snapshots: List<Snapshot>,
        percent: Int,
        blobChecker: suspend (String, Blob) -> Unit,
    ): Long {
        // split up blobs for app data and for APKs
        val appBlobs = mutableMapOf<String, Blob>()
        val apkBlobs = mutableMapOf<String, Blob>()
        snapshots.forEach { snapshot ->
            val appChunkIds = snapshot.appsMap.flatMap { it.value.chunkIdsList.hexFromProto() }
            val apkChunkIds = snapshot.appsMap.flatMap {
                it.value.apk.splitsList.flatMap { split -> split.chunkIdsList.hexFromProto() }
            }
            appChunkIds.forEach { chunkId ->
                appBlobs[chunkId] = snapshot.blobsMap[chunkId] ?: error("No Blob for chunkId")
            }
            apkChunkIds.forEach { chunkId ->
                apkBlobs[chunkId] = snapshot.blobsMap[chunkId] ?: error("No Blob for chunkId")
            }
        }
        // calculate sizes
        val appSize = appBlobs.values.sumOf { it.length.toLong() }
        val apkSize = apkBlobs.values.sumOf { it.length.toLong() }
        // let's assume it is unlikely that app data and APKs have blobs in common
        val totalSize = appSize + apkSize
        log.info { "Got ${appBlobs.size + apkBlobs.size} blobs worth $totalSize bytes to check." }

        // calculate target sizes (how much do we want to check)
        val targetSize = (totalSize * (percent.toDouble() / 100)).roundToLong()
        val appTargetSize = min((targetSize * 0.75).roundToLong(), appSize) // 75% of targetSize
        log.info { "Sampling $targetSize bytes of which $appTargetSize bytes for apps." }

        var currentSize = 0L
        // check apps first until we reach their target size
        val appIterator = appBlobs.keys.shuffled().iterator() // random app blob iterator
        while (currentSize < appTargetSize && appIterator.hasNext()) {
            val randomChunkId = appIterator.next()
            val blob = appBlobs[randomChunkId] ?: error("No blob")
            blobChecker(randomChunkId, blob)
            currentSize += blob.length
            // TODO do progress reporting via system notification instead
            log.info { "  ${((currentSize.toDouble() / targetSize) * 100).roundToInt()}%" }
        }
        // now check APKs until we reach total targetSize
        val apkIterator = apkBlobs.keys.shuffled().iterator() // random APK blob iterator
        while (currentSize < targetSize && apkIterator.hasNext()) {
            val randomChunkId = apkIterator.next()
            val blob = apkBlobs[randomChunkId] ?: error("No blob")
            blobChecker(randomChunkId, blob)
            currentSize += blob.length
            log.info { "  ${((currentSize.toDouble() / targetSize) * 100).roundToInt()}%" }
        }
        return currentSize
    }
}
