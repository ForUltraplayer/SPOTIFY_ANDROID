# Myspoti + Hangul Pronunciation (Android)

[한국어](README.md) | **English**

A build of the Android Spotify app [Myspoti](https://github.com/kota-zo/myspoti) that adds Korean (Hangul) pronunciation for Japanese song lyrics. Pronunciation is pulled from Haneum and Namuwiki and matched line by line. Everything runs in the app, no PC or server needed.

Current version: 2.0.1-hangul.5 beta.

**[⬇ Download APK (v2.0.1-hangul.5)](https://github.com/ForUltraplayer/SPOTIFY_ANDROID/releases/tag/v2.0.1-hangul.5)**

PC version: [SPOTIFY_PC](https://github.com/ForUltraplayer/SPOTIFY_PC)

## Install

1. Download and open `Myspoti-Hangul-2.0.1-hangul.5.apk`. Allow app installs if Android asks.
2. Sign in with **Log in with Spotify**.
3. Play a song and open the lyrics view. Hangul pronunciation shows under each Japanese line.

Requires Android 10+. Free Spotify accounts work. Installs as a separate app (`app.myspoti.hangul`) from the original Myspoti.

## Updates

- **hangul.5 and later**: the app checks for new versions and prompts you to update.
- **hangul.4 and earlier**: download the new APK and install it over the existing app. No need to uninstall.

## Login

> [!TIP]
> If social login doesn't work, sign in with **email + Spotify password**.

- Google login has failed in testing. Apple and Facebook are untested.
- Use your Spotify account password, not your Google or Apple password. You can set one [here](https://www.spotify.com/password-reset/).
- If you used Apple's Hide My Email, sign in with the relay address Apple created.
- Facebook only works for accounts that are already linked.

## Features

- Matches Haneum/Namuwiki pronunciation to lyrics line by line (used only when 85%+ of Japanese lines match)
- Remembers matched sources for faster display next time
- Falls back to searching without feat., by lead artist, or with cleaned-up cover titles
- Retries failed lookups (up to 3 times, 2 s apart)
- Automatic pronunciation for missing lines using an on-device dictionary (off by default)
- Copy song title (long-press the title in the lyrics view)

For Myspoti's original features, see the [original README](README_UPSTREAM.md).

## Known issues

- Songs without Hangul pronunciation online won't show any.
- Automatic pronunciation may not match how kanji are actually sung.
- Google login may not work (see Login above).

## Development

<details>
<summary>Build</summary>

Requires JDK 17 and Android SDK 37.

```powershell
.\build-hangul.ps1 -JavaHome 'path to JDK' -AndroidSdk 'path to Android SDK'
```

Release builds need signing config (`MYSPOTI_KEYSTORE`, `MYSPOTI_KEYSTORE_PASSWORD`, `MYSPOTI_KEY_ALIAS`, `MYSPOTI_KEY_PASSWORD` env vars or a signing properties file). The version is set in `release.json`.

| Path | Role |
| --- | --- |
| `base/` | Myspoti source + Hangul pronunciation changes |
| `base/app/.../data/api/HangulReadingEngine.java` | Source lookup and matching |
| `base/app/.../data/api/HangulReadings.kt` | App integration, caching |
| `build-hangul.ps1` | Release APK build |

See [upstream maintenance](docs/MAINTENANCE.md) (Korean) for release detection, isolated merge candidates and verified promotion. Package/signing contract changes and unresolved conflicts block promotion.

</details>

## License

GPL-3.0, same as the original Myspoti. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for third-party components.
