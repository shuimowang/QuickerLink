package app.quickerlink.update

import android.content.Context
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response

enum class UpdateFailure {
    InvalidRelease,
    UntrustedUrl,
    Network,
    DownloadTooLarge,
    InvalidChecksum,
    ChecksumMismatch,
    InvalidApk,
    WrongPackage,
    VersionMismatch,
    SignatureMismatch,
    Storage,
    ContentUri,
}

class UpdateInstallException(
    val failure: UpdateFailure,
    message: String,
    cause: Throwable? = null,
) : IOException(message, cause)

data class UpdateDownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long,
) {
    val fraction: Float
        get() = if (totalBytes <= 0L) 0f else (bytesDownloaded.toDouble() / totalBytes)
            .coerceIn(0.0, 1.0)
            .toFloat()
}

data class InstallReady(
    val release: AppRelease,
    val apkFile: File,
    val contentUri: Uri,
)

/**
 * Performs the user-requested download and all checks required before handing the APK to Android.
 * Calling this method never starts the package installer by itself.
 */
class AppUpdateDownloader(
    context: Context,
    private val client: OkHttpClient = defaultClient(),
) : AutoCloseable {
    private val applicationContext = context.applicationContext

    @Throws(UpdateInstallException::class)
    fun downloadAndVerify(
        release: AppRelease,
        onProgress: (UpdateDownloadProgress) -> Unit = {},
    ): InstallReady {
        validateReleaseMetadata(release)
        val checksumText = downloadChecksum(release.checksumUrl)
        val expectedSha256 = parseSha256Checksum(checksumText, release.apkFileName)

        val updateDirectory = prepareUpdateDirectory()
        val apkFile = resolveUpdateFile(updateDirectory, release.apkFileName)
        val partialFile = resolveUpdateFile(updateDirectory, ".${release.apkFileName}.partial")

        try {
            Files.deleteIfExists(partialFile.toPath())
            val actualSha256 = downloadApk(release, partialFile, onProgress)
            if (!MessageDigest.isEqual(hexToBytes(expectedSha256), hexToBytes(actualSha256))) {
                throw UpdateInstallException(
                    UpdateFailure.ChecksumMismatch,
                    "Downloaded APK checksum does not match the release checksum",
                )
            }

            verifyApkArchive(applicationContext, partialFile, release.versionName)
            moveVerifiedApk(partialFile, apkFile)

            val contentUri = try {
                FileProvider.getUriForFile(applicationContext, FILE_PROVIDER_AUTHORITY, apkFile)
            } catch (exception: RuntimeException) {
                throw UpdateInstallException(
                    UpdateFailure.ContentUri,
                    "The verified APK cannot be shared with the system installer",
                    exception,
                )
            }
            return InstallReady(release, apkFile, contentUri)
        } finally {
            runCatching { Files.deleteIfExists(partialFile.toPath()) }
        }
    }

    override fun close() {
        client.dispatcher.cancelAll()
        client.dispatcher.executorService.shutdown()
        client.connectionPool.evictAll()
        runCatching { client.cache?.close() }
    }

    private fun downloadChecksum(url: String): String {
        val request = downloadRequest(url)
        return executeDownload(request) { response ->
            val body = response.body
            val declaredLength = body.contentLength()
            if (declaredLength > MAX_CHECKSUM_DOWNLOAD_BYTES) {
                throw UpdateInstallException(
                    UpdateFailure.DownloadTooLarge,
                    "Release checksum is too large",
                )
            }
            val output = java.io.ByteArrayOutputStream()
            body.byteStream().use { input ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var total = 0L
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_CHECKSUM_DOWNLOAD_BYTES) {
                        throw UpdateInstallException(
                            UpdateFailure.DownloadTooLarge,
                            "Release checksum is too large",
                        )
                    }
                    output.write(buffer, 0, count)
                }
            }
            output.toByteArray().toString(Charsets.US_ASCII)
        }
    }

    private fun downloadApk(
        release: AppRelease,
        destination: File,
        onProgress: (UpdateDownloadProgress) -> Unit,
    ): String {
        val request = downloadRequest(release.apkUrl)
        return executeDownload(request) { response ->
            val body = response.body
            val declaredLength = body.contentLength()
            if (declaredLength > MAX_APK_DOWNLOAD_BYTES ||
                (declaredLength >= 0L && declaredLength != release.apkSizeBytes)
            ) {
                throw UpdateInstallException(
                    UpdateFailure.DownloadTooLarge,
                    "Release APK size does not match its metadata",
                )
            }

            val digest = MessageDigest.getInstance("SHA-256")
            try {
                body.byteStream().use { input ->
                    val fileOutput = try {
                        FileOutputStream(destination)
                    } catch (exception: IOException) {
                        throw UpdateInstallException(
                            UpdateFailure.Storage,
                            "The APK could not be opened in app storage",
                            exception,
                        )
                    }
                    try {
                        fileOutput.use { output ->
                            val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                            var total = 0L
                            onProgress(UpdateDownloadProgress(0L, release.apkSizeBytes))
                            while (true) {
                                val count = try {
                                    input.read(buffer)
                                } catch (exception: IOException) {
                                    throw UpdateInstallException(
                                        UpdateFailure.Network,
                                        "The APK download was interrupted",
                                        exception,
                                    )
                                }
                                if (count < 0) break
                                total += count
                                if (total > MAX_APK_DOWNLOAD_BYTES || total > release.apkSizeBytes) {
                                    throw UpdateInstallException(
                                        UpdateFailure.DownloadTooLarge,
                                        "Release APK exceeded its declared size",
                                    )
                                }
                                digest.update(buffer, 0, count)
                                try {
                                    output.write(buffer, 0, count)
                                } catch (exception: IOException) {
                                    throw UpdateInstallException(
                                        UpdateFailure.Storage,
                                        "The APK could not be written to app storage",
                                        exception,
                                    )
                                }
                                onProgress(UpdateDownloadProgress(total, release.apkSizeBytes))
                            }
                            try {
                                output.fd.sync()
                            } catch (exception: IOException) {
                                throw UpdateInstallException(
                                    UpdateFailure.Storage,
                                    "The APK could not be flushed to app storage",
                                    exception,
                                )
                            }
                            if (total != release.apkSizeBytes) {
                                throw UpdateInstallException(
                                    UpdateFailure.InvalidApk,
                                    "Downloaded APK is incomplete",
                                )
                            }
                        }
                    } catch (exception: UpdateInstallException) {
                        throw exception
                    } catch (exception: IOException) {
                        throw UpdateInstallException(
                            UpdateFailure.Storage,
                            "The APK could not be finalized in app storage",
                            exception,
                        )
                    }
                }
            } catch (exception: UpdateInstallException) {
                throw exception
            } catch (exception: IOException) {
                throw UpdateInstallException(
                    UpdateFailure.Network,
                    "The APK download could not be read",
                    exception,
                )
            }
            digest.digest().toHexString()
        }
    }

    private fun <T> executeDownload(request: Request, block: (Response) -> T): T = try {
        client.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw UpdateInstallException(
                    UpdateFailure.Network,
                    "GitHub returned HTTP ${response.code}",
                )
            }
            val finalUrl = response.request.url
            if (!finalUrl.isHttps || !isTrustedGitHubDownloadHost(finalUrl.host)) {
                throw UpdateInstallException(
                    UpdateFailure.UntrustedUrl,
                    "GitHub redirected the download to an untrusted address",
                )
            }
            block(response)
        }
    } catch (exception: UpdateInstallException) {
        throw exception
    } catch (exception: IOException) {
        throw UpdateInstallException(UpdateFailure.Network, "Release download failed", exception)
    }

    private fun downloadRequest(url: String): Request = Request.Builder()
        .url(url)
        .header("Accept", "application/octet-stream")
        .header("Cache-Control", "no-store")
        .header("User-Agent", USER_AGENT)
        .build()

    private fun prepareUpdateDirectory(): File {
        val directory = File(applicationContext.cacheDir, UPDATE_CACHE_DIRECTORY)
        if ((!directory.exists() && !directory.mkdirs()) || !directory.isDirectory) {
            throw UpdateInstallException(
                UpdateFailure.Storage,
                "The update cache directory could not be created",
            )
        }
        return try {
            directory.canonicalFile
        } catch (exception: IOException) {
            throw UpdateInstallException(
                UpdateFailure.Storage,
                "The update cache directory could not be resolved",
                exception,
            )
        }
    }

    private fun resolveUpdateFile(directory: File, fileName: String): File {
        val file = try {
            File(directory, fileName).canonicalFile
        } catch (exception: IOException) {
            throw UpdateInstallException(
                UpdateFailure.Storage,
                "The update file path could not be resolved",
                exception,
            )
        }
        if (file.parentFile != directory) {
            throw UpdateInstallException(UpdateFailure.InvalidRelease, "Update file escaped its cache directory")
        }
        return file
    }

    private fun moveVerifiedApk(source: File, destination: File) {
        try {
            try {
                Files.move(
                    source.toPath(),
                    destination.toPath(),
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING,
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(
                    source.toPath(),
                    destination.toPath(),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }
        } catch (exception: IOException) {
            throw UpdateInstallException(
                UpdateFailure.Storage,
                "The verified APK could not be finalized",
                exception,
            )
        }
    }

    private companion object {
        fun defaultClient(): OkHttpClient = OkHttpClient.Builder()
            .callTimeout(3, TimeUnit.MINUTES)
            .connectTimeout(12, TimeUnit.SECONDS)
            .readTimeout(45, TimeUnit.SECONDS)
            .retryOnConnectionFailure(false)
            .build()
    }
}

internal data class ApkIdentity(
    val packageName: String,
    val versionName: String,
    val versionCode: Long,
    val signerSha256: Set<String>,
)

internal fun verifyApkIdentities(
    installed: ApkIdentity,
    candidate: ApkIdentity,
    expectedVersionName: String,
) {
    if (installed.packageName != EXPECTED_PACKAGE_NAME || candidate.packageName != EXPECTED_PACKAGE_NAME) {
        throw UpdateInstallException(UpdateFailure.WrongPackage, "APK package name is not Quicker Link")
    }
    val installedVersion = ReleaseVersion.parse(installed.versionName)
        ?: throw UpdateInstallException(UpdateFailure.InvalidApk, "Installed app version is invalid")
    val candidateVersion = ReleaseVersion.parse(candidate.versionName)
        ?: throw UpdateInstallException(UpdateFailure.InvalidApk, "Downloaded APK version is invalid")
    val expectedVersion = ReleaseVersion.parse(expectedVersionName)
        ?: throw UpdateInstallException(UpdateFailure.InvalidRelease, "Expected release version is invalid")
    if (candidate.versionName != expectedVersionName || candidateVersion != expectedVersion) {
        throw UpdateInstallException(
            UpdateFailure.VersionMismatch,
            "Downloaded APK version does not match the selected release",
        )
    }
    if (candidateVersion <= installedVersion || candidate.versionCode <= installed.versionCode) {
        throw UpdateInstallException(UpdateFailure.VersionMismatch, "Downloaded APK is not a newer build")
    }
    if (installed.signerSha256.isEmpty() || candidate.signerSha256 != installed.signerSha256) {
        throw UpdateInstallException(
            UpdateFailure.SignatureMismatch,
            "Downloaded APK signing certificate does not match the installed app",
        )
    }
}

private fun verifyApkArchive(context: Context, apkFile: File, expectedVersionName: String) {
    val packageManager = context.packageManager
    val installed = packageManager.installedIdentity(context.packageName)
    val candidate = packageManager.archiveIdentity(apkFile)
    verifyApkIdentities(installed, candidate, expectedVersionName)
}

@Suppress("DEPRECATION")
private fun PackageManager.installedIdentity(packageName: String): ApkIdentity {
    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        PackageManager.GET_SIGNATURES
    }
    val packageInfo = try {
        getPackageInfo(packageName, flags)
    } catch (exception: PackageManager.NameNotFoundException) {
        throw UpdateInstallException(UpdateFailure.InvalidApk, "Installed app identity is unavailable", exception)
    }
    return packageInfo.toApkIdentity()
}

