# Myspoti + Hangul Pronunciation (Android)

[한국어](README.md) | **English**

A build of the Android Spotify app [Myspoti](https://github.com/kota-zo/myspoti) that adds Korean (Hangul) pronunciation for Japanese song lyrics. Pronunciation is pulled from Haneum and Namuwiki and matched line by line. Everything runs in the app, no PC or server needed.

Current version: 2.0.1-hangul.9.

**[⬇ Download APK (v2.0.1-hangul.9)](https://github.com/ForUltraplayer/SPOTIFY_ANDROID/releases/tag/v2.0.1-hangul.9)**

PC version: [SPOTIFY_PC](https://github.com/ForUltraplayer/SPOTIFY_PC)

## Install

1. Download and open `Myspoti-Hangul-2.0.1-hangul.9.apk`. Allow app installs if Android asks.
2. Sign in with **Log in with Spotify**.
3. Play a song and open the lyrics view. Hangul pronunciation shows under each Japanese line.

Requires Android 10+. Free Spotify accounts work. Installs as a separate app (`app.myspoti.hangul`) from the original Myspoti.

## Updates

- **hangul.9 and later**: the app checks for new versions and prompts you to update.
- **hangul.5 and earlier**: download the hangul.9 APK once and install it over the existing app. No need to uninstall. From then on, the app tells you about updates.

Apps up to hangul.5 only check pre-releases, so they can't see hangul.9, which is a regular release.

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
- Automatic pronunciation for missing lines using an on-device dictionary (off by default), following Haneum/Namuwiki conventions (long vowels dropped, っ as final ㅅ, ん as final ㄴ)
- Copy song title (long-press the title in the lyrics view)
- Per-song lyric sync adjustment (**가사 싱크 조절** in the lyrics menu, 0.25 s and 1 s steps, up to 60 s either way)
- YouTube links in Spotify playlists, played together with the Spotify songs (see below)
- Export and import phone lists as files

## YouTube links and mixed playlists

**Adding a link**

- The Spotify playlist page's + → **YouTube 링크 추가** (Add YouTube link)
- The playlist's ⋮ menu → **YouTube 링크 추가**
- The library's + → **YouTube 링크 추가** (then pick a phone list or a Spotify playlist)
- A phone list's ⋮ → **YouTube 링크 추가**

Paste a YouTube or YouTube Music address. The optional title and artist are used to look up lyrics; left empty, the video's title and channel are used. For cover videos, entering the original title and artist finds lyrics more reliably. Private or removed videos are reported when you add them.

**Order and playback**

- YouTube links and phone files show among the Spotify songs as one list. They start after the Spotify songs.
- Use the sort menu (⇅) → **순서 직접 편집 (이 기기)** (edit order on this device) and the up/down buttons; **Spotify 순서로** restores Spotify's order.
- The order is kept on this phone only. The playlist's order on Spotify never changes, even when you only move Spotify songs.
- YouTube plays audio only, no video. Next, Previous, shuffle and repeat follow the order on screen.
- Swipe an item to the left to remove it from the list.

**List files**

- A phone list's ⋮ → **파일로 내보내기** saves it as a JSON file; the library's + → **목록 파일 가져오기** creates a new list from one. Existing lists are never overwritten.
- On another phone only the YouTube links play; phone files point to the original phone's storage.

For Myspoti's original features, see the [original README](README_UPSTREAM.md).

## Known issues

- Songs without Hangul pronunciation online won't show any.
- Automatic pronunciation may not match how kanji are actually sung.
- Google login may not work (see Login above).
- YouTube links and the mixed order live on this phone only. Other devices and the official Spotify app don't see them.
- Videos that need sign-in or are age-restricted may not play. YouTube audio quality is not affected by Spotify's quality setting.
- Moving through mixed playlists with Bluetooth or lock-screen buttons hasn't been tested separately.

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

## Disclaimer

- This project is not affiliated with, endorsed by or sponsored by Spotify AB. Spotify is a trademark of Spotify AB.
- The ad blocking and download-source extensions of the original Myspoti are kept as is. Blocking ads goes against the [Spotify Terms of Use](https://www.spotify.com/us/legal/end-user-agreement/), and Spotify may restrict accounts that do it. Use it at your own risk.
- Lyrics and Hangul readings belong to their respective rights holders. This project does not distribute lyrics; they are only looked up and cached on your own device.
- YouTube playback is subject to the YouTube Terms of Service.
- Parts will be taken down on request from a rights holder. Please reach out through [Issues](https://github.com/ForUltraplayer/SPOTIFY_ANDROID/issues).

## License

GPL-3.0, same as the original Myspoti. See [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md) for third-party components.
