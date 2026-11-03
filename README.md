# Telik-TV

Android IPTV player for Android 10+ and Android TV/projectors.

## Features
- Media3 playback (HLS, DASH, progressive streams supported by ExoPlayer)
- Channel categories, search and favorites
- Remote JSON channel catalogue with manual/automatic refresh
- Catalogue URL configurable in app settings

## Build
Open the repository in Android Studio or run `gradle assembleDebug` using Gradle 8.9 and JDK 17.
GitHub Actions builds `app-debug.apk` on pushes to `main` and manual workflow dispatch.

## Channel catalogue
The app loads JSON from a configurable HTTPS URL. Example schema:
```json
{
  "version": 1,
  "channels": [
    {"id":"demo","name":"Demo channel","url":"https://example.com/live.m3u8","category":"Demo","logo":""}
  ]
}
```
Use only IPTV streams you are authorized to access. The repository does not include paid or copyrighted channel feeds.
