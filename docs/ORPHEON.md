# 원본 Orpheon 전환 대응

원본 Myspoti는 2026-11-12에 이름을 Orpheon으로 바꾸고 저장소를 `kota-zo/Orpheon`으로 옮겼다. 이 문서는 한글판이 Orpheon을 따라갈지에 대한 결정과 근거를 정리한다.

## 결정: Spotify 기반 2.4.x 유지

2026-11-18에 소유자가 정했다. 한글판은 Orpheon을 따라가지 않고 Myspoti 2.4.0(`95a24e7e`) 기반의 Spotify 앱으로 계속 간다. Spotify 쪽 변경으로 로그인·재생 등에 문제가 생기면 그때 이 저장소에서 직접 고친다.

이유는 Orpheon 정식 1.0.0(2026-11-17 공개)이 Spotify 지원을 뺐기 때문이다. 한글판은 Spotify 로그인과 재생목록 위에 한글 발음을 얹은 앱이라, Orpheon을 따라가면 Spotify 기능을 잃는다.

| 항목 | Myspoti 2.4.0(한글판 기반) | Orpheon 1.0.0 |
|---|---|---|
| 음악 계정 | Spotify 로그인 | YouTube Music 또는 계정 없음 |
| 곡 목록과 검색 | Spotify | 공개 카탈로그(Deezer, iTunes, MusicBrainz) |
| 재생 | Spotify와 확장 | 확장, 휴대폰 파일, 라이브 라디오만 |
| 코드 | `api-spotify` 모듈 있음(경로에 spotify가 들어간 파일 236개) | `api-spotify` 삭제, `api-ytmusic`·`catalog` 추가(같은 기준 3개) |

2026-11-18에 확인한 원본의 상태는 다음과 같다.

- 예전 기록 전체를 커밋 하나("Orpheon 1.0.0", `773dcc7b`)로 합치고 Myspoti 2.x 릴리스와 태그를 모두 지웠다. 남은 릴리스는 Orpheon v1.0.0 하나다. 저장소는 보관(archive) 처리되지 않았다.
- README는 "예전 이름은 Myspoti"라고 적고, Myspoti 사용자에게 2.4.1로 업데이트해 Orpheon을 설치하라고 안내한다. Spotify를 뺀 이유나 Myspoti 지원 종료를 밝힌 글은 README와 릴리스 노트에서 찾지 못했다. 원본 Discord는 확인하지 않았다.
- 릴리스 노트에 "콘텐츠를 담지 않고, 보호를 우회하거나 광고를 막는 확장은 스토어에서 받지 않는다"는 문장이 새로 있다. 약관·법적 이유로 방향을 바꾼 것으로 보이나 추정이다.

이 저장소에는 v2.4.0까지의 원본 기록 전체와 예전 태그가 남아 있어, Spotify 기반을 유지하는 데 필요한 소스는 모두 있다. 원본의 Spotify 쪽 수정은 더 받을 수 없다.

아래 "2026-11-17 계획"(아이콘, 3.0.0 번호, `myspoti-ex` 파일 이름)은 Orpheon을 따라갈 때를 전제로 했으므로 보류한다. 그 준비로 내보낸 2.4.1-hangul.2의 업데이트 확인 변경은 그대로 둬도 동작에 문제가 없다.

### 매일 검사의 처리

원본이 기록을 새로 시작한 뒤 매일 검사가 실패했다. 우리 기준 커밋과 원본 태그를 GitHub에서 비교하면 공통 조상이 없어 404가 나기 때문이다(2026-11-17 실행 `44072149731`). `scripts/maintain_upstream.py`가 이 경우를 "unrelated"로 보고하고, 시험 병합은 기준 커밋을 병합 기준(`--merge-base`)으로 지정해 계산하도록 고쳤다. 이 방식으로 계산한 v1.0.0과의 충돌은 27개 파일이다. Orpheon의 새 릴리스는 앞으로도 `upstream` 라벨 이슈로 알려지며, 참고용으로 보고 반영하지 않는다.

## 원본의 상태 (2026-11-17 확인)

