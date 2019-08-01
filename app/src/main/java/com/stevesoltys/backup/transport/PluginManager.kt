package com.stevesoltys.backup.transport

import android.content.Context
import com.stevesoltys.backup.Backup
import com.stevesoltys.backup.crypto.CipherFactoryImpl
import com.stevesoltys.backup.crypto.CryptoImpl
import com.stevesoltys.backup.header.HeaderReaderImpl
import com.stevesoltys.backup.header.HeaderWriterImpl
import com.stevesoltys.backup.settings.getBackupFolderUri
import com.stevesoltys.backup.settings.getDeviceName
import com.stevesoltys.backup.transport.backup.BackupCoordinator
import com.stevesoltys.backup.transport.backup.FullBackup
import com.stevesoltys.backup.transport.backup.InputFactory
import com.stevesoltys.backup.transport.backup.KVBackup
import com.stevesoltys.backup.transport.backup.plugins.DocumentsProviderBackupPlugin
import com.stevesoltys.backup.transport.backup.plugins.DocumentsStorage
import com.stevesoltys.backup.transport.restore.FullRestore
import com.stevesoltys.backup.transport.restore.KVRestore
import com.stevesoltys.backup.transport.restore.OutputFactory
import com.stevesoltys.backup.transport.restore.RestoreCoordinator
import com.stevesoltys.backup.transport.restore.plugins.DocumentsProviderRestorePlugin

class PluginManager(context: Context) {

    // We can think about using an injection framework such as Dagger to simplify this.

    private val storage = DocumentsStorage(context, getBackupFolderUri(context), getDeviceName(context)!!)

    private val headerWriter = HeaderWriterImpl()
    private val headerReader = HeaderReaderImpl()
    private val cipherFactory = CipherFactoryImpl(Backup.keyManager)
    private val crypto = CryptoImpl(cipherFactory, headerWriter, headerReader)


    private val backupPlugin = DocumentsProviderBackupPlugin(storage, context.packageManager)
    private val inputFactory = InputFactory()
    private val kvBackup = KVBackup(backupPlugin.kvBackupPlugin, inputFactory, headerWriter, crypto)
    private val fullBackup = FullBackup(backupPlugin.fullBackupPlugin, inputFactory, headerWriter, crypto)

    internal val backupCoordinator = BackupCoordinator(backupPlugin, kvBackup, fullBackup)


    private val restorePlugin = DocumentsProviderRestorePlugin(storage)
    private val outputFactory = OutputFactory()
    private val kvRestore = KVRestore(restorePlugin.kvRestorePlugin, outputFactory, headerReader, crypto)
    private val fullRestore = FullRestore(restorePlugin.fullRestorePlugin, outputFactory, headerReader, crypto)

    internal val restoreCoordinator = RestoreCoordinator(restorePlugin, kvRestore, fullRestore)

}