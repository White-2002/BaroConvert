import os
import secrets
import shutil
import subprocess
import tempfile
import time
import zipfile
import hashlib
import threading
from pathlib import Path

import httpx
from fastapi import FastAPI, File, Header, HTTPException, Query, UploadFile
from fastapi.responses import FileResponse
from starlette.background import BackgroundTask

app = FastAPI(title="BaroConvert Server", docs_url=None, redoc_url=None)
API_KEY = os.environ.get("API_KEY", "")
MAX_BYTES = int(os.environ.get("MAX_UPLOAD_MB", "500")) * 1024 * 1024
APP_VERSION_CODE = int(os.environ.get("APP_VERSION_CODE", "0"))
APP_VERSION_NAME = os.environ.get("APP_VERSION_NAME", "unconfigured")
RELEASE_APK = Path(os.environ.get("RELEASE_APK", "/app/releases/baroconvert.apk"))
CLOUDCONVERT_API_KEY = os.environ.get("CLOUDCONVERT_API_KEY", "").strip()
CLOUDCONVERT_TIMEOUT = int(os.environ.get("CLOUDCONVERT_TIMEOUT_SECONDS", "240"))
CLOUDCONVERT_FORMAT_CACHE_SECONDS = int(os.environ.get("CLOUDCONVERT_FORMAT_CACHE_SECONDS", "21600"))
PDF_SERVICES_CLIENT_ID = os.environ.get("PDF_SERVICES_CLIENT_ID", "").strip()
PDF_SERVICES_CLIENT_SECRET = os.environ.get("PDF_SERVICES_CLIENT_SECRET", "").strip()
ADOBE_TIMEOUT = int(os.environ.get("ADOBE_TIMEOUT_SECONDS", "240"))

OFFICE_EXTS = {
    ".doc", ".docx", ".odt", ".rtf", ".txt", ".html",
    ".ppt", ".pptx", ".odp",
    ".xls", ".xlsx", ".ods", ".csv",
}
CLOUDCONVERT_OFFICE_EXTS = {".doc", ".docx", ".ppt", ".pptx", ".xls", ".xlsx"}
CLOUD_ONLY_TARGETS = {
    ".pdf": {"docx", "pptx", "xlsx"},
    ".svg": {"pdf", "jpg", "png"},
    ".psd": {"pdf", "jpg", "png"},
    ".ai": {"pdf", "jpg", "png", "svg"},
    ".epub": {"pdf", "mobi", "azw3"},
    ".mobi": {"pdf", "epub", "azw3"},
    ".azw3": {"pdf", "epub", "mobi"},
    ".rar": {"zip"},
    ".7z": {"zip"},
    ".tar": {"zip"},
    ".gz": {"zip"},
}
AUDIO_EXTS = {".mp3", ".m4a", ".aac", ".wav", ".flac", ".ogg", ".opus", ".wma"}
VIDEO_EXTS = {".mp4", ".mov", ".mkv", ".avi", ".webm", ".m4v", ".3gp", ".wmv", ".flv"}
AUDIO_TARGETS = {"mp3", "m4a", "wav", "flac", "ogg", "opus"}
VIDEO_TARGETS = {"mp4", "mkv", "webm"}
PDF_TARGETS = {"jpg-zip", "png-zip"}
FORMAT_PATTERN = __import__("re").compile(r"^[a-z0-9][a-z0-9._+-]{0,31}$")
_cloud_format_cache: dict[str, tuple[float, list[dict[str, object]]]] = {}
_cloud_format_cache_lock = threading.Lock()


def authorize(value: str | None) -> None:
    if not API_KEY:
        raise HTTPException(500, "서버에 API_KEY가 설정되지 않았습니다.")
    if value is None or not secrets.compare_digest(value, API_KEY):
        raise HTTPException(401, "API 키가 올바르지 않습니다.")