| 원본 태그 | 날짜 | 내용 | 한글판 반영 |
|---|---|---|---|
| v2.4.1 | 2026-11-12 | Myspoti 사용자에게 Orpheon 설치를 권하는 안내만 추가 | 반영하지 않음 |
| v2.4.2-beta.1 | 2026-11-12 | v2.4.1과 같은 안내의 베타 | 반영하지 않음 |
| v1.0.0-beta.1 | 2026-11-12 | Orpheon 첫 베타, 원본 main(`70f323c5`) | 정식이 나올 때까지 보류 |

한글판 2.4.1-hangul.1은 원본 v2.4.1의 안내를 넣지 않았고, 원본 기준은 v2.4.0(`95a24e7e`)이다. 한글판 사용자를 한글 기능이 없는 다른 앱으로 보내지 않기 위해서다.

## Orpheon 1.0.0-beta.1의 변경 규모

v2.4.0 대비 원본 main은 커밋 2개, 파일 932개(추가 422, 수정 494, 삭제 10, 이름 변경 6)가 바뀌었다. 이름만 바뀐 것이 아니라 재생 엔진 동기화(`api-spotify/.../sync`), 광고 처리(`AdSchedule`, `PageRecoveryRules`), 첫 실행 흐름 등 기능 변경이 함께 들어 있다.

| 항목 | v2.4.0 | Orpheon 1.0.0-beta.1 |
|---|---|---|
| 앱 식별자 | `app.myspoti.stable`, 베타 `app.myspoti` | `app.orpheon`, 베타 `app.orpheon.beta` |
| 홈 화면 이름 | Myspoti | Orpheon |
| 버전 번호 | 2.4.0 | 1.0.0부터 다시 시작 |
| 업데이트 확인 | 베타는 목록, 정식은 latest만 조회 | 두 채널 모두 릴리스 목록 조회 |
| 코드 패키지 | `com.music.spotui`, 네임스페이스 `app.myspoti.engine` | 그대로 |

한글판 현재 소스(`5370d493` 이후)와 원본 main을 시험 병합하면 22개 파일이 충돌한다(내용 충돌 21, 양쪽 추가 1). v2.4.0 병합 때 12개였다.

| 영역 | 충돌 파일 |
|---|---|
| 빌드 설정 | `base/app/build.gradle.kts` |
| 업데이트 확인 | `UpdateChecker.kt`, `UpdateCheckerTest.kt` |
| 가사 | `LyricsApi.kt`, `ImmersiveLyricsPanel.kt` |
| 재생목록 화면 | `ContextScreen.kt`, `ImmersiveContextScreen.kt` |
| 문구 | 기본 `myspoti_strings.xml`, 9개 언어 `myspoti_strings.xml`, ko·ja `sources_strings.xml`, de `automix_session_strings.xml` |
| 문서 | `README.md`, `CHANGELOG.md` |

기본 문구는 91개가 새로 생기고 92개가 없어진다. 영어 기본 리소스에서 `Orpheon`이 들어간 줄은 53줄이다(`myspoti_strings.xml` 29, `extensions_strings.xml` 14 등, 번역하지 않는 항목 포함). 한국어와 일본어는 새 문구를 이 저장소에서 번역해야 한다.

## 2026-11-17 계획 (보류)

2026-11-17에 소유자가 정했다. Orpheon을 따라갈 때를 전제로 한 계획이며, 2026-11-18 결정으로 보류했다.

| 항목 | 결정 |
|---|---|
| 아이콘 | Myspoti 아이콘 유지. 원본이 바꾼 홈 화면 아이콘(`ic_launcher_foreground*.xml`, `ic_launcher_monochrome.xml`), 시작 아이콘(`splash_icon.xml`), TV 배너(`tv_banner.png`)는 병합할 때 지금 파일로 남긴다. 원본 아이콘은 임시(placeholder)라고 원본 커밋에 적혀 있다 |
| 버전 번호 | Orpheon 병합판부터 `3.0.0`으로 시작해 `3.0.1`처럼 올린다. 뒤의 `-hangul.N`은 붙이지 않는다. 원본 기준은 `release.json`의 `upstreamTag`에 남긴다 |
| 앱 식별자 | `app.myspoti.hangul` 유지(기존 설치본 위에 업데이트되려면 필수) |
| APK 파일 이름 | 3.0.0부터 `myspoti-ex-<버전>.apk` 하나만 올린다 |

