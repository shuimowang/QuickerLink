# Release process

GitHub Releases must contain only APKs signed by Quicker Link's dedicated release key. Debug-signed and unsigned APKs are build outputs, not releases.

## Required GitHub environment

The repository uses a protected GitHub environment named `release`. It accepts only `v*` tags, requires approval from the repository owner, and disables administrator bypass. Version tags are protected against deletion and non-fast-forward updates, and immutable releases are enabled. Keep those controls active and add these environment secrets:

- `ANDROID_RELEASE_KEYSTORE_BASE64`: Base64 encoding of the complete JKS or PKCS12 keystore file.
- `ANDROID_RELEASE_KEYSTORE_PASSWORD`: Keystore password.
- `ANDROID_RELEASE_KEY_ALIAS`: Alias of the dedicated signing key.
- `ANDROID_RELEASE_KEY_PASSWORD`: Password for that key entry.

The keystore and passwords must never be committed, attached to issues, stored in workflow artifacts, or added to repository variables. Keep an encrypted offline backup under access control; losing the key prevents normal Android updates, while disclosure allows an attacker to sign malicious updates.

To encode the keystore for the Base64 secret on PowerShell:

```powershell
[Convert]::ToBase64String([IO.File]::ReadAllBytes(".\quicker-link-release.jks"))
```

On GNU/Linux:

```bash
base64 --wrap=0 quicker-link-release.jks
```

Treat the resulting text as the private keystore itself. Do not save it in the repository or paste it into logs.

## Publish a prerelease

1. Update `versionName`, `versionCode`, and `CHANGELOG.md`; the version name must be a valid tag suffix such as `0.1.0-alpha.1`.
2. Ensure regular CI passes `testDebugUnitTest` and `lintDebug` for the exact commit being tagged.
3. Create and push the matching tag, for example `v0.1.0-alpha.1`.
4. Approve the protected `release` environment deployment if required.

The tag workflow then:

- validates that all four signing secrets are non-empty;
- decodes the keystore only into the hosted runner's temporary directory;
- tests, lints, and builds the signed Release variant;
- requires the tag to equal `v` plus the APK's `versionName`;
- verifies the APK signature and expected certificate SHA-256 fingerprint with Android `apksigner`;
- generates and checks a SHA-256 file from the final renamed APK;
- creates a GitHub prerelease containing only that APK and checksum; and
- removes the decoded keystore even when an earlier step fails.

All reusable GitHub Actions in the signing workflow are pinned to full commit SHAs. Review Dependabot updates before accepting a new pinned revision; do not replace those revisions with mutable major-version tags.

Do not upload a local debug APK or unsigned `assembleRelease` output to a GitHub Release. Key rotation or loss requires a separately reviewed migration plan before another release is attempted.

## Verification levels

- Development: run focused tests for the code being changed. Do not build an APK unless the change affects packaging, resources, manifests, or device behavior.
- Main branch: regular CI runs the complete unit-test suite and Android Lint. New pushes cancel obsolete runs for the same branch.
- Tagged release: the protected workflow repeats tests and Lint, builds one signed Release APK, verifies its tag, certificate, and checksum, and publishes it.
- Device smoke test: reserve it for changes to pairing, networking, storage, permissions, updates, or user-facing workflows. Documentation and isolated unit-tested logic changes do not require reinstalling the app.
