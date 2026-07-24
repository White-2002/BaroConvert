import subprocess
import unittest
import io
import hashlib
import tempfile
import zipfile
from pathlib import Path
from unittest.mock import patch

from fastapi.testclient import TestClient

import app as server

PPTX_MIME = "application/vnd.openxmlformats-officedocument.presentationml.presentation"


class ConvertApiTest(unittest.TestCase):
    def setUp(self) -> None:
        server.API_KEY = "test-secret"
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
        self.assertEqual(response.content, b"%PDF-test")

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
