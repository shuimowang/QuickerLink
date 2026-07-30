# Contributing

Issues and pull requests are welcome.

## Development setup

1. Install JDK 17 and Android SDK Platform 37.0.
2. Enable a local Quicker WebSocket service for manual integration testing.
3. Run the verification tasks before opening a pull request:

```bash
./gradlew testDebugUnitTest lintDebug assembleDebug assembleRelease
```

On Windows, clone the repository to a path containing only ASCII characters. Gradle test workers can fail to construct their classpath when the project path contains non-ASCII characters, even though APK compilation succeeds.

## Pull requests

- Keep protocol changes compatible with both lower camel case and legacy PascalCase response fields.
- Do not disable TLS certificate or hostname validation.
- Do not add analytics, advertising, or cloud services without prior discussion.
- Add focused tests for URL validation and protocol serialization changes.
- Never commit Quicker verification codes, signing keys, `local.properties`, or private network captures.

For protocol uncertainties, reference the relevant Quicker documentation and distinguish documented behavior from behavior confirmed by real-device testing.

## Distribution

- Regular CI uploads only a clearly named debug test artifact and its SHA-256 file; it is not a GitHub Release.
- `assembleRelease` without signing environment variables is a compile check. Never publish its unsigned output.
- Tag releases use the dedicated release key through GitHub Secrets and publish only after signature verification.
- Generate checksums from the exact APK bytes being published, then attach the matching checksum file to the same release.
- Never publish debug-signed APKs through GitHub Releases.

See [the release guide](docs/RELEASING.md) for the required secrets, tag/version check, and key-handling rules.
