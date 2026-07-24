FROM python:3.13-slim

RUN apt-get update \
    && apt-get install -y --no-install-recommends libreoffice ffmpeg poppler-utils fonts-noto-cjk \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY requirements.txt .
RUN pip install --no-cache-dir -r requirements.txt
COPY app.py .

RUN useradd --create-home --uid 10001 converter \
    && chown -R converter:converter /app
USER converter

EXPOSE 8787
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8787", "--workers", "1"]
