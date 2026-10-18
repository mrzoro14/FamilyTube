# FamilyTube

A simple app for family and friends to save **YouTube** videos and music and **Instagram** Reels to their phone or PC.

## Download

**[⬇ Get the latest version](https://github.com/mrzoro14/FamilyTube/releases/latest)**

- **Android phones:** download `FamilyTube.apk`, open it, and tap Install.
- **Windows PCs:** download `FamilyTube.exe` and double-click it.

The instructions for each are in the release notes. You only install it once: the app tells you when a new version is ready.

## How to use it (Android)

1. In YouTube or Instagram, tap **Share**, then **FamilyTube**.
2. A small panel pops up. Tap a quality: **360p / 720p / 1080p / Best**, or **MP3 Normal / High** for music only.
3. The download runs in the background and shows in the notification bar. Tap **Done! Tap to play** when it finishes.

Files are saved in **Download → FamilyTube**.

**WhatsApp Status saver:** open FamilyTube and tap **WhatsApp Status saver**. The first time, tap **Allow access → Use this folder → Allow**. Statuses you've watched show in a grid: tap one to view it, or tap **Save** to keep it in **Download → FamilyTube → WhatsApp Status**.

## What it can't do

- Private videos, Instagram Stories, and private Instagram accounts (they need a login).
- Instagram photo posts (only videos and Reels).

Please only download videos you're allowed to keep.

## For the developer

- `app/`: Android app (Java). Built on [youtubedl-android](https://github.com/yausername/youtubedl-android), which bundles [yt-dlp](https://github.com/yt-dlp/yt-dlp), Python and ffmpeg. yt-dlp updates itself on the phone once a day.
- `windows/`: Windows app (Python + Tkinter). It downloads yt-dlp, ffmpeg and Deno on first run. Build it with `pyinstaller --onefile --windowed --name FamilyTube familytube.py`.

Build the APK with `gradlew assembleRelease` (JDK 17+). Releases are signed with one key, so each new APK installs over the old one.

**Releasing an update:** bump `versionCode` and `versionName` in `app/build.gradle.kts`, build, then create a GitHub release tagged `v<versionName>` (for example `v1.4`) and attach `FamilyTube.apk`. Installed apps see the new tag and offer the update.

## License

GPL-3.0, the same as youtubedl-android. See [LICENSE](LICENSE).
