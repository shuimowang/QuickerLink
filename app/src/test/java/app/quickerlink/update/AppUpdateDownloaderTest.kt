package app.quickerlink.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AppUpdateDownloaderTest {
    @Test
    fun `parses sha256sum output for the exact APK`() {
        val hash = "A".repeat(64)
        val name = "quicker-link-v0.2.0-release.apk"

        assertEquals(hash.lowercase(), parseSha256Checksum("$hash  $name\n", name))
        assertEquals(hash.lowercase(), parseSha256Checksum("$hash *$name\r\n", name))
    }

    @Test
    fun `rejects checksum for another file or multiple entries`() {
        val hash = "a".repeat(64)

        assertFailure(UpdateFailure.InvalidChecksum) {
            parseSha256Checksum("$hash  another.apk\n", "expected.apk")
        }
        assertFailure(UpdateFailure.InvalidChecksum) {
            parseSha256Checksum("$hash  expected.apk\n$hash  expected.apk\n", "expected.apk")
        }
        assertFailure(UpdateFailure.InvalidChecksum) {
            parseSha256Checksum("$hash expected.apk\n", "expected.apk")
        }
    }

    @Test
    fun `computes SHA-256 deterministically`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            sha256Hex("abc".toByteArray()),
        )
    }

    @Test
    fun `accepts only a newer expected APK with the same signer`() {
        verifyApkIdentities(
            installed = identity(versionName = "0.2.0-alpha.3", versionCode = 4),
            candidate = identity(versionName = "0.2.0-alpha.4", versionCode = 5),
            expectedVersionName = "0.2.0-alpha.4",
        )
    }

    @Test
    fun `rejects package version code and signer mismatches`() {
        assertFailure(UpdateFailure.WrongPackage) {
            verifyApkIdentities(
                installed = identity(versionName = "0.2.0-alpha.3", versionCode = 4),
                candidate = identity(
                    packageName = "evil.example",
                    versionName = "0.2.0-alpha.4",
                    versionCode = 5,
                ),
                expectedVersionName = "0.2.0-alpha.4",
            )
        }
        assertFailure(UpdateFailure.VersionMismatch) {
            verifyApkIdentities(
                installed = identity(versionName = "0.2.0-alpha.3", versionCode = 4),
                candidate = identity(versionName = "0.2.0-alpha.4", versionCode = 4),
                expectedVersionName = "0.2.0-alpha.4",
            )
        }
        assertFailure(UpdateFailure.SignatureMismatch) {
            verifyApkIdentities(
                installed = identity(versionName = "0.2.0-alpha.3", versionCode = 4),
                candidate = identity(
                    versionName = "0.2.0-alpha.4",
                    versionCode = 5,
                    signer = "other",
                ),
                expectedVersionName = "0.2.0-alpha.4",
            )
        }
    }

    private fun identity(
        packageName: String = EXPECTED_PACKAGE_NAME,
        versionName: String,
        versionCode: Long,
        signer: String = "signer",
    ) = ApkIdentity(packageName, versionName, versionCode, setOf(signer))

    private fun assertFailure(expected: UpdateFailure, block: () -> Unit) {
        val exception = assertThrows(UpdateInstallException::class.java, block)
        assertEquals(expected, exception.failure)
    }
}
