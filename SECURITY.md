# Security Policy

## Supported versions

There is no stable production release yet. `v0.5.0-alpha.2` is a dedicated-key-signed prerelease for testing and receives fixes on a best-effort basis. Pre-release builds may change storage or protocol behavior without backward compatibility.

GitHub Releases publish neither debug-signed nor unsigned APKs. Verify the downloaded release APK against the SHA-256 file attached to the same release. A matching checksum verifies bytes; trust in the first installation still depends on obtaining it from the authentic project release page. Subsequent versions must retain the dedicated signing key so Android can verify update continuity.

The in-app updater is manual-only. It accepts exact release asset names and trusted GitHub release URLs, streams downloads into the app cache with size and timeout limits, and verifies SHA-256, package name, newer version code/name, and the installed signing certificate before exposing the APK through a narrowly scoped `FileProvider`. Android's system installer remains responsible for user confirmation and the unknown-app-source permission.

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
- The current Android client and companion action use strict generation v8 capability and response contracts. Older or malformed catalog, stop, toolbox, and desktop-push data is rejected rather than coerced.
- A local Quicker automation may ask the companion action to send bounded text, an Android notification, or a staged file offer to authenticated phones. Remote/external action invocation cannot use that desktop-push entry point to submit a computer path. File offers still require explicit acceptance on the phone.
- The optional background connection is off by default and uses an Android `connectedDevice` foreground service with a persistent notification and Stop action. Incoming text and notifications are handled by the application runtime, and file offers remain available for later UI acceptance, so Activity destruction alone does not interrupt delivery while the service and process remain alive. It has no boot receiver and is `START_NOT_STICKY`; stopping the service or losing the process interrupts immediate command handling.
- Automatic clipboard synchronization is separately off by default, runs only while the app is foregrounded, rejects Android-sensitive clipboard content, bounds text size, and keeps only SHA-256 fingerprints for in-memory loop suppression. Enabling the background connection does not enable background clipboard access.
- The app requests no SMS read/receive permission and does not monitor or forward SMS or one-time codes.
- File transfers are limited to 64 MiB in ordered 64 KiB chunks. This bounds up to 1,024 action-channel round trips, Base64 expansion, temporary disk use, and denial-of-service exposure; it is not a WebSocket frame limit.
- Remote action icons are restricted to HTTPS resources under `files.getquicker.net/_icons/` or `files.getquicker.net/_system/`; rendered PNG data is size- and signature-validated before display.
- Android backup and device-transfer rules exclude app preferences and the other app data domains, but this does not protect data extracted from a rooted or otherwise compromised device.