def cloudconvert_formats(input_format: str) -> list[dict[str, object]]:
    input_format = input_format.lower().lstrip(".")
    if not FORMAT_PATTERN.fullmatch(input_format):
        raise HTTPException(400, "올바르지 않은 입력 형식입니다.")
    if not CLOUDCONVERT_API_KEY:
        raise CloudConvertConfigurationError("CloudConvert API 키가 NAS에 설정되지 않았습니다.")

    now = time.monotonic()
    with _cloud_format_cache_lock:
        cached = _cloud_format_cache.get(input_format)
        if cached and now - cached[0] < CLOUDCONVERT_FORMAT_CACHE_SECONDS:
            return cached[1]

    try:
        response = httpx.get(
            "https://api.cloudconvert.com/v2/operations",
            params={"filter[operation]": "convert", "filter[input_format]": input_format},
            headers={"Authorization": f"Bearer {CLOUDCONVERT_API_KEY}"},
            timeout=30,
        )
    except httpx.HTTPError as exc:
        raise CloudConvertError(f"CloudConvert 형식 목록 연결 오류: {type(exc).__name__}") from exc
    if response.status_code in {401, 403}:
        raise CloudConvertConfigurationError("CloudConvert API 키 또는 권한을 확인해주세요.")
    if response.is_error:
        raise CloudConvertError(f"CloudConvert 형식 목록 조회 실패: {cloud_error_text(response)[:300]}")

    operations = response.json().get("data", [])
    best: dict[str, dict[str, object]] = {}
    for operation in operations:
        output = str(operation.get("output_format", "")).lower()
        if not FORMAT_PATTERN.fullmatch(output) or output == input_format:
            continue
        credits = max(1, int(operation.get("credits") or 1))
        current = best.get(output)
        if current is None or credits < int(current["credits"]):
            best[output] = {"format": output, "credits": credits}
    result = sorted(best.values(), key=lambda item: str(item["format"]))
    with _cloud_format_cache_lock:
        _cloud_format_cache[input_format] = (now, result)
    return result


def cloudconvert_supports(source_ext: str, target: str) -> bool:
    return any(item["format"] == target for item in cloudconvert_formats(source_ext))


def validate_conversion(source_ext: str, target: str, method: str = "nas") -> None:
    if not FORMAT_PATTERN.fullmatch(target):
        raise HTTPException(400, "올바르지 않은 출력 형식입니다.")
    nas_allowed = (
        (source_ext in OFFICE_EXTS and target == "pdf")
        or (source_ext == ".pdf" and target in PDF_TARGETS)
        or (source_ext in AUDIO_EXTS and target in AUDIO_TARGETS)
        or (source_ext in VIDEO_EXTS and target in AUDIO_TARGETS | VIDEO_TARGETS)
    )
    cloud_allowed = cloudconvert_supports(source_ext, target) if method == "cloud" else False
    adobe_allowed = (
        (source_ext in CLOUDCONVERT_OFFICE_EXTS and target == "pdf")
        or (source_ext == ".pdf" and target in {"docx", "pptx", "xlsx"})
    )
    allowed = (
        nas_allowed if method == "nas"
        else adobe_allowed if method == "adobe"
        else cloud_allowed if method == "cloud"
        else False
    )
    if not allowed:
        raise HTTPException(415, f"지원하지 않는 변환입니다: {source_ext} → {target}")


def ffmpeg_command(source: Path, target: Path, target_format: str, video_input: bool) -> list[str]:
    command = ["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", str(source)]
    if target_format == "mp3":
        return command + ["-vn", "-c:a", "libmp3lame", "-q:a", "2", str(target)]
    if target_format == "m4a":
        return command + ["-vn", "-c:a", "aac", "-b:a", "192k", str(target)]
    if target_format == "wav":
        return command + ["-vn", "-c:a", "pcm_s16le", str(target)]
    if target_format == "flac":
        return command + ["-vn", "-c:a", "flac", str(target)]
    if target_format == "ogg":
        return command + ["-vn", "-c:a", "libvorbis", "-q:a", "6", str(target)]
    if target_format == "opus":
        return command + ["-vn", "-c:a", "libopus", "-b:a", "160k", str(target)]
    if not video_input:
        raise HTTPException(415, "음원 파일을 영상으로 변환할 수 없습니다.")
    if target_format == "mp4":
        return command + ["-c:v", "libx264", "-preset", "medium", "-crf", "22", "-c:a", "aac", "-movflags", "+faststart", str(target)]
    if target_format == "mkv":
        return command + ["-c:v", "libx264", "-preset", "medium", "-crf", "22", "-c:a", "aac", str(target)]
    if target_format == "webm":
        return command + ["-c:v", "libvpx-vp9", "-crf", "32", "-b:v", "0", "-c:a", "libopus", str(target)]
    raise HTTPException(415, "지원하지 않는 FFmpeg 출력 형식입니다.")


