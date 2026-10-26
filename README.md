# Myspoti 한글 (Android)

**한국어** | [English](README.en.md)

안드로이드 Spotify 클라이언트 [Myspoti](https://github.com/kota-zo/myspoti)에 **일본어 노래의 한글 발음 가사**를 더한 수정판입니다. 음악 재생과 한글 발음 표시를 앱 하나에서 하며, PC나 별도 서버가 필요하지 않습니다.

> [!NOTE]
> 현재 버전은 **2.0.1-hangul.4 베타**입니다. 빌드와 서명은 확인했지만 이번 APK의 휴대폰 설치와 실제 재생 검증은 아직 진행 중입니다.

**[⬇ APK 받기 (Myspoti-Hangul-2.0.1-hangul.4.apk)](https://github.com/ForUltraplayer/SPOTIFY_ANDROID/releases/latest)**

## 한눈에 보기

| 항목 | 내용 |
| --- | --- |
| 하는 일 | Myspoti 가사 화면에 일본어 원문과 한글 발음을 함께 표시 |
| 발음 출처 | 한음, 나무위키에 사람이 작성한 기존 한글 발음 |
| 기반 앱 | [Myspoti](https://github.com/kota-zo/myspoti) (GPL-3.0), 기준 커밋 `d99d09d` |
| 대상 | Android 10 이상 |
| 앱 식별자 | `app.myspoti.hangul` (원본 Myspoti와 별개 앱으로 설치) |
| 계정 | 본인의 Spotify 계정 (무료 계정 가능) |
| 비용 | 유료 API 키 없이 사용 |
| PC판 | 별도 프로젝트 [SPOTIFY_PC](https://github.com/ForUltraplayer/SPOTIFY_PC) |

## Myspoti란

Myspoti는 kota-zo가 만든 오픈소스 안드로이드 Spotify 앱입니다. 앱 안에 숨겨진 Spotify 웹 플레이어로 음악을 재생하고, 그 위에 자체 화면을 그립니다. 본인 Spotify 계정으로 로그인하며 무료 계정으로도 광고 없이 재생하는 것을 목표로 합니다. 원본 기능은 [원본 README](README_UPSTREAM.md)에 정리되어 있습니다.

이 수정판은 Myspoti의 재생, 라이브러리, 가사 기능을 그대로 두고 **가사 화면에 한글 발음 줄을 추가**합니다.

## 추가 기능

**발음 표시**

- 한음, 나무위키의 일본어 가사와 한글 발음을 재생 중인 가사와 줄 단위로 대조
- 원문 일본어 줄의 85% 이상, 최소 5 줄이 맞는 자료만 사용 (일본어가 3~4 줄인 곡은 모든 줄 일치 필요)
- 원문 대조를 통과한 출처를 기억하고 다음 재생 때도 다시 검증

**가사를 못 찾을 때의 보완**

- 커버곡 제목의 부가 표기, 반복 횟수 표기 정리와 한글 제목 문서 검색
- 제목의 feat. 표기를 빼거나 공동 가수 중 대표 가수로 재검색 (일본어 쉼표가 들어간 그룹명과 전각 제목은 보존)
- 가수, 앨범, 길이가 다른 녹음의 가사 배제
- 가사 서버의 요청 제한과 일시 오류 구분

**선택 기능**

- 자동 발음: 발음 자료에 없는 줄을 기기 안 사전으로 변환 (기본값 꺼짐, 자동 생성 줄 수는 가사 화면에 표시)
- 제목 복사: 가사 화면의 곡 제목 길게 누르기 또는 메뉴의 곡 제목 복사

## 설치

1. [배포 페이지](https://github.com/ForUltraplayer/SPOTIFY_ANDROID/releases/latest)에서 `Myspoti-Hangul-2.0.1-hangul.4.apk`를 받습니다.
2. APK를 열고, 안드로이드가 요청하면 브라우저나 파일 관리자의 앱 설치를 한 번 허용합니다.
3. **Log in with Spotify**를 눌러 로그인합니다. (로그인 방식은 아래 안내 참고)
4. 곡을 재생하고 가사 화면을 열면 일본어 줄 아래에 한글 발음이 표시됩니다.

새 버전은 이 저장소의 배포 페이지에서 받아 기존 앱 위에 덮어 설치합니다. 배포 APK는 이전 버전과 같은 키로 서명하므로 덮어 설치할 수 있습니다. 직접 빌드한 APK처럼 서명이 다른 앱은 덮어 설치할 수 없습니다.

## 로그인 안내

> [!TIP]
> 소셜 로그인이 되지 않으면 **이메일 주소와 Spotify용 비밀번호**로 로그인하세요. 이전 기기 시험에서 로그인과 재생이 확인된 방식입니다.

앱은 내부 웹 화면에서 Spotify 공식 로그인 페이지를 엽니다. 소셜 로그인 버튼이 보여도 정상 동작이 확인된 것은 아닙니다.

| 로그인 방식 | 확인 상태 |
| --- | --- |
| 이메일 + Spotify 비밀번호 | 이전 기기 시험에서 로그인과 재생 성공, 현재 APK는 재검증 대기 |
| Google | 이전 기기 시험에서 실패, 현재 APK는 재검증 안 함 |
| Apple | 실제 기기 미검증 |
| Facebook | 실제 기기 미검증 |

- Google이나 Apple 계정 비밀번호가 아니라 **Spotify 계정에 설정한 비밀번호**를 입력합니다. 없다면 [Spotify 비밀번호 설정](https://www.spotify.com/password-reset/)에서 만들 수 있습니다.
- Apple의 이메일 가리기를 쓴 계정은 Apple이 만든 중계 이메일 주소로 로그인합니다. ([Apple 로그인 안내](https://support.spotify.com/us/article/apple-login-help/))
- Facebook은 이미 연결되어 있는 계정만 사용할 수 있습니다. Spotify가 신규 Facebook 연결을 지원하지 않습니다. ([Facebook 로그인 안내](https://support.spotify.com/us/article/facebook-login-help/))

## 한계와 주의 사항

- 모든 곡에 한글 발음 자료가 있지는 않습니다. 사이트 응답, 문서 구조, 가사 표기 차이로 실패할 수 있습니다.
- 자동 발음은 실제 노래의 한자 읽기와 다를 수 있습니다.
- 베타 버전입니다. 휴대폰에서의 업데이트 설치, 로그인 유지, 실제 재생 확인이 남아 있습니다.

## 저장 데이터

| 데이터 | 위치 | 보관 기준 |
| --- | --- | --- |
| 원문 가사 | Myspoti 기존 저장 방식 | 원본 앱 기준 |
| 작성된 한글 발음 | 앱 내부 `hangul_readings_cache` 폴더의 JSON | 최대 500 곡, 30 일 |
| 확인한 출처와 설정 | 앱 비공개 설정 저장소 | 앱 삭제 전까지 |

자동으로 만든 발음은 사람이 작성한 발음 캐시에 덮어쓰지 않습니다. 저장 자료와 로그인 정보는 이 저장소에 포함하지 않습니다.

## 검증 현황

| 항목 | 결과 |
| --- | --- |
| Java 발음 엔진 검사 | 22 개 통과 |
| 원문 조회 검사 | 26 개 통과 |
| release APK 빌드 (Kotlin, Compose, R8) | 통과 |
| 서명 | 이전 버전(1085)과 같은 서명자 확인, 새 버전 코드 1086 |
| 실제 문서 연결 (PC JVM) | 모니터링 85/85 줄, Execution Clap(처형박수) 57/60 줄 |
| 휴대폰 설치, 로그인 유지, 재생 | 대기 |

실제 문서 연결 결과는 PC에서 같은 엔진을 실행한 결과이며 휴대폰 속도 측정이 아닙니다. 상세 범위는 [검증 기록](docs/VERIFICATION.md)에 있습니다.

## 개발자용

<details>
<summary>빌드와 서명</summary>

JDK 17과 Android SDK 37을 준비하고 다음 명령을 실행합니다.

```powershell
.\build-hangul.ps1 -JavaHome 'JDK 설치 경로' -AndroidSdk 'Android SDK 경로'
```

서명 환경 변수 `MYSPOTI_KEYSTORE`, `MYSPOTI_KEYSTORE_PASSWORD`, `MYSPOTI_KEY_ALIAS`, `MYSPOTI_KEY_PASSWORD`가 있으면 해당 키로 서명합니다. 없으면 개발용 키를 사용하므로 배포 APK로 설치한 앱을 덮어 설치할 수 없습니다. 개인 키와 자격 정보는 저장소에 넣지 않습니다.

| 경로 | 역할 |
| --- | --- |
| `base/` | 원본 Myspoti 소스와 한글 발음 수정 |
| `base/app/.../data/api/HangulReadingEngine.java` | 발음 자료 조회와 원문 대조 엔진 |
| `base/app/.../data/api/HangulReadings.kt` | 앱과 발음 엔진 연결, 캐시 |
| `build-hangul.ps1` | 버전 지정 release APK 빌드 |
| `docs/HANGUL_PLAN.md` | 한글 발음 통합 계획 |

원본의 자동 릴리스와 외부 알림 워크플로는 `.github/workflows-upstream`에 참고용으로 보관하며 자동 실행하지 않습니다.

</details>

## 출처와 라이선스

원본 Myspoti의 GPL-3.0 라이선스와 저작권 고지를 유지합니다. 원본 이력과 전체 수정 소스를 이 저장소에 보존합니다. 추가 의존성은 [외부 구성 요소](THIRD_PARTY_NOTICES.md)에 정리되어 있습니다. 명시적 배포 라이선스를 확인하지 못한 ivLyrics Android 소스는 포함하지 않습니다.
