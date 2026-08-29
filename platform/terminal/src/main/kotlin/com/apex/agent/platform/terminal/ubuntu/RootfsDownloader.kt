package com.apex.agent.platform.terminal.ubuntu

import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.FileOutputStream
import java.io.InputStream
import java.security.MessageDigest

/**
 * T72: Streaming rootfs downloader with SHA-256 verification + TRUE range resume.
 *
 * §30 performance: streams the archive to disk — NEVER loads it into RAM.
 * §9: verifies SHA-256 after download; refuses to install on mismatch
 *     (bad file is DELETED, never kept around to poison a later attempt).
 * §8: retry with backoff; cancellation-aware per read.
 *
 * T72 fixes over the P69 version:
 *  - REAL resume: a leftover .part is resumed via HTTP `Range: bytes=<len>-`.
 *    P69 blindly appended a fresh full stream to the .part — guaranteed
 *    corruption on any retry. Now: 206 → append (true resume);
 *    200 (range ignored) → truncate the .part and restart;
 *    416 (part longer than remote file) → delete and restart.
 *  - single hash pass: P69 hashed the file ~3 times; the hash now happens
 *    exactly once, on the .part, BEFORE the atomic rename — so a failed
 *    check never leaves a "final-looking" archive behind.
 *
 * §28: structured logging via progress callback — no per-byte log spam.
 */