def run_checked(command: list[str], timeout: int, env: dict[str, str] | None = None) -> None:
    result = subprocess.run(command, capture_output=True, text=True, timeout=timeout, env=env)
    if result.returncode != 0:
        detail = (result.stderr or result.stdout or "변환 결과가 없습니다.")[-500:]
        raise HTTPException(422, f"변환 실패: {detail}")


class CloudConvertError(RuntimeError):
    pass


class CloudConvertCreditsExhausted(CloudConvertError):
    pass


class CloudConvertConfigurationError(CloudConvertError):
    pass


class AdobeError(RuntimeError):
    pass


class AdobeQuotaExhausted(AdobeError):
    pass


class AdobeConfigurationError(AdobeError):
    pass


def cloud_base_credits(source_ext: str, target_format: str) -> int:
    if source_ext == ".pdf" and target_format in {"docx", "pptx", "xlsx"}:
        return 4
    if source_ext in CLOUDCONVERT_OFFICE_EXTS and target_format == "pdf":
        return 2
    return 1


def cloud_error_text(response: httpx.Response) -> str:
    try:
        payload = response.json()
        return str(payload.get("message") or payload.get("error") or payload)
    except (ValueError, AttributeError):
        return response.text


def is_credit_error(text: str) -> bool:
    lowered = text.lower()
    return any(word in lowered for word in ("credit", "quota", "balance", "payment"))


def cloudconvert_remaining_credits(session: httpx.Client, auth_header: dict[str, str]) -> int:
    response = session.get(
        "https://api.cloudconvert.com/v2/users/me",
        headers=auth_header,
        timeout=30,
    )
    if response.status_code in {401, 403}:
        raise CloudConvertConfigurationError(
            "CloudConvert API 키에 user.read, task.read, task.write 권한이 필요합니다."
        )
    response.raise_for_status()
    return int(response.json()["data"]["credits"])


