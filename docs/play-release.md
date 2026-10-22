# Android release checklist — pending

Android requests INTERNET, POST_NOTIFICATIONS and the Android 14
FOREGROUND_SERVICE_REMOTE_MESSAGING permission. It denies cleartext traffic in
the release manifest, disables backup and does not register VpnService or
AccessibilityService. The debug manifest allows HTTP for loopback `adb reverse`.
The foreground service starts only after the user connects the bridge and has a
persistent Stop action.

Approval requests use a high-importance notification channel. The optional
user switch uses an alarm-sound channel and repeats the notification every five
minutes until the request is handled or expires. It does not request exact-alarm
access or use a full-screen intent. Android notification permission, DND and the
user's channel settings still control visibility and sound. This is not a
Play-ready release.

Before a release: verify current target API and Google Play requirements; implement
pairing, verified TLS, encrypted cache/Keystore, user-driven system file pickers,
clear data-retention controls, consent and revocation. Add Privacy Policy,
accurate Data safety, licensing/NOTICE, signed artifacts and a labeled review demo.
Reliable delivery after the foreground connection is stopped requires a separately
designed privacy-preserving push or polling path.
No VPN engine is planned; ordinary HTTPS/WSS uses the existing OS network.

Android 12+ cloud and device-transfer exclusions are explicit in data_extraction_rules.xml; Android 11 uses allowBackup/fullBackupContent=false. Reference: https://developer.android.com/identity/data/autobackup
