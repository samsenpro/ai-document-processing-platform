# syntax=docker/dockerfile:1

# ---- Base: Python + Tesseract (OCR en español e inglés) ----
FROM python:3.12-slim AS base
ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1 \
    PIP_DISABLE_PIP_VERSION_CHECK=1
RUN apt-get update \
    && apt-get install -y --no-install-recommends tesseract-ocr tesseract-ocr-spa tesseract-ocr-eng \
    && rm -rf /var/lib/apt/lists/*
WORKDIR /app
COPY requirements.txt .
RUN pip install -r requirements.txt

# ---- Tests: docker build --target test -t documind-ai-test ai-service && docker run --rm documind-ai-test ----
FROM base AS test
COPY requirements-dev.txt .
RUN pip install -r requirements-dev.txt
COPY pyproject.toml .
COPY app ./app
COPY tests ./tests
CMD ["pytest", "--cov=app", "--cov-report=term-missing:skip-covered"]

# ---- Runtime ----
FROM base AS runtime
RUN useradd --system --uid 10001 --no-create-home app
COPY --chown=app:app app ./app
USER app
EXPOSE 8000
HEALTHCHECK --interval=15s --timeout=5s --start-period=20s --retries=5 \
    CMD python -c "import urllib.request,sys; sys.exit(0 if urllib.request.urlopen('http://localhost:8000/api/v1/health', timeout=4).status == 200 else 1)"
# Un solo proceso: el trabajo pesado va al threadpool y las métricas de Prometheus no necesitan modo multiproceso
CMD ["uvicorn", "app.main:create_app", "--factory", "--host", "0.0.0.0", "--port", "8000", "--no-access-log"]