def cloudconvert_file(source: Path, output: Path, target_format: str) -> int:
    if not CLOUDCONVERT_API_KEY:
        raise CloudConvertConfigurationError("CloudConvert API 키가 NAS에 설정되지 않았습니다.")

    auth_header = {"Authorization": f"Bearer {CLOUDCONVERT_API_KEY}"}
    convert_task: dict[str, object] = {
        "operation": "convert",
        "input": "upload-file",
        "input_format": source.suffix.lower().lstrip("."),
        "output_format": target_format,
    }
    if source.suffix.lower() in CLOUDCONVERT_OFFICE_EXTS and target_format == "pdf":
        convert_task["engine"] = "office"
    payload = {
        "tasks": {
            "upload-file": {"operation": "import/upload"},
            "convert-file": convert_task,
            "export-file": {"operation": "export/url", "input": "convert-file"},
        }
    }

    session = httpx.Client(follow_redirects=True)
    job_id: str | None = None
    try:
        credits_before = cloudconvert_remaining_credits(session, auth_header)
        needed = cloud_base_credits(source.suffix.lower(), target_format)
        if credits_before < needed:
            raise CloudConvertCreditsExhausted(
                f"CloudConvert 무료 크레딧이 부족합니다. 필요 {needed}, 남음 {credits_before}"
            )

        created = session.post(
            "https://api.cloudconvert.com/v2/jobs",
            headers={**auth_header, "Content-Type": "application/json"},
            json=payload,
            timeout=30,
        )
        if created.is_error:
            detail = cloud_error_text(created)
            if is_credit_error(detail):
                raise CloudConvertCreditsExhausted("CloudConvert 무료 크레딧을 모두 사용했습니다.")
            created.raise_for_status()
        job = created.json()["data"]
        job_id = job["id"]
        upload_task = next(task for task in job["tasks"] if task["name"] == "upload-file")
        form = upload_task["result"]["form"]

        with source.open("rb") as source_stream:
            uploaded = session.post(
                form["url"],
                data={key: str(value) for key, value in form["parameters"].items()},
                files={"file": (source.name, source_stream, "application/octet-stream")},
                timeout=CLOUDCONVERT_TIMEOUT,
            )
        uploaded.raise_for_status()

        completed = session.get(
            f"https://sync.api.cloudconvert.com/v2/jobs/{job_id}",
            headers=auth_header,
            timeout=CLOUDCONVERT_TIMEOUT,
        )
        if completed.is_error:
            detail = cloud_error_text(completed)
            if is_credit_error(detail):
                raise CloudConvertCreditsExhausted("CloudConvert 무료 크레딧을 모두 사용했습니다.")
            completed.raise_for_status()
        finished_job = completed.json()["data"]
        if finished_job.get("status") != "finished":
            messages = [
                task.get("message")
                for task in finished_job.get("tasks", [])
                if task.get("message")
            ]
            detail = "; ".join(messages) or "CloudConvert 변환에 실패했습니다."
            if is_credit_error(detail):
                raise CloudConvertCreditsExhausted("CloudConvert 무료 크레딧을 모두 사용했습니다.")
            raise CloudConvertError(detail)

        export_task = next(
            task
            for task in finished_job["tasks"]
            if task["name"] == "export-file" and task["status"] == "finished"
        )
        export_files = export_task.get("result", {}).get("files", [])
        if not export_files or not export_files[0].get("url"):
            raise CloudConvertError("CloudConvert 결과 다운로드 주소가 없습니다.")
        download_url = export_files[0]["url"]
        if not download_url.startswith("https://"):
            raise CloudConvertError("CloudConvert가 안전하지 않은 다운로드 주소를 반환했습니다.")

        with session.stream("GET", download_url, timeout=CLOUDCONVERT_TIMEOUT) as downloaded:
            downloaded.raise_for_status()
            with output.open("wb") as output_stream:
                for chunk in downloaded.iter_bytes(1024 * 1024):
                    if chunk:
                        output_stream.write(chunk)
        if not output.is_file() or output.stat().st_size == 0:
            raise CloudConvertError("CloudConvert가 빈 결과 파일을 반환했습니다.")
        used_credits = sum(
            int(task.get("credits") or 0)
            for task in finished_job.get("tasks", [])
        )
        return max(0, credits_before - used_credits)
    except (CloudConvertCreditsExhausted, CloudConvertConfigurationError):
        raise
    except (httpx.HTTPError, KeyError, StopIteration, TypeError, ValueError) as exc:
        raise CloudConvertError(f"CloudConvert 연결 오류: {type(exc).__name__}") from exc
    finally:
        if job_id:
            try:
                session.delete(
                    f"https://api.cloudconvert.com/v2/jobs/{job_id}",
                    headers=auth_header,
                    timeout=15,
                )
            except httpx.HTTPError:
                pass
        session.close()


def adobe_error_text(response: httpx.Response) -> str:
    try:
        payload = response.json()
        return str(payload.get("message") or payload.get("error") or payload)
    except (ValueError, AttributeError):
        return response.text


def adobe_checked(response: httpx.Response) -> httpx.Response:
    if response.status_code == 429:
        raise AdobeQuotaExhausted("Adobe PDF Services 무료 사용량을 모두 사용했습니다.")
    if response.status_code in {401, 403}:
        raise AdobeConfigurationError("Adobe PDF Services 인증 정보가 올바르지 않습니다.")
    if response.is_error:
        detail = adobe_error_text(response)
        if is_credit_error(detail) or "usage" in detail.lower() or "limit" in detail.lower():
            raise AdobeQuotaExhausted("Adobe PDF Services 무료 사용량을 모두 사용했습니다.")
        response.raise_for_status()
    return response


