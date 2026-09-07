# Spoiler Alert

Spoiler Alert is an Android notification listener for sports fans who watch games on their own schedule. Arm a team or custom keyword shield, and matching notifications are held on-device until you choose to reveal them.

> **TL;DR:** Build with JDK 17 and `./gradlew :app:assembleDebug`. Install the resulting debug APK, whose `.opensource` application ID keeps it separate from the author's private app. Grant notification-listener access in Android settings, then try the onboarding's self-labeled synthetic notification demo.

## Privacy and network boundary

Notification text is matched and stored locally. It is never sent to a network service. The only network code is `schedule/ScheduleFetcher.kt`, which makes bounded GET requests to public ESPN schedule and event-status endpoints. It has no access to the notification service or vault. Network features are best-effort and can be avoided by using custom keyword shields.

Android cannot restore another app's cancelled notification or unread state. Spoiler Alert retains protected notification content locally so you can reveal it later and open the source app.

## Build and install

Requirements: Android SDK with API 35, JDK 17, and an emulator or Android device running API 26 or newer.

Clone the repository and enter it:

```bash
git clone https://github.com/jessecmaddox3/spoiler-alert-android.git
cd spoiler-alert-android
```

Install JDK 17 and Android Studio, then use Android Studio's SDK Manager to install **Android SDK Platform 35** and **Android SDK Build-Tools**. Set `ANDROID_HOME` to that SDK directory (often `$HOME/Library/Android/sdk` on macOS), or create an uncommitted `local.properties` with `sdk.dir=/absolute/path/to/Android/sdk`.

```bash
export JAVA_HOME=/path/to/jdk-17
./gradlew :app:testDebugUnitTest :app:lintDebug :app:assembleDebug
adb install app/build/outputs/apk/debug/app-debug.apk
```

The debug app ID is `com.jessemaddox.spoileralert.opensource`, so it will not update or replace `com.jessemaddox.spoileralert`. `assembleRelease` produces an unsigned APK because this repository contains no keystore or signing configuration. Sign a release with your own credentials if you distribute one.

Instrumented tests require an emulator or device:

```bash
./gradlew :app:connectedDebugAndroidTest
```

## Public-release catalog and assets

This repository includes a factual, manually curated team and player reference catalog so it works with real sports out of the box. It does not redistribute professional team logos, generated artwork, or font files. Generic emoji and vector artwork are used where a visual mark would otherwise appear. Team and league names are used nominatively and do not imply affiliation or endorsement.

## Development

Run `./gradlew :app:testDebugUnitTest :app:lintDebug` before opening a pull request. The project uses Kotlin, Jetpack Compose, Room, WorkManager, Glance, and JUnit. The notification and vault boundary is deliberate: do not add network access outside `schedule/`, and do not import notification or vault code into that package.

The current public source targets SDK 35. This is suitable for local development, but it may need an SDK update before a Play submission.

See [CONTRIBUTING.md](CONTRIBUTING.md), [SECURITY.md](SECURITY.md), and [NOTICE](NOTICE).
