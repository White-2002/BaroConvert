# CloudConvert 고품질 Office 변환 설정

앱에서 `CloudConvert` 방법을 직접 선택한 경우에만 파일을 CloudConvert로 보냅니다. 무료 크레딧이 소진되거나 요청에 실패해도 LibreOffice로 몰래 전환하지 않고 이유를 앱에 표시합니다.

## API 키 만들기

1. [CloudConvert](https://cloudconvert.com/)에 로그인합니다.
2. Dashboard의 API Keys 화면에서 새 API 키를 만듭니다.
3. `user.read`, `task.read`, `task.write` 권한을 허용합니다.
4. 발급된 키를 한 번만 복사합니다.

## NAS에 적용하기

`file-converter/.env` 파일의 두 번째 줄에 키를 붙여 넣습니다.

```dotenv
API_KEY=앱에서-사용하는-기존-키
CLOUDCONVERT_API_KEY=CloudConvert에서-발급한-키
```

Container Manager에서 `file-converter` 프로젝트를 다시 빌드합니다. Android 앱 설정은 변경할 필요가 없습니다.

## 동작과 보안

- CloudConvert API 키는 NAS의 `.env`에만 저장하고 APK나 GitHub에 넣지 않습니다.
- CloudConvert 전송은 HTTPS를 사용합니다.
- 앱은 변환 전에 외부 전송 여부를 표시하고 사용자가 방법을 선택하도록 합니다.
- 무료 크레딧이 부족하면 앱에 남은 크레딧 부족 안내가 표시됩니다.
- 민감한 문서를 외부 서비스에 보내고 싶지 않으면 `CLOUDCONVERT_API_KEY` 값을 비우고 프로젝트를 다시 시작합니다.