def find_download_uri(payload: object) -> str | None:
    if isinstance(payload, dict):
        for key in ("downloadUri", "dowloadUri"):
            value = payload.get(key)
            if isinstance(value, str):
                return value
        for value in payload.values():
            found = find_download_uri(value)
            if found:
                return found
    if isinstance(payload, list):
        for value in payload:
            found = find_download_uri(value)
            if found:
                return found
    return None


def adobe_file(source: Path, output: Path, target_format: str) -> None:
    if not PDF_SERVICES_CLIENT_ID or not PDF_SERVICES_CLIENT_SECRET:
        raise AdobeConfigurationError("Adobe PDF Services 인증 정보가 NAS에 설정되지 않았습니다.")

    media_types = {
        ".doc": "application/msword",
        ".docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
        ".ppt": "application/vnd.ms-powerpoint",
        ".pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
        ".xls": "application/vnd.ms-excel",
        ".xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        ".pdf": "application/pdf",
    }
    media_type = media_types.get(source.suffix.lower(), "application/octet-stream")
    try:
        with httpx.Client(follow_redirects=True) as session:
            token_response = adobe_checked(session.post(
                "https://pdf-services.adobe.io/token",
                headers={"Content-Type": "application/x-www-form-urlencoded"},
                data={
                    "client_id": PDF_SERVICES_CLIENT_ID,
                    "client_secret": PDF_SERVICES_CLIENT_SECRET,
                },
                timeout=30,
            ))
            token = token_response.json()["access_token"]
            auth_headers = {
                "X-API-Key": PDF_SERVICES_CLIENT_ID,
                "Authorization": f"Bearer {token}",
            }
            asset_response = adobe_checked(session.post(
                "https://pdf-services.adobe.io/assets",
                headers={**auth_headers, "Content-Type": "application/json"},
                json={"mediaType": media_type},
                timeout=30,
            ))
            asset = asset_response.json()
            upload_uri = asset["uploadUri"]
            asset_id = asset["assetID"]
            if not upload_uri.startswith("https://"):
                raise AdobeError("Adobe가 안전하지 않은 업로드 주소를 반환했습니다.")
            with source.open("rb") as source_stream:
                adobe_checked(session.put(
                    upload_uri,
                    headers={"Content-Type": media_type},
                    content=source_stream,
                    timeout=ADOBE_TIMEOUT,
                ))

            if source.suffix.lower() == ".pdf":
                operation = "exportpdf"
                job_payload = {"assetID": asset_id, "targetFormat": target_format}
            else:
                operation = "createpdf"
                job_payload = {"assetID": asset_id}
            job_response = adobe_checked(session.post(
                f"https://pdf-services.adobe.io/operation/{operation}",
                headers={**auth_headers, "Content-Type": "application/json"},
                json=job_payload,
                timeout=30,
            ))
            status_url = job_response.headers.get("location", "")
            if not status_url.startswith("https://"):
                raise AdobeError("Adobe 작업 상태 주소가 없습니다.")

            deadline = time.monotonic() + ADOBE_TIMEOUT
            while True:
                status_response = adobe_checked(session.get(status_url, headers=auth_headers, timeout=30))
                status_payload = status_response.json()
                status = str(status_payload.get("status", "")).lower().replace("_", " ")
                if status == "done":
                    download_uri = find_download_uri(status_payload)
                    if not download_uri or not download_uri.startswith("https://"):
                        raise AdobeError("Adobe 결과 다운로드 주소가 없습니다.")
                    with session.stream("GET", download_uri, timeout=ADOBE_TIMEOUT) as downloaded:
                        adobe_checked(downloaded)
                        with output.open("wb") as output_stream:
                            for chunk in downloaded.iter_bytes(1024 * 1024):
                                if chunk:
                                    output_stream.write(chunk)
                    break
                if status == "failed":
                    detail = str(status_payload.get("error") or status_payload.get("message") or status_payload)
                    if is_credit_error(detail) or "usage" in detail.lower() or "limit" in detail.lower():
                        raise AdobeQuotaExhausted("Adobe PDF Services 무료 사용량을 모두 사용했습니다.")
                    raise AdobeError(f"Adobe 변환 실패: {detail[:300]}")
                if time.monotonic() >= deadline:
                    raise AdobeError("Adobe 변환 제한 시간을 초과했습니다.")
                time.sleep(1.5)
        if not output.is_file() or output.stat().st_size == 0:
            raise AdobeError("Adobe가 빈 결과 파일을 반환했습니다.")
    except (AdobeQuotaExhausted, AdobeConfigurationError, AdobeError):
        raise
    except (httpx.HTTPError, KeyError, TypeError, ValueError) as exc:
        raise AdobeError(f"Adobe 연결 오류: {type(exc).__name__}") from exc


