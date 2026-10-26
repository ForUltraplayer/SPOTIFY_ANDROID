# 원본 변경 유지 관리

## 버전과 앱 식별자

자체 APK 버전, 버전 코드, 앱 식별자와 업데이트 저장소 및 원본 커밋은 release.json에서 관리한다. Android 라이브러리 버전은 기존 base/gradle/libs.versions.toml에서 관리한다. 원본이 새 버전을 배포해도 원본 APK로 바꾸지 않고 한글 기능을 유지한 소스를 다시 빌드한다. 기존 개인 서명키를 유지하며 서명키를 검사 서버에 올리지 않는다.

## 변경 확인과 후보 검사

```powershell
python scripts/maintain_upstream.py check
python scripts/maintain_upstream.py prepare --tag v2.0.2-beta.6
# work/upstream-candidate.json의 후보 폴더에서 보고된 충돌을 먼저 해결한다.
python scripts/maintain_upstream.py verify --java-home 'JDK 경로' --android-sdk 'SDK 경로'
python scripts/maintain_upstream.py promote
```

prepare는 깨끗한 Git 작업 폴더에서만 실행한다. Git의 병합 결과를 work 하위에 따로 준비하며 현재 앱 소스를 수정하지 않는다. 충돌 파일은 보고서에 기록하고 해결 전에는 검사를 중단한다. README 형식과 한글 변경 내역 및 추가 라이선스 고지는 유지하면서 원본의 변경도 검토한다. 원본의 코딩 에이전트 정책 파일은 앱 소스와 구분해 현재 프로젝트의 정책을 보존한다.

verify는 앱 식별자, 업데이트 저장소, 버전 코드와 서명 설정 연결을 확인하고 후보 폴더에서 기존 한글 발음 및 원문 조회와 업데이트 검사를 실행한다. 성공 보고서는 기준 Git 커밋과 후보 파일 전체 해시에 연결한다. 검사 이후 후보가 변경되거나 기준 소스가 바뀌면 promote가 반영을 거부한다. 반영은 검증한 변경만 현재 소스에 적용하며 자동 커밋이나 푸시는 수행하지 않는다. 기존 Git 파일 실행 권한도 유지한다.

검사 통과가 실제 휴대폰 재생을 보장하지는 않는다. 배포 전에는 버전 코드를 높이고 기존 개인 키로 release APK를 서명한 뒤 이전 APK와 서명자를 비교한다. 기존 앱 위 설치와 로그인 유지, 곡 전환과 한글 발음 표시는 실제 기기에서 별도로 확인한다.

## GitHub 실행

.github/workflows/maintenance.yml은 매일 UTC 02:47 및 main 변경과 PR에서 원본 릴리스와 현재 소스를 검사한다. 수동 실행에서 태그를 지정하면 병합 후보도 검사한다. 베타 원본과 정식 원본을 구분하고 원본 기준 커밋보다 오래된 정식 버전을 새 업데이트로 적용하지 않는다. 결과는 작업 요약과 android-upstream-inspection 아티팩트에 남는다. API 조회 실패는 작업 실패로 표시한다.

검사는 GitHub의 일시적인 Windows 실행기에서 최대 25 분 동안 실행한다. 휴대폰이나 개발 PC에 상주 서비스를 추가하지 않는다. Android upstream maintenance 워크플로를 비활성화하거나 schedule 항목을 제거하면 주기 검사를 중단할 수 있다. 실제 GitHub 실행과 성공 여부는 Actions에서 확인한다. 공개 저장소의 검사에는 개인 키와 계정 및 유료 API가 필요하지 않다.