@Suppress("DEPRECATION")
private fun PackageManager.archiveIdentity(apkFile: File): ApkIdentity {
    val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        PackageManager.GET_SIGNING_CERTIFICATES
    } else {
        PackageManager.GET_SIGNATURES
    }
    val packageInfo = getPackageArchiveInfo(apkFile.absolutePath, flags)
        ?: throw UpdateInstallException(UpdateFailure.InvalidApk, "Downloaded file is not a valid APK")
    return packageInfo.toApkIdentity()
}

@Suppress("DEPRECATION")
private fun PackageInfo.toApkIdentity(): ApkIdentity {
    val certificates = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
        signingInfo?.apkContentsSigners.orEmpty()
    } else {
        signatures.orEmpty()
    }
    val signerDigests = certificates.mapTo(linkedSetOf()) { signature ->
        MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).toHexString()
    }
    val name = versionName
        ?: throw UpdateInstallException(UpdateFailure.InvalidApk, "APK has no version name")
    val code = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) longVersionCode else versionCode.toLong()
    return ApkIdentity(packageName, name, code, signerDigests)
}

internal fun parseSha256Checksum(text: String, expectedFileName: String): String {
    if ('\u0000' in text || '\r' in text.replace("\r\n", "")) {
        throw UpdateInstallException(UpdateFailure.InvalidChecksum, "Release checksum format is invalid")
    }
    val lines = text.replace("\r\n", "\n").split('\n').dropLastWhile(String::isEmpty)
    if (lines.size != 1) {
        throw UpdateInstallException(UpdateFailure.InvalidChecksum, "Release checksum must contain one entry")
    }
    val match = SHA256_LINE_PATTERN.matchEntire(lines.single())
        ?: throw UpdateInstallException(UpdateFailure.InvalidChecksum, "Release checksum format is invalid")
    if (match.groupValues[3] != expectedFileName) {
        throw UpdateInstallException(UpdateFailure.InvalidChecksum, "Release checksum names another file")
    }
    return match.groupValues[1].lowercase()
}

internal fun sha256Hex(bytes: ByteArray): String =
    MessageDigest.getInstance("SHA-256").digest(bytes).toHexString()

private fun ByteArray.toHexString(): String = joinToString(separator = "") { byte -> "%02x".format(byte) }

private fun hexToBytes(value: String): ByteArray = ByteArray(value.length / 2) { index ->
    value.substring(index * 2, index * 2 + 2).toInt(16).toByte()
}

private fun isTrustedGitHubDownloadHost(host: String): Boolean =
    host == "github.com" || host == "release-assets.githubusercontent.com"

private val SHA256_LINE_PATTERN = Regex("^([0-9A-Fa-f]{64}) ([ *])(.+)$")