def perform_conversion(
    source: Path,
    workdir: Path,
    target_format: str,
    method: str = "nas",
) -> tuple[Path, str, str, int | None]:
    source_ext = source.suffix.lower()
    validate_conversion(source_ext, target_format, method)

    if method == "cloud":
        output = workdir / f"output.{target_format}"
        credits_remaining = cloudconvert_file(source, output, target_format)
        return output, target_format, "cloudconvert", credits_remaining

    if method == "adobe":
        output = workdir / f"output.{target_format}"
        adobe_file(source, output, target_format)
        return output, target_format, "adobe-pdf-services", None

    if source_ext in OFFICE_EXTS:
        output = workdir / "input.pdf"
        run_checked([
            "libreoffice", "--headless", "--nologo", "--nodefault", "--nolockcheck",
            "--nofirststartwizard", "--convert-to", "pdf", "--outdir", str(workdir), str(source),
        ], timeout=180, env={**os.environ, "HOME": str(workdir)})
        return output, "pdf", "libreoffice", None

    if source_ext == ".pdf":
        image_format = target_format.split("-", 1)[0]
        prefix = workdir / "page"
        render_option = "-jpeg" if image_format == "jpg" else "-png"
        command = ["pdftoppm", "-r", "160", render_option, str(source), str(prefix)]
        if image_format == "jpg":
            command[3:3] = ["-jpegopt", "quality=90"]
        run_checked(command, timeout=180)
        pages = sorted(workdir.glob(f"page-*.{image_format}"))
        if not pages:
            raise HTTPException(422, "PDF에서 이미지 페이지를 만들지 못했습니다.")
        output = workdir / f"pages-{image_format}.zip"
        with zipfile.ZipFile(output, "w", zipfile.ZIP_DEFLATED) as archive:
            for page in pages:
                archive.write(page, page.name)
        return output, "zip", "poppler", None

    output = workdir / f"output.{target_format}"
    run_checked(ffmpeg_command(source, output, target_format, source_ext in VIDEO_EXTS), timeout=900)
    return output, target_format, "ffmpeg", None


@app.get("/health")
def health() -> dict[str, str]:
    return {"status": "ok"}


@app.get("/app/version")
def app_version(x_api_key: str | None = Header(default=None)) -> dict[str, object]:
    authorize(x_api_key)
    if not RELEASE_APK.is_file():
        raise HTTPException(503, "배포할 APK가 서버에 없습니다.")
    digest = hashlib.sha256(RELEASE_APK.read_bytes()).hexdigest()
    return {
        "versionCode": APP_VERSION_CODE,
        "versionName": APP_VERSION_NAME,
        "size": RELEASE_APK.stat().st_size,
        "sha256": digest,
        "downloadPath": "/app/download",
    }


@app.get("/cloud/formats/{input_format}")
def cloud_formats(
    input_format: str,
    x_api_key: str | None = Header(default=None),
) -> dict[str, object]:
    authorize(x_api_key)
    try:
        return {"inputFormat": input_format.lower().lstrip("."), "outputs": cloudconvert_formats(input_format)}
    except CloudConvertConfigurationError as exc:
        raise HTTPException(503, str(exc)) from exc
    except CloudConvertError as exc:
        raise HTTPException(502, str(exc)) from exc


@app.get("/app/download")
def app_download(x_api_key: str | None = Header(default=None)):
    authorize(x_api_key)
    if not RELEASE_APK.is_file():
        raise HTTPException(404, "배포할 APK가 서버에 없습니다.")
    return FileResponse(
        RELEASE_APK,
        media_type="application/vnd.android.package-archive",
        filename=f"baroconvert-{APP_VERSION_NAME}.apk",
    )


