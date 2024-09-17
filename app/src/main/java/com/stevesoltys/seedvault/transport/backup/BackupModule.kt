/*
 * SPDX-FileCopyrightText: 2020 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.transport.backup

import org.koin.android.ext.koin.androidContext
import org.koin.dsl.module

val backupModule = module {
    single { BackupInitializer(get()) }
    single { InputFactory() }
    single {
        PackageService(
            context = androidContext(),
            settingsManager = get(),
            backendManager = get(),
        )
    }
    single<KvDbManager> { KvDbManagerImpl(androidContext()) }
    single {
        KVBackup(
            settingsManager = get(),
            backupReceiver = get(),
            inputFactory = get(),
            dbManager = get(),
        )
    }
    single {
        FullBackup(
            settingsManager = get(),
            nm = get(),
            backupReceiver = get(),
            inputFactory = get(),
        )
    }
    single {
        BackupCoordinator(
            context = androidContext(),
            backendManager = get(),
            appBackupManager = get(),
            kv = get(),
            full = get(),
            clock = get(),
            packageService = get(),
            metadataManager = get(),
            settingsManager = get(),
            nm = get(),
        )
    }
}
