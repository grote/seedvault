/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package org.calyxos.seedvault.core.backends.saf

import android.content.Context
import android.provider.DocumentsContract.renameDocument
import androidx.documentfile.provider.DocumentFile
import io.github.oshai.kotlinlogging.KotlinLogging
import okio.BufferedSink
import okio.BufferedSource
import okio.buffer
import okio.sink
import okio.source
import org.calyxos.seedvault.core.backends.Backend
import org.calyxos.seedvault.core.backends.Constants.DIRECTORY_ROOT
import org.calyxos.seedvault.core.backends.Constants.FILE_BACKUP_METADATA
import org.calyxos.seedvault.core.backends.Constants.chunkFolderRegex
import org.calyxos.seedvault.core.backends.Constants.chunkRegex
import org.calyxos.seedvault.core.backends.Constants.folderRegex
import org.calyxos.seedvault.core.backends.Constants.snapshotRegex
import org.calyxos.seedvault.core.backends.Constants.tokenRegex
import org.calyxos.seedvault.core.backends.FileBackupFileType
import org.calyxos.seedvault.core.backends.FileHandle
import org.calyxos.seedvault.core.backends.FileInfo
import org.calyxos.seedvault.core.backends.LegacyAppBackupFile
import org.calyxos.seedvault.core.backends.TopLevelFolder
import org.calyxos.seedvault.core.getBackendContext
import java.io.IOException
import kotlin.reflect.KClass

public class SafBackend(
    private val appContext: Context,
    private val safConfig: SafConfig,
    root: String = DIRECTORY_ROOT,
) : Backend {

    private val log = KotlinLogging.logger {}

    /**
     * Attention: This context might be from a different user. Use with care.
     */
    private val context: Context get() = appContext.getBackendContext { safConfig.isUsb }
    private val cache = DocumentFileCache(context, safConfig.getDocumentFile(context), root)

    override suspend fun save(handle: FileHandle): BufferedSink {
        val file = cache.getFile(handle)
        return file.getOutputStream(context.contentResolver).sink().buffer()
    }

    override suspend fun load(handle: FileHandle): BufferedSource {
        val file = cache.getFile(handle)
        return file.getInputStream(context.contentResolver).source().buffer()
    }

    override suspend fun list(
        topLevelFolder: TopLevelFolder?,
        vararg fileTypes: KClass<out FileHandle>,
        callback: (FileInfo) -> Unit,
    ) {
        if (TopLevelFolder::class in fileTypes) throw UnsupportedOperationException()
        if (LegacyAppBackupFile::class in fileTypes) throw UnsupportedOperationException()
        if (LegacyAppBackupFile.IconsFile::class in fileTypes) throw UnsupportedOperationException()
        if (LegacyAppBackupFile.Blob::class in fileTypes) throw UnsupportedOperationException()

        val folder = if (topLevelFolder == null) {
            cache.getRootFile()
        } else {
            cache.getFile(topLevelFolder)
        }
        // limit depth based on wanted types and if top-level folder is given
        var depth = if (FileBackupFileType.Blob::class in fileTypes) 3 else 2
        if (topLevelFolder != null) depth -= 1

        folder.listFilesRecursive(depth) { file ->
            log.error { "${file.uri}" }

            if (!file.isFile) return@listFilesRecursive
            val parentName = file.parentFile?.name ?: return@listFilesRecursive
            val name = file.name ?: return@listFilesRecursive
            if (LegacyAppBackupFile.Metadata::class in fileTypes && name == FILE_BACKUP_METADATA &&
                parentName.matches(tokenRegex)
            ) {
                val metadata = LegacyAppBackupFile.Metadata(parentName.toLong())
                callback(FileInfo(metadata, file.length()))
            }
            if (FileBackupFileType.Snapshot::class in fileTypes ||
                FileBackupFileType::class in fileTypes
            ) {
                val match = snapshotRegex.matchEntire(name)
                log.error { "$parentName/$name ${match?.groupValues}" }
                if (match != null) {
                    val snapshot = FileBackupFileType.Snapshot(
                        androidId = parentName.substringBefore('.'),
                        time = match.groupValues[1].toLong(),
                    )
                    callback(FileInfo(snapshot, file.length()))
                }
            }
            if ((FileBackupFileType.Blob::class in fileTypes ||
                    FileBackupFileType::class in fileTypes)
            ) {
                val androidIdSv = file.parentFile?.parentFile?.name ?: ""
                if (folderRegex.matches(androidIdSv) && chunkFolderRegex.matches(parentName)) {
                    if (chunkRegex.matches(name)) {
                        val blob = FileBackupFileType.Blob(
                            androidId = androidIdSv.substringBefore('.'),
                            name = name,
                        )
                        callback(FileInfo(blob, file.length()))
                    }
                }
            }
        }
    }

    private suspend fun DocumentFile.listFilesRecursive(
        depth: Int,
        callback: (DocumentFile) -> Unit,
    ) {
        if (depth <= 0) return
        listFilesBlocking(context).forEach { file ->
            callback(file)
            if (file.isDirectory) file.listFilesRecursive(depth - 1, callback)
        }
    }

    override suspend fun remove(handle: FileHandle) {
        val file = cache.getFile(handle)
        if (!file.delete()) throw IOException("could not delete ${handle.relativePath}")
    }

    override suspend fun rename(from: TopLevelFolder, to: TopLevelFolder) {
        val fromFile = cache.getFile(from)
        // don't use fromFile.renameTo(to.name) as that creates "${to.name} (1)"
        val newUri = renameDocument(context.contentResolver, fromFile.uri, to.name)
            ?: throw IOException("could not rename ${from.relativePath}")
        val toFile = DocumentFile.fromTreeUri(context, newUri)
            ?: throw IOException("renamed URI invalid: $newUri")
        if (toFile.name != to.name) {
            toFile.delete()
            throw IOException("renamed to ${toFile.name}, but expected ${to.name}")
        }
    }

    override suspend fun removeAll() {
        cache.getRootFile().listFilesBlocking(context).forEach {
            it.delete()
        }
    }

}
