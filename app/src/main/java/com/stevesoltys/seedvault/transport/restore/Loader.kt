/*
 * SPDX-FileCopyrightText: 2024 The Calyx Institute
 * SPDX-License-Identifier: Apache-2.0
 */

package com.stevesoltys.seedvault.transport.restore

import com.android.internal.R.attr.handle
import com.github.luben.zstd.ZstdInputStream
import com.stevesoltys.seedvault.backend.BackendManager
import com.stevesoltys.seedvault.crypto.Crypto
import com.stevesoltys.seedvault.header.UnsupportedVersionException
import com.stevesoltys.seedvault.header.VERSION
import io.github.oshai.kotlinlogging.KotlinLogging
import kotlinx.coroutines.runBlocking
import org.calyxos.seedvault.core.backends.AppBackupFileType
import org.calyxos.seedvault.core.toHexString
import java.io.ByteArrayInputStream
import java.io.File
import java.io.FilterInputStream
import java.io.IOException
import java.io.InputStream
import java.io.SequenceInputStream
import java.nio.ByteBuffer
import java.security.GeneralSecurityException
import java.util.Enumeration

internal class Loader(
    private val crypto: Crypto,
    private val backendManager: BackendManager,
) {

    private val log = KotlinLogging.logger {}

    /**
     * Downloads the given [fileHandle], decrypts and decompresses its content
     * and returns the content as a decrypted and decompressed stream.
     *
     * Attention: The responsibility with closing the returned stream lies with the caller.
     *
     * @param cacheFile if non-null, the ciphertext of the loaded file will be cached there
     * for later loading with [loadFile].
     */
    suspend fun loadFile(fileHandle: AppBackupFileType, cacheFile: File? = null): InputStream {
        val expectedHash = when (fileHandle) {
            is AppBackupFileType.Snapshot -> fileHandle.hash
            is AppBackupFileType.Blob -> fileHandle.name
        }
        return loadFromStream(backendManager.backend.load(fileHandle), expectedHash, cacheFile)
    }

    /**
     * The responsibility with closing the returned stream lies with the caller.
     */
    fun loadFile(file: File, expectedHash: String): InputStream {
        return loadFromStream(file.inputStream(), expectedHash)
    }

    suspend fun loadFiles(handles: List<AppBackupFileType>): InputStream {
        val enumeration: Enumeration<InputStream> = object : Enumeration<InputStream> {
            val iterator = handles.iterator()

            override fun hasMoreElements(): Boolean {
                return iterator.hasNext()
            }

            override fun nextElement(): InputStream {
                return runBlocking { loadFile(iterator.next()) }
            }
        }
        return SequenceInputStream(enumeration)
    }

    private fun loadFromStream(
        inputStream: InputStream,
        expectedHash: String,
        cacheFile: File? = null,
    ): InputStream {
        // We load the entire ciphertext into memory,
        // so we can check the SHA-256 hash before decrypting and parsing the data.
        val cipherText = inputStream.use { it.readAllBytes() }
        // check SHA-256 hash first thing
        val sha256 = crypto.sha256(cipherText).toHexString()
        if (sha256 != expectedHash) {
            throw GeneralSecurityException("File had wrong SHA-256 hash: $handle")
        }
        // check that we can handle the version of that snapshot
        val version = cipherText[0]
        if (version <= 1) throw GeneralSecurityException("Unexpected version: $version")
        if (version > VERSION) throw UnsupportedVersionException(version)
        // cache ciperText in cacheFile, if existing
        try {
            cacheFile?.outputStream()?.use { outputStream ->
                outputStream.write(cipherText)
            }
        } catch (e: Exception) {
            log.error(e) { "Error writing cache file $cacheFile: " }
        }
        // get associated data for version, used for authenticated decryption
        val ad = crypto.getAdForVersion(version)
        // skip first version byte when creating cipherText stream
        val byteStream = ByteArrayInputStream(cipherText, 1, cipherText.size - 1)
        // decrypt, de-pad and decompress cipherText stream
        val decryptingStream = crypto.newDecryptingStream(byteStream, ad)
        val paddedStream = PaddedInputStream(decryptingStream)
        return ZstdInputStream(paddedStream)
    }

}

private class PaddedInputStream(inputStream: InputStream) : FilterInputStream(inputStream) {

    val size: Int
    var bytesRead: Int = 0

    init {
        val sizeBytes = ByteArray(4)
        val bytesRead = inputStream.read(sizeBytes)
        if (bytesRead != 4) {
            throw IOException("Could not read padding size: ${sizeBytes.toHexString()}")
        }
        size = ByteBuffer.wrap(sizeBytes).getInt()
    }

    override fun read(): Int {
        if (bytesRead >= size) return -1
        return getReadBytes(super.read())
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (bytesRead >= size) return -1
        if (bytesRead + len >= size) {
            return getReadBytes(super.read(b, off, size - bytesRead))
        }
        return getReadBytes(super.read(b, off, len))
    }

    override fun available(): Int {
        return size - bytesRead
    }

    private fun getReadBytes(read: Int): Int {
        if (read == -1) return -1
        bytesRead += read
        if (bytesRead > size) return -1
        return read
    }
}
