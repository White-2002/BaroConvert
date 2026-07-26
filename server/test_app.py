import subprocess
import unittest
import io
import hashlib
import tempfile
import zipfile
from pathlib import Path
from unittest.mock import MagicMock, patch

from fastapi.testclient import TestClient

import app as server

PPTX_MIME = "application/vnd.openxmlformats-officedocument.presentationml.presentation"


class ConvertApiTest(unittest.TestCase):
    def setUp(self) -> None:
        server.API_KEY = "test-secret"
        server.CLOUDCONVERT_API_KEY = ""
        server.PDF_SERVICES_CLIENT_ID = ""
        server.PDF_SERVICES_CLIENT_SECRET = ""
        server.MAX_BYTES = 1024 * 1024
        self.release_dir = tempfile.TemporaryDirectory()
        self.addCleanup(self.release_dir.cleanup)
        server.RELEASE_APK = Path(self.release_dir.name) / "baroconvert.apk"
        server.RELEASE_APK.write_bytes(b"test-apk")
        server.APP_VERSION_CODE = 5
        server.APP_VERSION_NAME = "0.4.0"
        self.client = TestClient(server.app)

    def test_health(self) -> None:
        response = self.client.get("/health")
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"status": "ok"})

    def test_app_update_manifest_and_download(self) -> None:
        manifest = self.client.get("/app/version", headers={"X-API-Key": "test-secret"})
        self.assertEqual(manifest.status_code, 200)
        self.assertEqual(manifest.json()["versionCode"], 5)
        self.assertEqual(manifest.json()["versionName"], "0.4.0")
        self.assertEqual(manifest.json()["sha256"], hashlib.sha256(b"test-apk").hexdigest())

        download = self.client.get("/app/download", headers={"X-API-Key": "test-secret"})
        self.assertEqual(download.status_code, 200)
        self.assertEqual(download.content, b"test-apk")

    def test_requires_valid_api_key(self) -> None:
        response = self.client.post(
            "/convert/pptx-to-pdf",
            headers={"X-API-Key": "wrong"},
            files={"file": ("slides.pptx", b"pptx", PPTX_MIME)},
        )
        self.assertEqual(response.status_code, 401)

    def test_rejects_non_pptx(self) -> None:
        response = self.client.post(
            "/convert/pptx-to-pdf",
            headers={"X-API-Key": "test-secret"},
            files={"file": ("notes.txt", b"text", "text/plain")},
        )
        self.assertEqual(response.status_code, 415)

    @patch("app.subprocess.run")
    def test_returns_generated_pdf(self, run_mock) -> None:
        def fake_convert(command, **_kwargs):
            output_dir = Path(command[command.index("--outdir") + 1])
            (output_dir / "input.pdf").write_bytes(b"%PDF-test")
            return subprocess.CompletedProcess(command, 0, "", "")

        run_mock.side_effect = fake_convert
        response = self.client.post(
            "/convert/pptx-to-pdf",
            headers={"X-API-Key": "test-secret"},
            files={"file": ("slides.pptx", b"pptx", PPTX_MIME)},
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.headers["content-type"], "application/pdf")
        self.assertEqual(response.headers["x-conversion-engine"], "libreoffice")
        self.assertEqual(response.content, b"%PDF-test")

    @patch("app.cloudconvert_file")
    def test_prefers_cloudconvert_for_powerpoint(self, cloud_mock) -> None:
        server.CLOUDCONVERT_API_KEY = "cloud-secret"

        def fake_cloud(_source, output, target_format):
            self.assertEqual(target_format, "pdf")
            output.write_bytes(b"%PDF-cloud")
            return 8

        cloud_mock.side_effect = fake_cloud
        response = self.client.post(
            "/convert/pdf?method=cloud",
            headers={"X-API-Key": "test-secret"},
            files={"file": ("slides.pptx", b"pptx", PPTX_MIME)},
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.headers["x-conversion-engine"], "cloudconvert")
        self.assertEqual(response.headers["x-cloudconvert-credits-remaining"], "8")
        self.assertEqual(response.content, b"%PDF-cloud")

    @patch("app.httpx.Client")
    def test_cloudconvert_job_upload_wait_download_and_delete(self, client_class) -> None:
        server.CLOUDCONVERT_API_KEY = "cloud-secret"
        session = client_class.return_value

        created = MagicMock()
        created.status_code = 201
        created.is_error = False
        created.json.return_value = {
            "data": {
                "id": "job-123",
                "tasks": [{
                    "name": "upload-file",
                    "result": {
                        "form": {
                            "url": "https://upload.cloudconvert.com/job-123",
                            "parameters": {"signature": "signed", "max_file_count": 1},
                        }
                    },
                }],
            }
        }
        uploaded = MagicMock()
        uploaded.status_code = 200
        uploaded.is_error = False
        session.post.side_effect = [created, uploaded]

        credits = MagicMock()
        credits.status_code = 200
        credits.is_error = False
        credits.json.return_value = {"data": {"credits": 10}}
        completed = MagicMock()
        completed.status_code = 200
        completed.is_error = False
        completed.json.return_value = {
            "data": {
                "status": "finished",
                "tasks": [{
                    "name": "export-file",
                    "status": "finished",
                    "result": {
                        "files": [{
                            "url": "https://storage.cloudconvert.com/result.pdf",
                        }]
                    },
                }],
            }
        }
        session.get.side_effect = [credits, completed]

        downloaded = MagicMock()
        downloaded.status_code = 200
        downloaded.is_error = False
        downloaded.iter_bytes.return_value = [b"%PDF-cloud-http"]
        stream_context = MagicMock()
        stream_context.__enter__.return_value = downloaded
        session.stream.return_value = stream_context

        with tempfile.TemporaryDirectory() as directory:
            source = Path(directory) / "slides.pptx"
            output = Path(directory) / "slides.pdf"
            source.write_bytes(b"pptx")
            remaining = server.cloudconvert_file(source, output, "pdf")
            self.assertEqual(output.read_bytes(), b"%PDF-cloud-http")
            self.assertEqual(remaining, 10)

        session.delete.assert_called_once()
        session.close.assert_called_once()

    @patch("app.cloudconvert_file")
    def test_cloud_quota_error_does_not_silently_fallback(self, cloud_mock) -> None:
        server.CLOUDCONVERT_API_KEY = "cloud-secret"
        cloud_mock.side_effect = server.CloudConvertCreditsExhausted("무료 크레딧을 모두 사용했습니다.")
        response = self.client.post(
            "/convert/pdf?method=cloud",
            headers={"X-API-Key": "test-secret"},
            files={"file": ("slides.pptx", b"pptx", PPTX_MIME)},
        )
        self.assertEqual(response.status_code, 402)
        self.assertIn("크레딧", response.json()["detail"])

    @patch("app.adobe_file")
    def test_adobe_is_explicit_and_supported_for_powerpoint(self, adobe_mock) -> None:
        def fake_adobe(_source, output, target_format):
            self.assertEqual(target_format, "pdf")
            output.write_bytes(b"%PDF-adobe")

        adobe_mock.side_effect = fake_adobe
        response = self.client.post(
            "/convert/pdf?method=adobe",
            headers={"X-API-Key": "test-secret"},
            files={"file": ("slides.pptx", b"pptx", PPTX_MIME)},
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.headers["x-conversion-engine"], "adobe-pdf-services")
        self.assertEqual(response.content, b"%PDF-adobe")

    def test_rejects_meaningless_office_to_audio(self) -> None:
        response = self.client.post(
            "/convert/mp3",
            headers={"X-API-Key": "test-secret"},
            files={"file": ("slides.pptx", b"pptx", PPTX_MIME)},
        )
        self.assertEqual(response.status_code, 415)

    @patch("app.subprocess.run")
    def test_converts_audio(self, run_mock) -> None:
        def fake_convert(command, **_kwargs):
            Path(command[-1]).write_bytes(b"fake-mp3")
            return subprocess.CompletedProcess(command, 0, "", "")

        run_mock.side_effect = fake_convert
        response = self.client.post(
            "/convert/mp3",
            headers={"X-API-Key": "test-secret"},
            files={"file": ("sound.wav", b"wav", "audio/wav")},
        )
        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.content, b"fake-mp3")

    @patch("app.subprocess.run")
    def test_pdf_pages_are_returned_as_zip(self, run_mock) -> None:
        def fake_convert(command, **_kwargs):
            prefix = Path(command[-1])
            prefix.with_name(prefix.name + "-1.png").write_bytes(b"png-page")
            return subprocess.CompletedProcess(command, 0, "", "")

        run_mock.side_effect = fake_convert
        response = self.client.post(
            "/convert/png-zip",
            headers={"X-API-Key": "test-secret"},
            files={"file": ("document.pdf", b"pdf", "application/pdf")},
        )
        self.assertEqual(response.status_code, 200)
        with zipfile.ZipFile(io.BytesIO(response.content)) as archive:
            self.assertEqual(archive.namelist(), ["page-1.png"])
            self.assertEqual(archive.read("page-1.png"), b"png-page")


if __name__ == "__main__":
    unittest.main()
