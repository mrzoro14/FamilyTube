# FamilyTube

A simple app for family and friends to save **YouTube** videos and music and **Instagram** Reels to their phone or PC.

## Download

**[⬇ Get the latest version](https://github.com/mrzoro14/FamilyTube/releases/latest)**

- **Android phones:** download `FamilyTube.apk`, open it, and tap Install.
- **Windows laptops:** download `FamilyTube-Portable-Windows.exe` and double-click it. It opens in your browser and runs only on that laptop, with nothing to install.

The instructions for each are in the release notes. You only install it once: the app tells you when a new version is ready.

The laptop version has everything except WhatsApp Status saver, which only works on phones.

## How to use it (Android)

1. In YouTube or Instagram, tap **Share**, then **FamilyTube**.
2. A small panel pops up. Tap a quality: **360p / 720p / 1080p / Best**, or **MP3 Normal / High** for music only.
3. The download runs in the background and shows in the notification bar. Tap **Done! Tap to play** when it finishes.

Files are saved in **Download → FamilyTube**.

**Dashboard:** Home has a New download card, shortcuts and your recent downloads. The **Downloads** tab shows everything with live progress, including downloads started from the Share button, with **Play**, **Share**, **Cancel** and **Retry**. A message inside the app tells you when a download ends.

**Clips:** to save only part of a YouTube video, switch on **✂ Only download a part (clip)**, drag the slider or type the **From** and **To** times (like `1:05`), then pick a quality. It works for video and music. Clips copy the video without re-encoding so they're fast, which means a clip can start a second or so early in some players.

**WhatsApp Status saver:** open FamilyTube and tap **WhatsApp Status saver**. The first time, tap **Allow access → Use this folder → Allow**. Statuses you've watched show in a grid: tap one to view it, or tap **Save** to keep it in **Download → FamilyTube → WhatsApp Status**.

## What it can't do

- Private videos, Instagram Stories, and private Instagram accounts. These need a login, and FamilyTube never logs in to keep accounts safe.

Public Instagram photo posts work. Share one and tap **Save post**, and it saves every photo and video in the post.

Please only download videos you're allowed to keep.

## For the developer

- `app/`: Android app (Java). Built on [youtubedl-android](https://github.com/yausername/youtubedl-android), which bundles [yt-dlp](https://github.com/yt-dlp/yt-dlp), Python and ffmpeg. yt-dlp updates itself on the phone once a day.
- `windows/`: FamilyTube Portable Windows (Python, with the page in `windows/web/`). A private website on 127.0.0.1 that downloads yt-dlp, ffmpeg and Deno on first run. Build it with `windows/build_portable.ps1`.

Build the APK with `gradlew assembleRelease` (JDK 17+). Releases are signed with one key, so each new APK installs over the old one.

**Releasing an update:** bump `versionCode` and `versionName` in `app/build.gradle.kts`, build, then create a GitHub release tagged `v<versionName>` (for example `v1.4`) and attach `FamilyTube.apk`. Installed apps see the new tag and offer the update.

**Releasing a laptop-only update:** bump `VERSION` in `windows/familytube_portable.py`, run `windows/build_portable.ps1`, then attach the exe and `portable.json` to the latest release with `gh release upload <tag> ... --clobber`. Laptops read `portable.json`, and phones aren't prompted because there's no new tag.

## License

GPL-3.0, the same as youtubedl-android. See [LICENSE](LICENSE).
