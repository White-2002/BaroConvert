# Adobe PDF Services 고품질 변환 설정

Adobe 방식은 Microsoft Word, PowerPoint, Excel 파일을 PDF로 변환하거나 PDF를 DOCX/PPTX/XLSX로 변환할 때 사용합니다. 앱에서 `Adobe 고품질 · 추천`을 선택한 경우에만 외부로 전송됩니다.

## 인증 정보 만들기

1. Adobe Acrobat Services의 PDF Services API에서 자격 증명을 만듭니다.
2. 내려받은 JSON에서 `client_id`와 `client_secret`을 확인합니다.
3. NAS의 `file-converter/.env`에 다음 두 값을 넣습니다.

```dotenv
PDF_SERVICES_CLIENT_ID=발급받은-client-id
PDF_SERVICES_CLIENT_SECRET=발급받은-client-secret
```

Container Manager에서 `file-converter` 프로젝트를 다시 빌드합니다. 인증 정보는 APK나 GitHub에 넣지 않습니다.

무료 사용량을 모두 사용하면 앱에 이를 알리고, 선택하지 않은 NAS 방식으로 자동 전환하지 않습니다.
