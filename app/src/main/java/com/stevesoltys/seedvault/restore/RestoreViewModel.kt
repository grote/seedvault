package com.stevesoltys.seedvault.restore

import android.app.Application
import android.app.backup.IBackupManager
import android.app.backup.IRestoreObserver
import android.app.backup.IRestoreSession
import android.app.backup.RestoreSet
import android.os.RemoteException
import android.os.UserHandle
import android.util.Log
import androidx.annotation.WorkerThread
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.Transformations.switchMap
import androidx.lifecycle.asLiveData
import androidx.lifecycle.viewModelScope
import com.stevesoltys.seedvault.BackupMonitor
import com.stevesoltys.seedvault.R
import com.stevesoltys.seedvault.crypto.KeyManager
import com.stevesoltys.seedvault.restore.DisplayFragment.RESTORE_APPS
import com.stevesoltys.seedvault.restore.DisplayFragment.RESTORE_BACKUP
import com.stevesoltys.seedvault.settings.SettingsManager
import com.stevesoltys.seedvault.transport.TRANSPORT_ID
import com.stevesoltys.seedvault.transport.restore.ApkRestore
import com.stevesoltys.seedvault.transport.restore.InstallResult
import com.stevesoltys.seedvault.transport.restore.RestoreCoordinator
import com.stevesoltys.seedvault.ui.LiveEvent
import com.stevesoltys.seedvault.ui.MutableLiveEvent
import com.stevesoltys.seedvault.ui.RequireProvisioningViewModel
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.launch
import kotlin.coroutines.Continuation
import kotlin.coroutines.resume
import kotlin.coroutines.suspendCoroutine

private val TAG = RestoreViewModel::class.java.simpleName