class RootfsDownloader(
    private val maxRetries: Int = 3,
    private val bufferBytes: Int = 64 * 1024
) {

    data class DownloadResult(
        val file: File,
        val bytesDownloaded: Long,     // total bytes on disk after this call
        val sha256Actual: String,
        val sha256Expected: String?,
        val checksumMatches: Boolean,
        val resumedFrom: Long,         // >0 if a partial download was resumed
        val durationMs: Long
    )

    /**
     * Downloads [artifact] to [targetFile]. Streams to a .part temp file
     * first, then atomically renames on success. Verifies SHA-256 if the
     * artifact carries one (§9). A mismatch DELETES the .part AND any stale
     * final file under the same name (T72: never leave a known-bad archive).
     *
     * [progress] receives (bytesSoFar, totalOrNull).
     */
    suspend fun download(
        source: RootfsArtifactSource,
        artifact: RootfsArtifact,
        targetFile: File,
        preflight: ProvisioningStoragePreflight? = null,
        progress: (suspend (Long, Long?) -> Unit)? = null
    ): Result<DownloadResult> = runCatching {
        if (preflight != null && !preflight.sufficient) {
            throw provisioningException(
                ProvisioningErrorCode.INSUFFICIENT_STORAGE,
                "Need ${preflight.totalRequired} bytes, only ${preflight.availableSpace} available",
                recoverable = false
            )
        }
        targetFile.parentFile?.mkdirs()

        var lastError: Throwable? = null
        for (attempt in 1..maxRetries) {
            try {
                return@runCatching attemptDownload(source, artifact, targetFile, progress)
            } catch (e: kotlinx.coroutines.CancellationException) {
                // §8: cancellation — propagate; the .part stays for a future resume
                throw e
            } catch (e: Throwable) {
                lastError = e
                if (attempt < maxRetries) delay(attempt * 1000L)
            }
        }
        throw wrapFinalError(lastError)
    }

    private suspend fun attemptDownload(
        source: RootfsArtifactSource,
        artifact: RootfsArtifact,
        targetFile: File,
        progress: (suspend (Long, Long?) -> Unit)?
    ): DownloadResult {
        val partFile = File(targetFile.parentFile, "${targetFile.name}.part")
        val startMs = System.currentTimeMillis()

        var append = false
        var resumedFrom = 0L
        var input: InputStream

        if (partFile.exists() && partFile.length() > 0) {
            val offset = partFile.length()
            try {
                val opened = source.open(artifact, offset).getOrElse {
                    throw provisioningException(
                        ProvisioningErrorCode.NETWORK_FAILURE,
                        "Failed to open source at offset $offset: ${it.message}",
                        recoverable = true,
                        cause = it
                    )
                }
                if (opened is RangeNotSupportedInputStream) {
                    // Plain 200: server cannot resume — drop the stale .part.
                    opened.close()
                    partFile.delete()
                    input = openFresh(source, artifact)
                } else {
                    input = opened
                    append = true
                    resumedFrom = offset
                }
            } catch (e: Throwable) {
                if (isStalePartError(e)) {
                    // 416: .part is longer than the remote file — bad state; restart.
                    partFile.delete()
                    input = openFresh(source, artifact)
                } else {
                    throw e
                }
            }
        } else {
            input = openFresh(source, artifact)
        }
        return transferAndFinish(input, partFile, targetFile, artifact, append, resumedFrom, progress, startMs)
    }

    private suspend fun openFresh(source: RootfsArtifactSource, artifact: RootfsArtifact): InputStream =
        source.open(artifact, 0).getOrElse {
            throw provisioningException(
                ProvisioningErrorCode.NETWORK_FAILURE,
                "Failed to open source: ${it.message}",
                recoverable = true,
                cause = it
            )
        }

    private fun isStalePartError(e: Throwable): Boolean {
        val m = e.message ?: return false
        return m.contains("416") || m.contains("stale .part")
    }

    private suspend fun transferAndFinish(
        input: InputStream,
        partFile: File,
        targetFile: File,
        artifact: RootfsArtifact,
        append: Boolean,
        resumedFrom: Long,
        progress: (suspend (Long, Long?) -> Unit)?,
        startMs: Long
    ): DownloadResult {
        var totalBytes = resumedFrom
        return coroutineScope {
            // TM5: wrap the copy in input.use { } so the source InputStream is closed on
            // ANY exit path (read error, cancellation, normal completion). Previously
            // input.close() lived OUTSIDE the out.use { } block — a network reset / TLS
            // error thrown by input.read(buf) propagated out of coroutineScope while the
            // HTTP stream / connection was leaked (only the FileOutputStream was closed
            // by `use`). Both streams are now closed regardless of which read/write throws.
            input.use { src ->
                FileOutputStream(partFile, append).use { out ->
                    val buf = ByteArray(bufferBytes)
                    while (true) {
                        currentCoroutineContext().ensureActive()   // §8: cancellation check
                        val n = src.read(buf)
                        if (n <= 0) break
                        out.write(buf, 0, n)
                        totalBytes += n
                        progress?.invoke(totalBytes, artifact.expectedSize)
                    }
                    out.fd.sync()   // flush to disk before hash + rename
                }
            }

            // §9: SHA-256 on the COMPLETE .part — exactly one pass. Resume
            // integrity is implied: the entire file (old + new bytes) is
            // hashed here regardless of where the transfer started.
            val actualSha = sha256OfFile(partFile)
            val expected = artifact.sha256
            if (expected != null && actualSha != expected) {
                // T72: a known-bad file must not survive.
                partFile.delete()
                targetFile.delete()
                throw provisioningException(
                    ProvisioningErrorCode.CHECKSUM_MISMATCH,
                    "SHA-256 mismatch: expected=$expected actual=$actualSha — bad file deleted",
                    recoverable = true
                )
            }

            // §18: atomic activate — rename .part → final. rename cannot change
            // content, so actualSha remains the truth for the final file.
            if (!partFile.renameTo(targetFile)) {
                partFile.copyTo(targetFile, overwrite = true)
                partFile.delete()
            }

            DownloadResult(
                file = targetFile,
                bytesDownloaded = totalBytes,
                sha256Actual = actualSha,
                sha256Expected = artifact.sha256,
                checksumMatches = artifact.sha256 == null || actualSha == artifact.sha256,
                resumedFrom = resumedFrom,
                durationMs = System.currentTimeMillis() - startMs
            )
        }
    }

    private fun wrapFinalError(lastError: Throwable?): Throwable {
        val msg = lastError?.message ?: ""
        return when {
            msg.contains("CHECKSUM_MISMATCH") -> provisioningException(
                ProvisioningErrorCode.CHECKSUM_MISMATCH, msg, recoverable = true, cause = lastError)
            msg.contains("stale .part") -> provisioningException(
                ProvisioningErrorCode.DOWNLOAD_FAILED, "Stale partial file: $msg", recoverable = true, cause = lastError)
            msg.contains("NETWORK_FAILURE") -> provisioningException(
                ProvisioningErrorCode.NETWORK_FAILURE, msg, recoverable = true, cause = lastError)
            msg.contains("INSUFFICIENT_STORAGE") -> provisioningException(
                ProvisioningErrorCode.INSUFFICIENT_STORAGE, msg, recoverable = false, cause = lastError)
            else -> provisioningException(
                ProvisioningErrorCode.DOWNLOAD_FAILED,
                "Download failed after $maxRetries attempts: ${lastError?.message}",
                recoverable = true, cause = lastError)
        }
    }

    companion object {
        /** One SHA-256 pass over a file (64KB buffered). */
        fun sha256OfFile(f: File): String {
            val sha = MessageDigest.getInstance("SHA-256")
            f.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                var read = input.read(buf)
                while (read > 0) {
                    sha.update(buf, 0, read)
                    read = input.read(buf)
                }
            }
            return sha.digest().joinToString("") { "%02x".format(it) }
        }
    }
}

/**
 * T72: marker stream — an artifact source that answered a ranged `open(offset)`
 * with a FULL (200-style) stream wraps it in this class. The downloader then
 * knows to discard the stale .part and restart instead of appending.
 */
class RangeNotSupportedInputStream(val delegate: InputStream) : InputStream() {
    override fun read(): Int = delegate.read()
    override fun read(b: ByteArray, off: Int, len: Int): Int = delegate.read(b, off, len)
    override fun available(): Int = delegate.available()
    override fun close() = delegate.close()
}

/** Builds a ProvisioningError + RuntimeException wrapper (Result.failure compatible). */
internal fun provisioningException(
    code: ProvisioningErrorCode,
    message: String,
    recoverable: Boolean = false,
    cause: Throwable? = null
): RuntimeException {
    // RuntimeException(msg, cause) already sets the cause. Do NOT call
    // initCause(cause) -- it throws IllegalStateException('Cause can't be set twice').
    return RuntimeException("ProvisioningError:" + code.name + " -- " + message, cause)
}
