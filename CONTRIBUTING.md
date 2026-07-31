# Contributing

Issues and pull requests are welcome.

This public repository covers the Android client, tests, build pipeline, and public documentation. The separately distributed Quicker companion action is outside the contribution scope of this repository.

## Development setup

1. Install JDK 17 and Android SDK Platform 37.0.
2. Enable a local Quicker WebSocket service for manual integration testing.
3. Run the same verification tasks as regular CI before opening a pull request:

```bash
./gradlew testDebugUnitTest lintDebug
```

During development, run only the focused tests affected by the change. Building an APK is not required for every source edit; the signed tag workflow owns Release packaging.

On Windows, clone the repository to a path containing only ASCII characters. Gradle test workers can fail to construct their classpath when the project path contains non-ASCII characters, even though APK compilation succeeds.

## Pull requests

- Keep Quicker official WebSocket envelope parsing compatible with both lower camel case and PascalCase response fields. This does not imply backward compatibility for Quicker Link catalog versions.
- Do not disable TLS certificate or hostname validation.
- Do not add analytics, advertising, or cloud services without prior discussion.
- Add focused tests for URL validation and protocol serialization changes.
- Never commit Quicker verification codes, signing keys, `local.properties`, or private network captures.

For protocol uncertainties, reference the relevant Quicker documentation and distinguish documented behavior from behavior confirmed by real-device testing.

## Distribution

- Regular CI runs unit tests and Lint. It does not package or upload an APK.
- Tag releases repeat tests and Lint, then build with the dedicated release key through GitHub Secrets and publish only after signature verification.
- Generate checksums from the exact APK bytes being published, then attach the matching checksum file to the same release.
- Never publish debug-signed APKs through GitHub Releases.

See [the release guide](docs/RELEASING.md) for the required secrets, tag/version check, and key-handling rules.
