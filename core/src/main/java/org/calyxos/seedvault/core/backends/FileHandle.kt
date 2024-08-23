/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.seedvault.core.backends

import org.calyxos.seedvault.core.backends.Constants.FILE_BACKUP_ICONS
import org.calyxos.seedvault.core.backends.Constants.FILE_BACKUP_METADATA
import org.calyxos.seedvault.core.backends.Constants.SNAPSHOT_EXT

public sealed class FileHandle {
    public abstract val name: String

    /**
     * The relative path relative to the storage root without prepended or trailing slash (/).
     */
    public abstract val relativePath: String
}

public data class TopLevelFolder(override val name: String) : FileHandle() {
    override val relativePath: String = name
}

public sealed class LegacyAppBackupFile : FileHandle() {
    public abstract val token: Long
    public val topLevelFolder: TopLevelFolder get() = TopLevelFolder(token.toString())
    override val relativePath: String get() = "$token/$name"

    public data class Metadata(override val token: Long) : LegacyAppBackupFile() {
        override val name: String = FILE_BACKUP_METADATA
    }

    public data class IconsFile(override val token: Long) : LegacyAppBackupFile() {
        override val name: String = FILE_BACKUP_ICONS
    }

    public data class Blob(
        override val token: Long,
        override val name: String,
    ) : LegacyAppBackupFile()
}

public sealed class FileBackupFileType : FileHandle() {
    public abstract val androidId: String
    public val topLevelFolder: TopLevelFolder get() = TopLevelFolder("$androidId.sv")

    public data class Blob(
        override val androidId: String,
        override val name: String,
    ) : FileBackupFileType() {
        override val relativePath: String get() = "$androidId.sv/${name.substring(0, 2)}/$name"
    }

    public data class Snapshot(
        override val androidId: String,
        val time: Long,
    ) : FileBackupFileType() {
        override val name: String = "$time$SNAPSHOT_EXT"
        override val relativePath: String get() = "$androidId.sv/$name"
    }
}

public data class FileInfo(
    val fileHandle: FileHandle,
    val size: Long,
)
