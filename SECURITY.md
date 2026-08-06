# Security Policy

## Supported versions

There is no stable production release yet. `v0.5.0-alpha.6` is a dedicated-key-signed prerelease for testing and receives fixes on a best-effort basis. Pre-release builds may change storage or protocol behavior without backward compatibility.

GitHub Releases publish neither debug-signed nor unsigned APKs. Verify the downloaded release APK against the SHA-256 file attached to the same release. A matching checksum verifies bytes; trust in the first installation still depends on obtaining it from the authentic project release page. Subsequent versions must retain the dedicated signing key so Android can verify update continuity.

The in-app updater is manual-only. A user-triggered check first reads an exact, non-redirecting HTTPS update-index URL under the project's GitHub Pages site, with a strict schema, repository identity, release-count, and response-size boundary. If that request is unavailable or invalid, the app makes one bounded request to the GitHub Releases REST API. Both paths accept only canonical release metadata with exact asset names and trusted GitHub release URLs. Downloads are streamed into the app cache with size and timeout limits, then verified by SHA-256, package name, newer version code/name, and the installed signing certificate before the APK is exposed through a narrowly scoped `FileProvider`. Android's system installer remains responsible for user confirmation and the unknown-app-source permission; checks, downloads, and installs never begin automatically.

The dedicated Release signing certificate has this SHA-256 fingerprint:

```text
55:FA:F4:A1:58:7B:58:7A:BF:0E:42:7C:08:02:99:32:35:DA:E8:1E:3C:05:B6:21:C2:FE:D3:F5:7A:60:89:C7
```

## Reporting a vulnerability

Please use GitHub's private security advisory feature for vulnerabilities involving authentication, TLS validation, credential storage, command execution, or unintended network access.

Do not include real Quicker verification codes, private IP mappings, action parameters, or other credentials in public issues.

## Security boundaries

- Quicker Link is intended for a trusted, mutually reachable LAN.
- WSS is the only supported transport; the app does not offer a cleartext WS downgrade.
- A non-empty Quicker WebSocket verification code is strongly recommended because the service can expose screen, input, clipboard, files, actions, and computer controls. Passwordless WSS remains supported for compatibility, but should be used only on a fully trusted LAN; WSS certificate and hostname validation still apply.
- WSS uses normal hostname verification and the Android system trust store. For `lan.quicker.cc` only, the network security configuration also includes a bundled copy of the public ISRG Root X2 certificate to support devices whose system store cannot build that public chain. This is not a leaf-certificate pin, a private CA, or a trust-all fallback. User-installed interception CAs are not added by the app.
- Connection and discovery clients bypass HTTP proxies and resolve the validated Quicker LAN hostname directly to its encoded private IPv4 address. The app does not implement `VpnService`, a system-wide proxy, or Clash traffic forwarding.
- Discovery is restricted to private IPv4 Wi-Fi/Ethernet subnets and bounded by fixed candidate, concurrency, and timeout limits. Probes perform only certificate-validated WSS handshakes and send no verification code; automatic authentication begins only when the completed scan has exactly one candidate.
- A successful discovery handshake identifies a compatible WSS endpoint, not a durable identity for a particular computer. Use the locally generated QR code or a verified manual address on an untrusted or ambiguous LAN.
- Pairing QR codes contain the connection endpoint and, when configured, the verification code. Generate and scan them locally, never publish them, and regenerate the code after suspected disclosure.
- The companion action reads but cannot create, change, or apply Quicker's verification code. The app follows the explicitly supplied mode: an empty code accepts only Quicker's passwordless handshake, while a non-empty app code must match an enabled Quicker code. A password-configured client ignores any preliminary passwordless success form and waits for the response correlated to its code-authentication request; it never treats that form as authenticated or silently downgrades.
- The app does not make a LAN service safe to expose directly to the public internet.
- Triggered Quicker actions run with the permissions and side effects defined by the user on the computer.
- Action triggers are dispatched without waiting for completion, so an accepted send does not prove that the computer-side action finished successfully.
- The current Android client and companion action use strict generation v9 capability and response contracts. Older or malformed catalog, stop, toolbox, desktop-push, and desktop-window data is rejected rather than coerced.
- Desktop-window activation accepts only short-lived random tokens bound to the enumerated window and process. Android never receives or submits a raw Windows window handle.
- A local Quicker automation may ask the companion action to send bounded text, an Android notification, or a staged file offer to authenticated phones. Remote/external action invocation cannot use that desktop-push entry point to submit a computer path. File offers still require explicit acceptance on the phone.
- Background receive is enabled by default and can be explicitly disabled. It uses an Android `connectedDevice` foreground service with a persistent service notification and Stop action. On Android 13+, denying `POST_NOTIFICATIONS` suppresses visual desktop notifications but does not disable the foreground service or WSS retention. The separate receipt cue is enabled by default, can be disabled without affecting delivery, and respects silent or vibration mode plus notification-stream volume and muting. Incoming text is handled by the application runtime, and file offers remain available for later UI acceptance, so Activity destruction alone does not interrupt delivery while the service and process remain alive. It has no boot receiver and is `START_NOT_STICKY`; stopping the service, force-stopping the app, or losing the process interrupts immediate command handling.
- Phone-initiated computer clipboard reads and writes require an explicit transfer-page command. Incoming bounded desktop text may attempt to update the phone clipboard, but the app does not continuously watch either clipboard.
- The app requests no SMS read/receive permission and does not monitor or forward SMS or one-time codes.
- File transfers are limited to 64 MiB in ordered 64 KiB chunks. This bounds up to 1,024 action-channel round trips, Base64 expansion, temporary disk use, and denial-of-service exposure; it is not a WebSocket frame limit.
- Remote action icons are restricted to HTTPS resources under `files.getquicker.net/_icons/` or `files.getquicker.net/_system/`; rendered PNG data is size- and signature-validated before display.
- Android backup and device-transfer rules exclude app preferences and the other app data domains, but this does not protect data extracted from a rooted or otherwise compromised device.