## 계획 당시 정하지 않은 것

1. 홈 화면 이름: 지금처럼 Myspoti-ex로 둘지, 원본처럼 Orpheon 계열로 바꿀지.
2. 화면 문구의 원본 이름: 설정·안내 문구에 들어간 `Orpheon`을 그대로 둘지, 한글판 이름으로 바꿀지.

## `-hangul` 없는 버전으로 넘어가는 준비

2.4.1-hangul.1까지의 앱은 버전 이름에 `-hangul.`이 있는지로 한글판인지를 판단했다. 그래서 `3.0.0`처럼 꼬리를 빼면 업데이트 확인이 원본 정식 채널처럼 동작하고, APK도 `myspoti-<버전>.apk` 같은 원본식 이름만 찾는다. 이 문제를 3.0.0보다 먼저 나가는 2.4.1-hangul.2에서 고쳤다.

| 위치 | 2.4.1-hangul.2에서 바꾼 내용 |
|---|---|
| `UpdateChecker.isHangul` | 앱 식별자가 `app.myspoti.hangul`(디버그는 `.dev`)이면 버전 이름과 상관없이 한글판으로 본다 |
| `UpdateChecker.newest` | 한글판은 정식과 시험판 릴리스를 모두 받는다(버전 이름이 아닌 `isHangul` 기준) |
| `UpdateChecker.apkAsset` | 한글판은 `Myspoti-Hangul-<버전>.apk`와 `myspoti-ex-<버전>.apk` 중 있는 쪽을 받는다 |
| `base/app/build.gradle.kts`의 `beta` | `release.json`의 앱 식별자가 `app.myspoti.hangul`이면 지금처럼 베타 쪽으로 빌드해 진단 로그와 adb 명령줄을 남긴다 |
| `UpdateCheckerTest` | `3.0.0` 형식 버전과 `myspoti-ex` 파일 이름 사례를 추가했다 |

이미 설치된 앱이 3.0.0을 받으려면 3.0.0이 나오기 전에 2.4.1-hangul.2 이상으로 업데이트돼 있어야 한다. 옛 앱은 가장 새 릴리스 하나만 보는데, 2.4.1-hangul.1 이하는 `myspoti-ex-<버전>.apk`를 찾지 못하기 때문이다(2.0.1-hangul.10, 2.3.0-hangul.3, 2.4.x의 코드로 확인, 기기 시험은 안 함). 3.0.0을 내기 전에 쓰는 휴대폰이 2.4.1-hangul.2 이상인지 확인한다. 그보다 오래된 앱이 남아 있으면 3.0.0 릴리스에 같은 APK를 `myspoti-3.0.0.apk`로도 올리면 그 앱도 받는다.

`build-hangul.ps1`과 `sign-hangul-apk.ps1`은 출력 파일을 아직 `Myspoti-Hangul-<버전>.apk`로 만든다. 3.0.0 병합 때 `myspoti-ex-<버전>.apk`로 바꾼다.

원본 저장소 이름도 병합할 때 바꾼다. `release.json`의 `upstream`은 아직 `kota-zo/myspoti`이고, GitHub가 새 이름으로 연결해 주어 지금은 동작한다.

## 이미 반영한 원본 수정

Orpheon에 들어간 수정 중 `Bridge`가 페이지 메시지를 생성자 안에서 바로 받기 시작하는 수정은 미리 가져왔다(`5370d493`, 2.4.1-hangul.1에 포함). 매일 검사에서 `ClientTokenStoreTest`가 가끔 실패하던 원인이다. 이 부분은 Orpheon 병합 때 충돌하지 않는다.

## 병합 절차 (따라가기로 할 경우)

v2.4.0 때와 같은 절차를 쓴다(`docs/MAINTENANCE.md`). Orpheon 정식 태그가 나오면 `python scripts/maintain_upstream.py prepare --tag <태그>`로 후보를 만들고 충돌 해결, 번역, `verify`, `promote` 순서로 진행한다. `prepare`는 정식·베타 태그를 `v1.0.0` 형식으로 받으므로 Orpheon 태그도 그대로 쓸 수 있다. 실제 Orpheon 태그로 `prepare`를 돌려 보지는 않았다.
