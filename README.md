# Myspoti + 한글 독음 (Android)

**한국어** | [English](README.en.md)

안드로이드 Spotify 앱 [Myspoti](https://github.com/kota-zo/myspoti)에 일본어 노래의 한글 발음 표시를 추가한 버전입니다. 한음, 나무위키에 올라와 있는 한글 발음을 가져와 가사와 줄 단위로 맞춥니다. PC나 별도 서버 없이 앱 하나로 동작합니다.

현재 2.0.1-hangul.10 정식 버전입니다.

> 개인이 취미로 만든 비공식 프로젝트입니다. 유지보수와 업데이트를 보장하지 않으니 [면책](#면책) 내용을 먼저 읽어 주세요.

**[⬇ APK 다운로드 (v2.0.1-hangul.10)](https://github.com/ForUltraplayer/SPOTIFY_ANDROID/releases/tag/v2.0.1-hangul.10)**

PC 버전: [SPOTIFY_PC](https://github.com/ForUltraplayer/SPOTIFY_PC)

## 설치

1. `Myspoti-Hangul-2.0.1-hangul.10.apk`를 받아서 엽니다. 안드로이드가 물어보면 앱 설치를 허용합니다.
2. **Log in with Spotify**로 로그인합니다.
3. 곡을 재생하고 가사 화면을 열면 일본어 줄 아래에 한글 발음이 나옵니다.

Android 10 이상, 무료 Spotify 계정도 됩니다. 원본 Myspoti와는 별개 앱(`app.myspoti.hangul`)으로 설치됩니다.

## 업데이트

- **hangul.9 이후**: 앱이 새 버전을 확인해서 업데이트를 안내합니다.
- **hangul.5 이하**: 이번 한 번만 hangul.10 APK를 받아서 기존 앱 위에 그대로 설치합니다. 앱을 지울 필요 없습니다. 다음부터는 앱이 알려 줍니다.

hangul.5까지의 앱은 시험판 릴리스만 확인하기 때문에 정식으로 나온 hangul.9 이후 버전을 안내하지 못합니다.

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
- 자동 발음: 자료에 없는 줄을 기기 안 사전으로 변환 (기본 꺼짐). 한음, 나무위키 표기 관례(장음 생략, っ는 ㅅ 받침, ん은 ㄴ 받침)에 맞춥니다.
- 곡 제목 복사 (가사 화면에서 제목 길게 누르기)
- 곡별 가사 싱크 조절 (가사 화면 메뉴의 **가사 싱크 조절**, 0.25초·1초 단위, 앞뒤 60초)
- YouTube 링크를 Spotify 플레이리스트에 넣어 Spotify 곡과 함께 재생 (아래 참고)
- 휴대폰 목록 파일 내보내기와 가져오기

## YouTube 링크와 혼합 목록

**추가하기**

- Spotify 플레이리스트 화면의 「+」에서 **YouTube 링크 추가**
- 플레이리스트 「⋮」 메뉴의 **YouTube 링크 추가**
- 라이브러리 「+」의 **YouTube 링크 추가** (휴대폰 목록이나 Spotify 플레이리스트를 고름)
- 휴대폰 목록 「⋮」의 **YouTube 링크 추가**

YouTube, YouTube Music 주소를 넣습니다. **가사 검색용 곡 제목**과 **가수**를 넣으면 그 정보로 가사를 찾고, 비워 두면 영상 제목과 채널을 씁니다. 커버 영상이면 원곡 제목과 가수를 넣는 편이 가사를 잘 찾습니다. 비공개이거나 삭제된 영상은 추가할 때 안내합니다.

**순서와 재생**

- YouTube 링크와 휴대폰 파일은 Spotify 곡 사이에 섞여 한 목록으로 보입니다. 처음에는 Spotify 곡 뒤에 붙습니다.
- 정렬 메뉴(⇅)의 **순서 직접 편집 (이 기기)**에서 위아래 버튼으로 옮기고, **Spotify 순서로**로 되돌립니다.
- 순서는 이 휴대폰에만 저장됩니다. Spotify 서버의 플레이리스트 순서는 바뀌지 않고, Spotify 곡끼리만 옮겨도 마찬가지입니다.
- YouTube는 영상 없이 소리만 재생합니다. 다음 곡, 이전 곡, 셔플, 반복이 화면 순서를 따릅니다.
- 항목을 왼쪽으로 밀면 목록에서 뺍니다.

**목록 파일**

- 휴대폰 목록 「⋮」의 **파일로 내보내기**로 JSON 파일을 저장하고, 라이브러리 「+」의 **목록 파일 가져오기**로 새 목록을 만듭니다. 기존 목록은 덮어쓰지 않습니다.
- 다른 휴대폰에서는 YouTube 링크만 재생됩니다. 휴대폰 파일은 그 휴대폰 안의 주소라서 재생되지 않습니다.

Myspoti 원래 기능은 [원본 README](README_UPSTREAM.md)를 참고하세요.

## 알려진 문제

- 한글 발음 자료가 없는 곡은 표시되지 않습니다.
- 자동 발음은 실제 노래의 한자 읽기와 다를 수 있습니다.
- Google 로그인이 안 될 수 있습니다 (위 로그인 참고).
- YouTube 링크와 혼합 순서는 이 휴대폰에만 있습니다. 다른 기기나 Spotify 공식 앱에는 보이지 않습니다.
- 로그인이 필요한 영상, 연령 제한 영상은 재생되지 않을 수 있습니다. YouTube 음질은 Spotify 음질 설정과 무관합니다.
- 블루투스, 잠금 화면 버튼으로 혼합 목록을 오가는 동작은 따로 확인하지 않았습니다.

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

## 면책

- 개인이 취미로 만든 비공식 프로젝트이며, 있는 그대로 제공하고 어떤 보증도 하지 않습니다. 유지보수, 업데이트, 문의 답변은 시간이 날 때 하며 예고 없이 멈출 수 있습니다. Spotify가 바뀌면 일부 기능이 언제든 동작하지 않을 수 있습니다.
- 이 프로젝트는 Spotify AB와 관련이 없으며 Spotify의 승인이나 보증을 받지 않았습니다. Spotify는 Spotify AB의 상표입니다.
- 원본 Myspoti의 광고 차단과 다운로드 확장 기능이 그대로 들어 있습니다. 광고 차단은 [Spotify 이용 약관](https://www.spotify.com/kr-ko/legal/end-user-agreement/)에 어긋나며 Spotify가 계정을 제한할 수 있습니다. 사용에 따른 책임은 사용자에게 있습니다.
- 가사와 한글 발음 자료의 권리는 각 권리자에게 있습니다. 이 프로젝트는 가사를 배포하지 않고 사용자의 기기에서만 조회하고 저장합니다.
- YouTube 재생에는 YouTube 이용 약관이 적용됩니다.
- 권리자가 요청하면 해당 부분을 내립니다. [이슈](https://github.com/ForUltraplayer/SPOTIFY_ANDROID/issues)로 연락해 주세요.

## 라이선스

원본 Myspoti와 같은 GPL-3.0입니다. 외부 구성 요소는 [THIRD_PARTY_NOTICES.md](THIRD_PARTY_NOTICES.md)를 참고하세요.
