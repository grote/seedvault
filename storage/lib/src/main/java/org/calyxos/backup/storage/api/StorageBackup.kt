package org.calyxos.backup.storage.api

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract.isTreeUri
import android.provider.MediaStore
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.room.Room
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import org.calyxos.backup.storage.backup.Backup
import org.calyxos.backup.storage.backup.BackupSnapshot
import org.calyxos.backup.storage.backup.ChunksCacheRepopulater
import org.calyxos.backup.storage.db.Db
import org.calyxos.backup.storage.getDocumentPath
import org.calyxos.backup.storage.getMediaType
import org.calyxos.backup.storage.plugin.SnapshotRetriever
import org.calyxos.backup.storage.prune.Pruner
import org.calyxos.backup.storage.prune.RetentionManager
import org.calyxos.backup.storage.restore.FileRestore
import org.calyxos.backup.storage.restore.Restore
import org.calyxos.backup.storage.scanner.DocumentScanner
import org.calyxos.backup.storage.scanner.FileScanner
import org.calyxos.backup.storage.scanner.MediaScanner
import org.calyxos.backup.storage.toStoredUri
import java.util.concurrent.atomic.AtomicBoolean

private const val TAG = "StorageBackup"

@Suppress("BlockingMethodInNonBlockingContext")
public class StorageBackup(
    private val context: Context,
    plugin: StoragePlugin,
    private val dispatcher: CoroutineDispatcher = Dispatchers.IO,
) {

    private val db: Db by lazy {
        Room.databaseBuilder(context, Db::class.java, "seedvault-storage-local-cache")
            .build()
    }
    private val uriStore by lazy { db.getUriStore() }

    private val mediaScanner by lazy { MediaScanner(context) }
    private val snapshotRetriever = SnapshotRetriever(plugin)
    private val chunksCacheRepopulater = ChunksCacheRepopulater(db, plugin, snapshotRetriever)
    private val backup by lazy {
        val documentScanner = DocumentScanner(context)
        val fileScanner = FileScanner(uriStore, mediaScanner, documentScanner)
        Backup(context, db, fileScanner, plugin, chunksCacheRepopulater)
    }
    private val restore by lazy {
        Restore(context, plugin, snapshotRetriever, FileRestore(context, mediaScanner))
    }
    private val retention = RetentionManager(context)
    private val pruner by lazy { Pruner(db, retention, plugin, snapshotRetriever) }

    private val backupRunning = AtomicBoolean(false)
    private val restoreRunning = AtomicBoolean(false)

    public val uris: Set<Uri>
        @WorkerThread
        get() {
            return uriStore.getStoredUris().map { it.uri }.toSet()
        }

    @Throws(IllegalArgumentException::class)
    public suspend fun addUri(uri: Uri): Unit = withContext(dispatcher) {
        if (uri.authority == MediaStore.AUTHORITY) {
            if (uri !in mediaUris) throw IllegalArgumentException("Not a supported MediaStore URI")
        } else if (uri.authority == EXTERNAL_STORAGE_PROVIDER_AUTHORITY) {
            if (!isTreeUri(uri)) throw IllegalArgumentException("Not a tree URI")
        } else {
            throw IllegalArgumentException()
        }
        Log.e(TAG, "Adding URI $uri")
        uriStore.addStoredUri(uri.toStoredUri())
    }

    public suspend fun removeUri(uri: Uri): Unit = withContext(dispatcher) {
        Log.e(TAG, "Removing URI $uri")
        uriStore.removeStoredUri(uri.toStoredUri())
    }

    public suspend fun getUriSummaryString(): String = withContext(dispatcher) {
        val uris = uris.sortedDescending()
        val list = ArrayList<String>()
        for (uri in uris) {
            val nameRes = uri.getMediaType()?.nameRes
            if (nameRes == null) {
                uri.getDocumentPath()?.let { list.add(it) }
            } else {
                list.add(context.getString(nameRes))
            }
        }
        list.joinToString(", ", limit = 5)
    }

    @Deprecated("TODO remove for release")
    public fun clearCache() {
        db.clearAllTables()
    }

    public suspend fun runBackup(backupObserver: BackupObserver?): Boolean =
        withContext(dispatcher) {
            if (backupRunning.getAndSet(true)) {
                Log.w(TAG, "Backup already running, not starting a new one")
                return@withContext false
            }
            try {
                backup.runBackup(backupObserver)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error during backup", e)
                false
            } finally {
                backupRunning.set(false)
            }
        }

    /**
     * Sets how many backup snapshots to keep in storage when running [pruneOldBackups].
     *
     * @throws IllegalArgumentException if all retention values are set to 0.
     */
    public fun setSnapshotRetention(snapshotRetention: SnapshotRetention) {
        retention.setSnapshotRetention(snapshotRetention)
    }

    /**
     * Gets the current snapshot retention policy.
     */
    @WorkerThread
    public fun getSnapshotRetention(): SnapshotRetention = retention.getSnapshotRetention()

    /**
     * Prunes old backup snapshots according to the parameters set via [setSnapshotRetention].
     * This will delete backed up data. Use with care!
     *
     * Run this only after [runBackup] returns true to ensure
     * that no chunks from partial backups get removed and need to be re-uploaded.
     */
    public suspend fun pruneOldBackups(backupObserver: BackupObserver?): Boolean =
        withContext(dispatcher) {
            backupObserver?.onPruneStartScanning()
            try {
                pruner.prune(backupObserver)
                true
            } catch (e: Exception) {
                Log.e(TAG, "Error during pruning backups", e)
                backupObserver?.onPruneError(null, e)
                false
            }
        }

    public fun getBackupSnapshots(): Flow<SnapshotResult> {
        return restore.getBackupSnapshots()
    }

    public suspend fun restoreBackupSnapshot(
        snapshot: BackupSnapshot,
        restoreObserver: RestoreObserver? = null
    ): Boolean = withContext(dispatcher) {
        if (restoreRunning.getAndSet(true)) {
            Log.w(TAG, "Restore already running, not starting a new one")
            return@withContext false
        }
        try {
            restore.restoreBackupSnapshot(snapshot, restoreObserver)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error during restore", e)
            false
        } finally {
            restoreRunning.set(false)
        }
    }

    public suspend fun restoreBackupSnapshot(
        timestamp: Long,
        restoreObserver: RestoreObserver? = null
    ): Boolean = withContext(dispatcher) {
        try {
            restore.restoreBackupSnapshot(timestamp, restoreObserver)
            true
        } catch (e: Exception) {
            Log.e(TAG, "Error during restore", e)
            false
        }
    }

}
