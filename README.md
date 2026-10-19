# Fo Assist

Android client for Fo (Wajo). Plain-Java `VoiceInteractionService` that sets itself as the phone's
assistant and sends requests to an fo-bridge server, which relays them to Fo and pushes replies back over ntfy.

Features: assist-gesture voice/screenshot requests, Wajo sign-in per device, floating "bird" chat head for
Fo notifications, busy-call auto-reply by SMS.

## Build

No Gradle. Needs the Android SDK (build-tools 36.0.0, platform android-36) at `$ANDROID_HOME` or `~/android-sdk`, and JDK 17+.

```
echo https://your-bridge.example > bridge.url   # optional: default bridge address, untracked
./build.sh   # -> build/fo-assist.apk
```

Without `bridge.url` the app asks for the bridge address in its settings.

`build.sh` creates `keystore.jks` on first run. It is gitignored: keep a backup, since an APK signed with
a different key can't upgrade an installed one.
