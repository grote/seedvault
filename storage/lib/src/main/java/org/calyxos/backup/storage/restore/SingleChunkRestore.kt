package org.calyxos.backup.storage.restore

import android.util.Log
import org.calyxos.backup.storage.api.RestoreObserver
import org.calyxos.backup.storage.api.StoragePlugin
import org.calyxos.backup.storage.crypto.StreamCrypto

private const val TAG = "SingleChunkRestore"

@Suppress("BlockingMethodInNonBlockingContext")
internal class SingleChunkRestore(
    storagePlugin: StoragePlugin,
    fileRestore: FileRestore,
    streamCrypto: StreamCrypto,
    streamKey: ByteArray
) : AbstractChunkRestore(storagePlugin, fileRestore, streamCrypto, streamKey) {

    suspend fun restore(
        version: Int,
        chunks: Collection<RestorableChunk>,
        observer: RestoreObserver?
    ): Int {
        var restoredFiles = 0
        chunks.forEach { chunk ->
            check(chunk.files.size == 1)
            val file = chunk.files[0]
            try {
                getAndDecryptChunk(version, chunk.chunkId) { decryptedStream ->
                    restoreFile(file, observer, "M") { outputStream ->
                        decryptedStream.copyTo(outputStream)
                    }
                }
                restoredFiles++
            } catch (e: Exception) {
                Log.e(TAG, "Failed to restore single chunk file ${file.path}", e)
                observer?.onFileRestoreError(file, e)
                // we try to continue to restore as many files as possible
            }
        }
        return restoredFiles
    }

}
