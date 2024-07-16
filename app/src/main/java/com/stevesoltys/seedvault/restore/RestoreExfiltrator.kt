/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.restore

import android.content.Context
import android.util.Log
import com.stevesoltys.seedvault.crypto.Crypto
import com.stevesoltys.seedvault.header.HeaderReader
import com.stevesoltys.seedvault.header.VERSION
import com.stevesoltys.seedvault.header.getADForFull
import com.stevesoltys.seedvault.header.getADForKV
import com.stevesoltys.seedvault.metadata.BackupType
import com.stevesoltys.seedvault.metadata.PackageMetadata
import com.stevesoltys.seedvault.plugins.StoragePluginManager
import org.koin.core.component.KoinComponent
import org.koin.core.component.inject
import java.io.File
import java.io.FileNotFoundException
import java.io.OutputStream
import java.util.zip.GZIPInputStream

class RestoreExfiltrator(
    private val context: Context,
    private val storagePluginManager: StoragePluginManager,
) : KoinComponent {

    private val headerReader: HeaderReader by inject()
    private val crypto: Crypto by inject()
    private val plugin get() = storagePluginManager.appPlugin

    suspend fun ex(b: RestorableBackup) {
        b.packageMetadataMap.forEach { (packageName, metadata) ->
            try {
                when (metadata.backupType) {
                    BackupType.FULL -> exFull(b.token, b.version, b.salt, packageName, metadata)
                    BackupType.KV -> exKv(b.token, b.version, b.salt, packageName, metadata)
                    else -> Log.e("TEST", "Ignoring $packageName was ${metadata.state}")
                }
            } catch (e: Exception) {
                if (e is FileNotFoundException) {
                    Log.e("TEST", "$packageName had no backup $metadata")
                } else {
                    Log.e("TEST", "ERROR restoring $packageName $metadata", e)
                }
            }
        }
        Log.e("TEST", "DONE!!!")
    }

    private suspend fun exKv(
        token: Long,
        version: Byte,
        salt: String,
        packageName: String,
        metadata: PackageMetadata,
    ) {
        val name = crypto.getNameForPackage(salt, packageName)
        plugin.getInputStream(token, name).use { inputStream ->
            headerReader.readVersion(inputStream, version)
            val ad = getADForKV(VERSION, packageName)
            crypto.newDecryptingStream(inputStream, ad).use { decryptedStream ->
                GZIPInputStream(decryptedStream).use { gzipStream ->
                    getOutputStream(packageName).use { outputStream ->
                        gzipStream.copyTo(outputStream)
                    }
                }
            }
        }
        if (metadata.hasApk()) exApks(token, salt, packageName, metadata)
    }

    private suspend fun exFull(
        token: Long,
        version: Byte,
        salt: String,
        packageName: String,
        metadata: PackageMetadata,
    ) {
        val name = crypto.getNameForPackage(salt, packageName)
        val inputStream = plugin.getInputStream(token, name)
        val v = headerReader.readVersion(inputStream, version)
        val ad = getADForFull(v, packageName)
        crypto.newDecryptingStream(inputStream, ad).use {
            getOutputStream(packageName).use { outputStream ->
                it.copyTo(outputStream)
            }
        }
        if (metadata.hasApk()) exApks(token, salt, packageName, metadata)
    }

    private suspend fun exApks(
        token: Long,
        salt: String,
        packageName: String,
        metadata: PackageMetadata,
    ) {
        val apkName = crypto.getNameForApk(salt, packageName, "")
        val version = metadata.version?.toString() ?: error("no version")
        plugin.getInputStream(token, apkName).use {
            getOutputStream("apk-$packageName", version).use { outputStream ->
                it.copyTo(outputStream)
            }
        }
        metadata.splits?.forEach { split ->
            val splitName = crypto.getNameForApk(salt, packageName, split.name)
            plugin.getInputStream(token, splitName).use {
                getOutputStream("apk-$packageName-${split.name}", version).use { outputStream ->
                    it.copyTo(outputStream)
                }
            }
        }
    }

    private fun getOutputStream(
        outputName: String,
        suffix: String = this.toString().split('@')[1],
    ): OutputStream {
        val name = "$outputName-$suffix"
        val dir = File("/sdcard/.SeedVaultAndroidBackup/1/")
        dir.mkdirs()
        return File(dir, name).outputStream()
    }

}