@app.post("/convert/{target_format}")
async def convert(
    target_format: str,
    file: UploadFile = File(...),
    x_api_key: str | None = Header(default=None),
    method: str = Query(default="nas", pattern="^(nas|adobe|cloud)$"),
):
    authorize(x_api_key)
    filename = Path(file.filename or "")
    source_ext = filename.suffix.lower()
    if target_format == "pptx-to-pdf" and source_ext == ".pptx":
        target_format = "pdf"
    validate_conversion(source_ext, target_format, method)

    workdir = Path(tempfile.mkdtemp(prefix="baroconvert-"))
    source = workdir / f"input{source_ext}"
    total = 0
    try:
        with source.open("wb") as output_stream:
            while chunk := await file.read(1024 * 1024):
                total += len(chunk)
                if total > MAX_BYTES:
                    raise HTTPException(413, "파일 크기 제한을 초과했습니다.")
                output_stream.write(chunk)

        output, output_ext, conversion_engine, credits_remaining = perform_conversion(
            source, workdir, target_format, method
        )
        if not output.exists():
            raise HTTPException(422, "변환 결과 파일이 없습니다.")
        download_name = filename.stem + f".{output_ext}"
        media_type = {
            "pdf": "application/pdf", "zip": "application/zip",
            "mp3": "audio/mpeg", "m4a": "audio/mp4", "wav": "audio/wav",
            "flac": "audio/flac", "ogg": "audio/ogg", "opus": "audio/ogg",
            "mp4": "video/mp4", "mkv": "video/x-matroska", "webm": "video/webm",
            "docx": "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "pptx": "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "xlsx": "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "jpg": "image/jpeg", "png": "image/png", "svg": "image/svg+xml",
            "epub": "application/epub+zip", "mobi": "application/x-mobipocket-ebook",
            "azw3": "application/vnd.amazon.ebook",
        }.get(output_ext, "application/octet-stream")
        response_headers = {"X-Conversion-Engine": conversion_engine}
        if credits_remaining is not None:
            response_headers["X-CloudConvert-Credits-Remaining"] = str(credits_remaining)
        return FileResponse(
            output,
            media_type=media_type,
            filename=download_name,
            headers=response_headers,
            background=BackgroundTask(shutil.rmtree, workdir, ignore_errors=True),
        )
    except HTTPException:
        shutil.rmtree(workdir, ignore_errors=True)
        raise
    except CloudConvertCreditsExhausted as exc:
        shutil.rmtree(workdir, ignore_errors=True)
        raise HTTPException(402, str(exc)) from exc
    except CloudConvertConfigurationError as exc:
        shutil.rmtree(workdir, ignore_errors=True)
        raise HTTPException(503, str(exc)) from exc
    except CloudConvertError as exc:
        shutil.rmtree(workdir, ignore_errors=True)
        raise HTTPException(502, str(exc)) from exc
    except AdobeQuotaExhausted as exc:
        shutil.rmtree(workdir, ignore_errors=True)
        raise HTTPException(402, str(exc)) from exc
    except AdobeConfigurationError as exc:
        shutil.rmtree(workdir, ignore_errors=True)
        raise HTTPException(503, str(exc)) from exc
    except AdobeError as exc:
        shutil.rmtree(workdir, ignore_errors=True)
        raise HTTPException(502, str(exc)) from exc
    except subprocess.TimeoutExpired:
        shutil.rmtree(workdir, ignore_errors=True)
        raise HTTPException(504, "변환 제한 시간을 초과했습니다.")
    except Exception as exc:
        shutil.rmtree(workdir, ignore_errors=True)
        raise HTTPException(500, f"서버 오류: {type(exc).__name__}") from exc
    finally:
        await file.close()


@app.post("/convert/pptx-to-pdf", include_in_schema=False)
async def legacy_pptx_to_pdf(
    file: UploadFile = File(...),
    x_api_key: str | None = Header(default=None),
):
    return await convert("pdf", file, x_api_key, "nas")
