# 파일 확장자 변환

갤럭시 탭/폰용 광고 없는 파일 변환기 MVP입니다.

현재 지원 범위:

- 휴대폰 시스템 설정을 따르는 라이트 모드·다크 모드
- PPTX → PDF (개인 NAS의 LibreOffice 서버 사용)
- JPG/PNG/WEBP 등 Android가 읽을 수 있는 이미지 → PDF/JPG/PNG/WEBP (기기 내부 처리)
- 파일을 먼저 선택하면 해당 파일에서 가능한 출력 확장자만 표시
- JSON/XML/YAML/Markdown/LOG/INI/CONF → TXT (내용을 변경하지 않고 기기 내부에서 저장)
- DOC/DOCX/ODT/RTF/TXT/HTML, PPT/PPTX/ODP, XLS/XLSX/ODS/CSV → PDF
- MP3/M4A/AAC/WAV/FLAC/OGG/OPUS/WMA 음원 상호 변환
- MP4/MOV/MKV/AVI/WEBM/M4V/3GP/WMV/FLV → MP4/MKV/WEBM 또는 음원 추출
- PDF → 페이지별 JPG/PNG ZIP 묶음
- 내 파일/갤러리에서 공유 → 파일 확장자 변환
- 시스템 파일 선택기와 저장 위치 선택기 사용 (전체 저장소 권한 없음)

## 왜 일부 변환은 서버를 쓰나요?

Android에는 Office 문서 렌더러나 범용 영상·음원 변환기가 내장되어 있지 않습니다. LibreOffice와 FFmpeg 전체를 앱에 포함하면 용량, 라이선스, 기기 호환성 문제가 커집니다. 이미지는 기기 내부에서 처리하고 문서·PDF·영상·음원은 개인 NAS의 LibreOffice, Poppler, FFmpeg로 변환합니다.

## NAS 서버 실행

Synology Container Manager에서 `server` 폴더를 프로젝트로 열거나, Docker Compose가 있는 서버에서 다음을 실행합니다.

```sh
cd server
docker compose up -d --build
```

실행 전에 `server/.env.example`을 `server/.env`로 복사하고 `API_KEY`를 길고 임의적인 값으로 반드시 변경하세요. 같은 값을 앱의 **API 키** 칸에 입력합니다. `.env`는 Git에 올리지 마세요.

서버 확인:

```sh
curl http://NAS주소:8787/health
```

집 안에서만 사용한다면 공유기에서 8787 포트 포워딩을 하지 마세요. 외부에서도 쓸 경우에는 Synology Reverse Proxy와 유효한 TLS 인증서를 사용해 `https://` 주소로 연결하세요.

## 폰에서 업데이트

v0.5.0부터 앱을 실행할 때 공개 GitHub Releases의 새 버전을 자동으로 확인합니다. NAS가 없어도 앱에서 **업데이트**를 눌러 APK를 내려받고 Android 설치 화면을 열 수 있습니다.

- GitHub Release에는 `file-extension-converter-v버전.apk`와 `update.json`을 함께 첨부합니다.
- `update.json`에는 새 APK의 버전 코드, 버전명, 크기, SHA-256, 다운로드 URL이 들어갑니다.
- 최초 한 번은 Android 설정에서 **이 출처의 앱 설치 허용**을 켜야 합니다.
- Android 보안상 실제 설치 직전의 시스템 확인 버튼은 자동으로 누를 수 없습니다.
- 모든 업데이트 APK는 동일한 서명 키로 빌드해야 기존 앱 위에 설치됩니다.

## Android 앱 빌드

1. Android Studio 최신 안정판과 Android SDK 36을 설치합니다.
2. 이 루트 폴더를 Android Studio에서 엽니다.
3. Gradle 동기화가 끝나면 갤럭시 기기를 연결하고 `app`을 실행합니다.
4. 앱에서 `http://NAS내부IP:8787`과 `API_KEY`를 입력합니다.

Gradle Wrapper가 포함되어 있으므로 Android Studio를 열지 않고도 JDK 17 환경에서 `gradlew.bat assembleDebug`로 빌드할 수 있습니다.

## 개인정보와 한계

- 문서·PDF·영상·음원은 지정한 서버로 업로드되며 변환 후 임시 폴더가 삭제됩니다.
- 로컬 HTTP는 암호화되지 않습니다. 신뢰하는 집 내부망에서만 사용하세요.
- LibreOffice와 Microsoft PowerPoint의 글꼴/도형 렌더링 차이로 일부 슬라이드 배치가 달라질 수 있습니다.
- 원본 PPTX가 사용하는 글꼴을 NAS 컨테이너에도 설치해야 결과가 가장 비슷합니다.
