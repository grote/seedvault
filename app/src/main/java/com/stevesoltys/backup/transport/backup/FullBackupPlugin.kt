package com.stevesoltys.backup.transport.backup

import android.content.pm.PackageInfo
import java.io.IOException
import java.io.OutputStream

interface FullBackupPlugin {

    fun getQuota(): Long

    // TODO consider using a salted hash for the package name to not leak it to the storage server
    @Throws(IOException::class)
    fun getOutputStream(targetPackage: PackageInfo): OutputStream

    @Throws(IOException::class)
    fun cancelFullBackup(targetPackage: PackageInfo)

}