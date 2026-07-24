import os
import secrets
import shutil
import subprocess
import tempfile
import zipfile
import hashlib
from pathlib import Path

from fastapi import FastAPI, File, Header, HTTPException, UploadFile
from fastapi.responses import FileResponse
from starlette.background import BackgroundTask

app = FastAPI(title="BaroConvert Server", docs_url=None, redoc_url=None)
API_KEY = os.environ.get("API_KEY", "")
MAX_BYTES = int(os.environ.get("MAX_UPLOAD_MB", "500")) * 1024 * 1024
APP_VERSION_CODE = int(os.environ.get("APP_VERSION_CODE", "0"))
APP_VERSION_NAME = os.environ.get("APP_VERSION_NAME", "unconfigured")
RELEASE_APK = Path(os.environ.get("RELEASE_APK", "/app/releases/baroconvert.apk"))

OFFICE_EXTS = {
    ".doc", ".docx", ".odt", ".rtf", ".txt", ".html",
    ".ppt", ".pptx", ".odp",
    ".xls", ".xlsx", ".ods", ".csv",
}
AUDIO_EXTS = {".mp3", ".m4a", ".aac", ".wav", ".flac", ".ogg", ".opus", ".wma"}
VIDEO_EXTS = {".mp4", ".mov", ".mkv", ".avi", ".webm", ".m4v", ".3gp", ".wmv", ".flv"}
AUDIO_TARGETS = {"mp3", "m4a", "wav", "flac", "ogg", "opus"}
VIDEO_TARGETS = {"mp4", "mkv", "webm"}
PDF_TARGETS = {"jpg-zip", "png-zip"}


def authorize(value: str | None) -> None:
    if not API_KEY:
        raise HTTPException(500, "서버에 API_KEY가 설정되지 않았습니다.")
    if value is None or not secrets.compare_digest(value, API_KEY):
        raise HTTPException(401, "API 키가 올바르지 않습니다.")


def validate_conversion(source_ext: str, target: str) -> None:
    allowed = (
        (source_ext in OFFICE_EXTS and target == "pdf")
        or (source_ext == ".pdf" and target in PDF_TARGETS)
        or (source_ext in AUDIO_EXTS and target in AUDIO_TARGETS)
        or (source_ext in VIDEO_EXTS and target in AUDIO_TARGETS | VIDEO_TARGETS)
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


def perform_conversion(source: Path, workdir: Path, target_format: str) -> tuple[Path, str]:
    source_ext = source.suffix.lower()
    validate_conversion(source_ext, target_format)

    if source_ext in OFFICE_EXTS:
        run_checked([
            "libreoffice", "--headless", "--nologo", "--nodefault", "--nolockcheck",
            "--nofirststartwizard", "--convert-to", "pdf", "--outdir", str(workdir), str(source),
        ], timeout=180, env={**os.environ, "HOME": str(workdir)})
        output = workdir / f"input.pdf"
        return output, "pdf"

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
        return output, "zip"

    output = workdir / f"output.{target_format}"
    run_checked(ffmpeg_command(source, output, target_format, source_ext in VIDEO_EXTS), timeout=900)
    return output, target_format


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
):
    authorize(x_api_key)
    filename = Path(file.filename or "")
    source_ext = filename.suffix.lower()
    if target_format == "pptx-to-pdf" and source_ext == ".pptx":
        target_format = "pdf"
    validate_conversion(source_ext, target_format)

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

        output, output_ext = perform_conversion(source, workdir, target_format)
        if not output.exists():
            raise HTTPException(422, "변환 결과 파일이 없습니다.")
        download_name = filename.stem + f".{output_ext}"
        media_type = {
            "pdf": "application/pdf", "zip": "application/zip",
            "mp3": "audio/mpeg", "m4a": "audio/mp4", "wav": "audio/wav",
            "flac": "audio/flac", "ogg": "audio/ogg", "opus": "audio/ogg",
            "mp4": "video/mp4", "mkv": "video/x-matroska", "webm": "video/webm",
        }.get(output_ext, "application/octet-stream")
        return FileResponse(
            output,
            media_type=media_type,
            filename=download_name,
            background=BackgroundTask(shutil.rmtree, workdir, ignore_errors=True),
        )
    except HTTPException:
        shutil.rmtree(workdir, ignore_errors=True)
        raise
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
    return await convert("pdf", file, x_api_key)
