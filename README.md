# Myspoti + 한글 독음 (Android)

**한국어** | [English](README.en.md)

안드로이드 Spotify 앱 [Myspoti](https://github.com/kota-zo/myspoti)에 일본어 노래의 한글 발음 표시를 추가한 버전입니다. 한음, 나무위키에 올라와 있는 한글 발음을 가져와 가사와 줄 단위로 맞춥니다. PC나 별도 서버 없이 앱 하나로 동작합니다.

현재 2.0.1-hangul.5 베타입니다.

**[⬇ APK 다운로드 (v2.0.1-hangul.5)](https://github.com/ForUltraplayer/SPOTIFY_ANDROID/releases/tag/v2.0.1-hangul.5)**

PC 버전: [SPOTIFY_PC](https://github.com/ForUltraplayer/SPOTIFY_PC)

## 설치

1. `Myspoti-Hangul-2.0.1-hangul.5.apk`를 받아서 엽니다. 안드로이드가 물어보면 앱 설치를 허용합니다.
2. **Log in with Spotify**로 로그인합니다.
3. 곡을 재생하고 가사 화면을 열면 일본어 줄 아래에 한글 발음이 나옵니다.

Android 10 이상, 무료 Spotify 계정도 됩니다. 원본 Myspoti와는 별개 앱(`app.myspoti.hangul`)으로 설치됩니다.

## 업데이트

- **hangul.5 이후**: 앱이 새 버전을 확인해서 업데이트를 안내합니다.
- **hangul.4 이하**: 새 APK를 받아서 기존 앱 위에 그대로 설치합니다. 앱을 지울 필요 없습니다.

## 로그인

> [!TIP]
> 소셜 로그인이 안 되면 **이메일 + Spotify 비밀번호**로 로그인하세요.

- Google 로그인은 실패한 적이 있고, Apple과 Facebook은 확인하지 않았습니다.
- 비밀번호는 Google이나 Apple 비밀번호가 아니라 Spotify 계정 비밀번호입니다. 없으면 [여기서](https://www.spotify.com/password-reset/) 만들 수 있습니다.
- Apple 이메일 가리기를 썼다면 Apple이 만든 중계 이메일로 로그인합니다.
- Facebook은 이미 연결된 계정만 쓸 수 있습니다.

## 기능

- 한음, 나무위키 한글 발음을 가사와 줄 단위로 매칭 (일본어 줄의 85% 이상 일치할 때만 사용)
- 한 번 찾은 자료는 기억해서 다음 재생 때 바로 표시
- 가사를 못 찾으면 feat. 제거, 대표 가수, 커버곡 제목 정리 등으로 재검색
- 조회 실패 시 자동 재시도 (2초 간격 최대 3회)
- 자동 발음: 자료에 없는 줄을 기기 안 사전으로 변환 (기본 꺼짐)
- 곡 제목 복사 (가사 화면에서 제목 길게 누르기)

Myspoti 원래 기능은 [원본 README](README_UPSTREAM.md)를 참고하세요.

## 알려진 문제

- 한글 발음 자료가 없는 곡은 표시되지 않습니다.
- 자동 발음은 실제 노래의 한자 읽기와 다를 수 있습니다.
- Google 로그인이 안 될 수 있습니다 (위 로그인 참고).

## 개발

<details>
<summary>빌드</summary>

JDK 17, Android SDK 37이 필요합니다.

```powershell
.\build-hangul.ps1 -JavaHome 'JDK 경로' -AndroidSdk 'Android SDK 경로'
```

release 빌드에는 서명 설정이 필요합니다 (`MYSPOTI_KEYSTORE`, `MYSPOTI_KEYSTORE_PASSWORD`, `MYSPOTI_KEY_ALIAS`, `MYSPOTI_KEY_PASSWORD` 환경 변수 또는 서명 속성 파일). 버전은 `release.json`에서 지정합니다.

| 경로 | 역할 |
| --- | --- |
| `base/` | Myspoti 소스 + 한글 발음 수정 |
| `base/app/.../data/api/HangulReadingEngine.java` | 발음 자료 조회, 원문 대조 |
| `base/app/.../data/api/HangulReadings.kt` | 앱 연결, 캐시 |
| `build-hangul.ps1` | release APK 빌드 |

원본 릴리스 감지, 별도 병합 후보 검사와 검증 후 반영 명령은 [원본 유지 관리](docs/MAINTENANCE.md)를 참고하세요. 앱 식별자와 서명 설정이 바뀌거나 충돌이 남으면 반영을 중단합니다.

</details>

## 라이선스

원본 Myspoti와 같은 GPL-3.0입니다. 외부 구성 요소는 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)를 참고하세요.