internal class RestoreViewModel(
        app: Application,
        settingsManager: SettingsManager,
        keyManager: KeyManager,
        private val backupManager: IBackupManager,
        private val restoreCoordinator: RestoreCoordinator,
        private val apkRestore: ApkRestore,
        private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) : RequireProvisioningViewModel(app, settingsManager, keyManager), RestorableBackupClickListener {

    override val isRestoreOperation = true

    private var session: IRestoreSession? = null
    private val monitor = BackupMonitor()

    private val mDisplayFragment = MutableLiveEvent<DisplayFragment>()
    internal val displayFragment: LiveEvent<DisplayFragment> = mDisplayFragment

    private val mRestoreSetResults = MutableLiveData<RestoreSetResult>()
    internal val restoreSetResults: LiveData<RestoreSetResult> get() = mRestoreSetResults

    private val mChosenRestorableBackup = MutableLiveData<RestorableBackup>()
    internal val chosenRestorableBackup: LiveData<RestorableBackup> get() = mChosenRestorableBackup

    internal val installResult: LiveData<InstallResult> = switchMap(mChosenRestorableBackup) { backup ->
        @Suppress("EXPERIMENTAL_API_USAGE")
        getInstallResult(backup)
    }

    private val mNextButtonEnabled = MutableLiveData<Boolean>().apply { value = false }
    internal val nextButtonEnabled: LiveData<Boolean> = mNextButtonEnabled

    private val mRestoreProgress = MutableLiveData<String>()
    internal val restoreProgress: LiveData<String> get() = mRestoreProgress

    private val mRestoreBackupResult = MutableLiveData<RestoreBackupResult>()
    internal val restoreBackupResult: LiveData<RestoreBackupResult> get() = mRestoreBackupResult

    @Throws(RemoteException::class)
    private fun getOrStartSession(): IRestoreSession {
        val session = this.session
                ?: backupManager.beginRestoreSessionForUser(UserHandle.myUserId(), null, TRANSPORT_ID)
                ?: throw RemoteException("beginRestoreSessionForUser returned null")
        this.session = session
        return session
    }

    internal fun loadRestoreSets() = viewModelScope.launch {
        mRestoreSetResults.value = getAvailableRestoreSets()
    }

    private suspend fun getAvailableRestoreSets() = suspendCoroutine<RestoreSetResult> { continuation ->
        val session = try {
            getOrStartSession()
        } catch (e: RemoteException) {
            Log.e(TAG, "Error starting new session", e)
            continuation.resume(RestoreSetResult(app.getString(R.string.restore_set_error)))
            return@suspendCoroutine
        }

        val observer = RestoreObserver(continuation)
        val setResult = session.getAvailableRestoreSets(observer, monitor)
        if (setResult != 0) {
            Log.e(TAG, "getAvailableRestoreSets() returned non-zero value")
            continuation.resume(RestoreSetResult(app.getString(R.string.restore_set_error)))
            return@suspendCoroutine
        }
    }

    override fun onRestorableBackupClicked(restorableBackup: RestorableBackup) {
        mChosenRestorableBackup.value = restorableBackup
        mDisplayFragment.setEvent(RESTORE_APPS)

        // re-installing apps will take some time and the session probably times out
        // so better close it cleanly and re-open it later
        closeSession()
    }

    @ExperimentalCoroutinesApi
    private fun getInstallResult(restorableBackup: RestorableBackup): LiveData<InstallResult> {
        return apkRestore.restore(restorableBackup.token, restorableBackup.packageMetadataMap)
                .onStart {
                    Log.d(TAG, "Start InstallResult Flow")
                }.catch { e ->
                    Log.d(TAG, "Exception in InstallResult Flow", e)
                }.onCompletion { e ->
                    Log.d(TAG, "Completed InstallResult Flow", e)
                    mNextButtonEnabled.postValue(true)
                }
                .flowOn(ioDispatcher)
                .asLiveData()
    }

    internal fun onNextClicked() {
        mDisplayFragment.postEvent(RESTORE_BACKUP)
        val token = mChosenRestorableBackup.value?.token ?: throw AssertionError()
        viewModelScope.launch(ioDispatcher) {
            startRestore(token)
        }
    }

    @WorkerThread
    private suspend fun startRestore(token: Long) {
        Log.d(TAG, "Starting new restore session to restore backup $token")

        // we need to start a new session and retrieve the restore sets before starting the restore
        val restoreSetResult = getAvailableRestoreSets()
        if (restoreSetResult.hasError()) {
            mRestoreBackupResult.postValue(RestoreBackupResult(app.getString(R.string.restore_finished_error)))
            return
        }

        // now we can start the restore of all available packages
        val observer = RestoreObserver()
        val restoreAllResult = session?.restoreAll(token, observer, monitor) ?: 1
        if (restoreAllResult != 0) {
            if (session == null) Log.e(TAG, "session was null")
            else Log.e(TAG, "restoreAll() returned non-zero value")
            mRestoreBackupResult.postValue(RestoreBackupResult(app.getString(R.string.restore_finished_error)))
            return
        }
    }

    override fun onCleared() {
        super.onCleared()
        closeSession()
    }

    private fun closeSession() {
        session?.endRestoreSession()
        session = null
    }

    @WorkerThread
    private inner class RestoreObserver(private val continuation: Continuation<RestoreSetResult>? = null) : IRestoreObserver.Stub() {

        /**
         * Supply a list of the restore datasets available from the current transport.
         * This method is invoked as a callback following the application's use of the
         * [IRestoreSession.getAvailableRestoreSets] method.
         *
         * @param restoreSets An array of [RestoreSet] objects
         *   describing all of the available datasets that are candidates for restoring to
         *   the current device. If no applicable datasets exist, restoreSets will be null.
         */
        override fun restoreSetsAvailable(restoreSets: Array<out RestoreSet>?) {
            check (continuation != null) { "Getting restore sets without continuation" }

            val result = if (restoreSets == null || restoreSets.isEmpty()) {
                RestoreSetResult(app.getString(R.string.restore_set_empty_result))
            } else {
                val backupMetadata = restoreCoordinator.getAndClearBackupMetadata()
                if (backupMetadata == null) {
                    Log.e(TAG, "RestoreCoordinator#getAndClearBackupMetadata() returned null")
                    RestoreSetResult(app.getString(R.string.restore_set_error))
                } else {
                    val restorableBackups = restoreSets.mapNotNull { set ->
                        val metadata = backupMetadata[set.token]
                        when {
                            metadata == null -> {
                                Log.e(TAG, "RestoreCoordinator#getAndClearBackupMetadata() has no metadata for token ${set.token}.")
                                null
                            }
                            metadata.time == 0L -> {
                                Log.d(TAG, "Ignoring RestoreSet with no last backup time: ${set.token}.")
                                null
                            }
                            else -> {
                                RestorableBackup(set, metadata)
                            }
                        }
                    }
                    RestoreSetResult(restorableBackups)
                }
            }
            continuation.resume(result)
        }

        /**
         * The restore operation has begun.
         *
         * @param numPackages The total number of packages being processed in this restore operation.
         */
        override fun restoreStarting(numPackages: Int) {
            // noop
        }

        /**
         * An indication of which package is being restored currently,
         * out of the total number provided in the [restoreStarting] callback.
         * This method is not guaranteed to be called.
         *
         * @param nowBeingRestored The index, between 1 and the numPackages parameter
         *   to the [restoreStarting] callback, of the package now being restored.
         * @param currentPackage The name of the package now being restored.
         */
        override fun onUpdate(nowBeingRestored: Int, currentPackage: String) {
            // nowBeingRestored reporting is buggy, so don't use it
            mRestoreProgress.postValue(currentPackage)
        }

        /**
         * The restore operation has completed.
         *
         * @param result Zero on success; a nonzero error code if the restore operation
         *   as a whole failed.
         */
        override fun restoreFinished(result: Int) {
            val restoreResult = RestoreBackupResult(
                    if (result == 0) null
                    else app.getString(R.string.restore_finished_error)
            )
            mRestoreBackupResult.postValue(restoreResult)
            closeSession()
        }

    }

}

internal class RestoreSetResult(
        internal val restorableBackups: List<RestorableBackup>,
        internal val errorMsg: String?) {

    internal constructor(restorableBackups: List<RestorableBackup>) : this(restorableBackups, null)

    internal constructor(errorMsg: String) : this(emptyList(), errorMsg)

    internal fun hasError(): Boolean = errorMsg != null
}

internal class RestoreBackupResult(val errorMsg: String? = null) {
    internal fun hasError(): Boolean = errorMsg != null
}

internal enum class DisplayFragment { RESTORE_APPS, RESTORE_BACKUP }
