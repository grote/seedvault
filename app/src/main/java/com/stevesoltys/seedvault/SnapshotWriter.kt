/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault

import android.os.Build.VERSION.SDK_INT
import android.provider.Settings.Secure.ANDROID_ID
import android.util.Log
import com.google.protobuf.ByteString
import com.stevesoltys.seedvault.metadata.BackupMetadata
import com.stevesoltys.seedvault.metadata.BackupType
import com.stevesoltys.seedvault.proto.Snapshot
import com.stevesoltys.seedvault.proto.Snapshot.BackupType.FULL
import com.stevesoltys.seedvault.proto.Snapshot.BackupType.KV
import com.stevesoltys.seedvault.proto.SnapshotKt.apk
import com.stevesoltys.seedvault.proto.SnapshotKt.app
import com.stevesoltys.seedvault.proto.SnapshotKt.blob
import com.stevesoltys.seedvault.proto.SnapshotKt.split
import com.stevesoltys.seedvault.proto.snapshot
import java.util.Base64
import kotlin.random.Random

object SnapshotWriter {

    fun createSnapshot(metadata: BackupMetadata): Snapshot = snapshot {
        token = metadata.token
        name = metadata.deviceName
        androidId = ANDROID_ID
        sdkInt = SDK_INT
        androidIncremental = metadata.androidIncremental
        d2D = metadata.d2dBackup
        Log.e("TEST", "numApps: ${metadata.packageMetadataMap.size}")
        val chunkId = ByteArray(32)
        var appChunks = 0
        var apkChunks = 0
        val chunkIdsList = buildList<ByteString> {
            for (i in 0..8000) add(ByteString.copyFrom(Random.nextBytes(32)))
        }
        val chunkIdsIterator = chunkIdsList.iterator()
        metadata.packageMetadataMap.forEach { (packageName, m) ->
            apps[packageName] = app {
                time = m.time
                state = m.state.name
                type = if (m.backupType == BackupType.KV) KV else FULL
                name = m.name?.toString() ?: ""
                system = m.system
                launchableSystemApp = m.isLaunchableSystemApp
                chunkIds += listOf(
                    ByteString.copyFrom(chunkId),
                    chunkIdsIterator.next(),
                    chunkIdsIterator.next(),
                    chunkIdsIterator.next(),
                    chunkIdsIterator.next(),
                    chunkIdsIterator.next(),
                    chunkIdsIterator.next(),
                    chunkIdsIterator.next(),
                    chunkIdsIterator.next(),
                    chunkIdsIterator.next(),
                    chunkIdsIterator.next(),
                    chunkIdsIterator.next(),
                    chunkIdsIterator.next(),
                    chunkIdsIterator.next(),
                    chunkIdsIterator.next(),
                ).also { appChunks += it.size }
                apk = apk {
                    versionCode = m.version ?: 0
                    installer = m.installer ?: ""
                    signatures += m.signatures?.map {
                        ByteString.copyFrom(Base64.getDecoder().decode(it))
                    } ?: emptyList()
                    splits += m.splits?.map {
                        split {
                            name = it.name
                            chunkIds += listOf(
                                chunkIdsIterator.next(),
                                chunkIdsIterator.next(),
                                chunkIdsIterator.next(),
                                chunkIdsIterator.next(),
                                chunkIdsIterator.next(),
                            ).also { apkChunks += it.size }
                        }
                    } ?: emptyList()
                }
            }
        }.also {
            Log.e("TEST", "appChunks: $appChunks")
            Log.e("TEST", "apkChunks: $apkChunks")
        }
        iconChunkIds += listOf(
            chunkIdsIterator.next(),
            chunkIdsIterator.next(),
            chunkIdsIterator.next(),
            chunkIdsIterator.next(),
            chunkIdsIterator.next(),
            chunkIdsIterator.next(),
            chunkIdsIterator.next(),
        )
        val chunkIdsIterator2 = chunkIdsList.iterator()
        for (i in 0..4000) blobs[chunkIdsIterator2.next().toByteArray().toHex()] = blob {
            id = ByteString.copyFrom(Random.nextBytes(32))
            length = Random.nextInt(1, Int.MAX_VALUE)
            uncompressedLength = Random.nextInt(1, Int.MAX_VALUE)
        }
        blobs[chunkId.toHex()] = blob {
            id = ByteString.copyFrom(Random.nextBytes(32))
            length = Random.nextInt(1, Int.MAX_VALUE)
            uncompressedLength = Random.nextInt(1, Int.MAX_VALUE)
        }
    }
}

fun ByteArray.toHex(): String = joinToString(separator = "") { eachByte ->
    "%02x".format(eachByte)
}
