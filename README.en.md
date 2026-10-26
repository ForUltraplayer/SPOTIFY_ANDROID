# Myspoti Hangul (Android)

[한국어](README.md) | **English**

A modified build of the Android Spotify client [Myspoti](https://github.com/kota-zo/myspoti) that adds **Korean (Hangul) pronunciation for Japanese song lyrics**. Playback and Hangul pronunciation work in one app, with no PC or separate server needed.

> [!NOTE]
> The current version is **2.0.1-hangul.5 beta**. The build and signature are verified, but installing this APK on a phone and testing real playback are still in progress.

**[⬇ Download the APK (Myspoti-Hangul-2.0.1-hangul.5.apk)](https://github.com/ForUltraplayer/SPOTIFY_ANDROID/releases/tag/v2.0.1-hangul.5)**

## At a glance

| Item | Details |
| --- | --- |
| What it does | Shows Japanese lyrics with Hangul pronunciation in Myspoti's lyrics view |
| Pronunciation source | Existing human-written Hangul pronunciation on Haneum and Namuwiki |
| Base app | [Myspoti](https://github.com/kota-zo/myspoti) (GPL-3.0), base commit `d99d09d` |
| Target | Android 10 or later |
| App ID | `app.myspoti.hangul` (installs as a separate app from the original Myspoti) |
| Account | Your own Spotify account (free accounts work) |
| Cost | No paid API key needed |
| PC version | Separate project: [SPOTIFY_PC](https://github.com/ForUltraplayer/SPOTIFY_PC) |

## What is Myspoti

Myspoti is an open-source Android Spotify app by kota-zo. It plays music through the Spotify Web Player hidden inside the app and draws its own screens on top. You sign in with your own Spotify account, and it aims to play without ads even on a free account. The original features are described in the [original README](README_UPSTREAM.md).

This build keeps Myspoti's playback, library and lyrics features as they are and **adds Hangul pronunciation lines to the lyrics view**.

## Added features

**Pronunciation display**

- Matches Japanese lyrics and Hangul pronunciation from Haneum and Namuwiki line by line against the playing lyrics
- Uses a source only when it matches at least 85% of the Japanese lines and at least 5 lines (songs with only 3 to 4 Japanese lines must match every line)
- Remembers sources that passed the match and checks them again on the next playback

**Fallbacks when lyrics are not found**

- Cleans up extra tags and repeat counts in cover titles, and searches pages registered under Korean titles
- Retries without the feat. part of the title, or with the lead artist of a joint credit (group names with Japanese commas and full-width titles are kept)
- Rejects lyrics from recordings with a different artist, album or length
- Tells lyrics-server rate limits apart from temporary errors
- Retries failed pronunciation lookups up to three additional times, waiting two seconds between attempts, with a 60-second overall budget; changing songs cancels the request

**Optional features**

- Automatic pronunciation: converts lines missing from the source with an on-device dictionary (off by default; the number of generated lines is shown in the lyrics view)
- Title copy: long-press the song title in the lyrics view, or use Copy song title in the menu

## Installation

1. Download `Myspoti-Hangul-2.0.1-hangul.5.apk` from the [releases page](https://github.com/ForUltraplayer/SPOTIFY_ANDROID/releases/tag/v2.0.1-hangul.5).
2. Open the APK, and when Android asks, allow installs from your browser or file manager once.
3. Tap **Log in with Spotify** and sign in. (See the login notes below.)
4. Play a song and open the lyrics view. Hangul pronunciation appears under each Japanese line.

To update, download the new version from this repository's releases page and install it over the existing app. Release APKs are signed with the same key as previous versions, so they install over them. An app signed with a different key, such as one you built yourself, cannot be updated this way.

**Keep the existing app installed and open the APK to update it.** The app ID is unchanged and the version code increases from 1086 to 1087. Install this APK manually when updating from 2.0.1-hangul.4 or earlier. Starting with 2.0.1-hangul.5, the automatic update check, when enabled, checks this repository for newer beta releases after app launch. Follow the in-app prompts to download and install an update; Android requires installation confirmation.

## Login notes

> [!TIP]
> If social login does not work, sign in with **your email address and your Spotify password**. This method signed in and played music in an earlier device test.

The app opens Spotify's official login page in an in-app web view. A visible social login button does not mean that login method has been verified.

| Login method | Status |
| --- | --- |
| Email + Spotify password | Login and playback worked in an earlier device test; re-test with the current APK pending |
| Google | Failed in an earlier device test; not re-tested with the current APK |
| Apple | Not verified on a real device |
| Facebook | Not verified on a real device |

- Enter **the password set for your Spotify account**, not your Google or Apple password. If you do not have one, you can set it on [Spotify password reset](https://www.spotify.com/password-reset/).
- If you used Apple's Hide My Email, sign in with the relay email address Apple created. ([Apple login help](https://support.spotify.com/us/article/apple-login-help/))
- Facebook only works for accounts that are already linked. Spotify no longer supports linking new Facebook accounts. ([Facebook login help](https://support.spotify.com/us/article/facebook-login-help/))

## Limitations

- Not every song has Hangul pronunciation available. Lookups can fail because of site responses, page structure or differences in how lyrics are written.
- Automatic pronunciation can differ from how kanji are actually sung.
- This is a beta. Installing the update on a phone, keeping the login across updates and real playback still need to be checked.

## Stored data

| Data | Location | Retention |
| --- | --- | --- |
| Original lyrics | Myspoti's existing storage | As in the original app |
| Human-written Hangul pronunciation | JSON files in the app's internal `hangul_readings_cache` folder | Up to 500 songs, 30 days |
| Verified sources and settings | The app's private preferences | Until the app is removed |

Automatically generated pronunciation never overwrites the cache of human-written pronunciation. Stored data and login information are not included in this repository.

## Verification status

| Item | Result |
| --- | --- |
| Java pronunciation engine tests | 22 passed |
| Lyrics lookup tests | 26 passed |
| Pronunciation retry and failure-cache tests | 9 passed |
| Update version and APK-selection tests | 6 passed |
| Release APK build (Kotlin, Compose, R8) | Passed |
| Signature | Same signer as the previous version (1086) confirmed; new version code 1087 |
| Matching against real pages (PC JVM) | Monitoring 85/85 lines, Execution Clap 57/60 lines |
| Phone install, login persistence, playback | Pending |

The matching results come from running the same engine on a PC and are not phone performance measurements. Details are in the [verification record](docs/VERIFICATION.md) (Korean).

## For developers

<details>
<summary>Build and signing</summary>

Prepare JDK 17 and Android SDK 37, then run:

```powershell
.\build-hangul.ps1 -JavaHome 'path to JDK' -AndroidSdk 'path to Android SDK'
```

Provide the signing environment variables `MYSPOTI_KEYSTORE`, `MYSPOTI_KEYSTORE_PASSWORD`, `MYSPOTI_KEY_ALIAS` and `MYSPOTI_KEY_PASSWORD`, or the existing signing properties file. The script stops a release build when signing configuration is missing. Updating an app installed from a release APK requires the original key. The version and update repository are set in `release.json`. Private keys and credentials are never committed to this repository.

| Path | Role |
| --- | --- |
| `base/` | Original Myspoti source with the Hangul pronunciation changes |
| `base/app/.../data/api/HangulReadingEngine.java` | Engine that looks up pronunciation sources and matches them against the original lyrics |
| `base/app/.../data/api/HangulReadings.kt` | Connects the app to the engine, caching |
| `build-hangul.ps1` | Release APK build with the version set |
| `docs/HANGUL_PLAN.md` | Hangul pronunciation integration plan (Korean) |

The original automated release and notification workflows are kept for reference in `.github/workflows-upstream` and do not run automatically.

</details>

## Credits and license

The GPL-3.0 license and copyright notices of the original Myspoti are kept. The original history and the full modified source are preserved in this repository. Additional dependencies are listed in [third-party components](THIRD_PARTY_NOTICES.md). The ivLyrics Android source is not included, because no explicit distribution license for it could be confirmed.
