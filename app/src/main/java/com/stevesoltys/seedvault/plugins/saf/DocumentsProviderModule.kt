package com.stevesoltys.seedvault.plugins.saf

import com.stevesoltys.seedvault.transport.backup.BackupPlugin
import com.stevesoltys.seedvault.transport.restore.FullRestorePlugin
import com.stevesoltys.seedvault.transport.restore.KVRestorePlugin
import com.stevesoltys.seedvault.transport.restore.RestorePlugin
import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val documentsProviderModule = module {
    single { DocumentsStorage(androidContext(), get()) }

    single<BackupPlugin> { DocumentsProviderBackupPlugin(androidContext(), get()) }

    single<KVRestorePlugin> { DocumentsProviderKVRestorePlugin(androidContext(), get()) }
    single<FullRestorePlugin> { DocumentsProviderFullRestorePlugin(androidContext(), get()) }
    single<RestorePlugin> { DocumentsProviderRestorePlugin(androidContext(), get(), get(), get()) }
}
