package com.stevesoltys.seedvault.plugins.saf

import android.content.Context
import android.content.pm.PackageInfo
import androidx.documentfile.provider.DocumentFile
import com.stevesoltys.seedvault.transport.restore.KVRestorePlugin
import java.io.IOException
import java.io.InputStream

@Suppress("BlockingMethodInNonBlockingContext", "Deprecation")
@Deprecated("Use only for v0 restore")
internal class DocumentsProviderKVRestorePlugin(
    private val context: Context,
    private val storage: DocumentsStorage
) : KVRestorePlugin {

    private var packageDir: DocumentFile? = null
    private var packageChildren: List<DocumentFile>? = null

    override suspend fun hasDataForPackage(token: Long, packageInfo: PackageInfo): Boolean {
        return try {
            val backupDir = storage.getKVBackupDir(token) ?: return false
            val dir = backupDir.findFileBlocking(context, packageInfo.packageName) ?: return false
            val children = dir.listFilesBlocking(context)
            // remember package file for subsequent operations
            packageDir = dir
            // remember package children for subsequent operations
            packageChildren = children
            // we have data if we have a non-empty list of children
            children.isNotEmpty()
        } catch (e: IOException) {
            false
        }
    }

    @Throws(IOException::class)
    override suspend fun listRecords(token: Long, packageInfo: PackageInfo): List<String> {
        val packageDir = this.packageDir
            ?: throw AssertionError("No cached packageDir for ${packageInfo.packageName}")
        packageDir.assertRightFile(packageInfo)
        return packageChildren
            ?.filter { file -> file.name != null }
            ?.map { file -> file.name!! }
            ?: throw AssertionError("No cached children for ${packageInfo.packageName}")
    }

    @Throws(IOException::class)
    override suspend fun getInputStreamForRecord(
        token: Long,
        packageInfo: PackageInfo,
        key: String
    ): InputStream {
        val packageDir = this.packageDir
            ?: throw AssertionError("No cached packageDir for ${packageInfo.packageName}")
        packageDir.assertRightFile(packageInfo)
        val keyFile = packageChildren?.find { it.name == key }
            ?: packageDir.findFileBlocking(context, key)
            ?: throw IOException()
        return storage.getInputStream(keyFile)
    }

}
