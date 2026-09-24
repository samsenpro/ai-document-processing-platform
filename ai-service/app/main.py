import logging
import time
from collections.abc import AsyncIterator
from contextlib import asynccontextmanager

import httpx
from fastapi import FastAPI, Request
from fastapi.exceptions import RequestValidationError
from fastapi.responses import JSONResponse
from prometheus_client import make_asgi_app

from app.api.routes import router
from app.core.config import Settings, get_settings
from app.core.correlation import CORRELATION_HEADER, correlation_id_var, resolve_correlation_id
from app.core.errors import ErrorCode, ProcessingError
from app.core.logging import configure_logging
from app.core.metrics import HTTP_LATENCY, HTTP_REQUESTS, PROCESSING_ERRORS
from app.llm.base import LlmService
from app.llm.factory import create_llm_service
from app.ocr.base import OcrService
from app.ocr.factory import create_ocr_service
from app.services.classification import DocumentClassifier
from app.services.document_fetcher import DocumentFetcher
from app.services.extraction.service import EntityExtractionService
from app.services.language_detection import LanguageDetector
from app.services.pipeline import DocumentPipeline
from app.services.summarization import Summarizer
from app.services.text_cleaning import TextCleaner
from app.services.text_extraction import TextExtractionService

logger = logging.getLogger("app")


def create_app(
    settings: Settings | None = None,
    *,
    ocr: OcrService | None = None,
    llm: LlmService | None = None,
    http_client: httpx.Client | None = None,
) -> FastAPI:
    """Construye la aplicación y sus dependencias. Los tests sustituyen el OCR, el LLM o el cliente
    HTTP sin tocar el resto del pipeline."""
    settings = settings or get_settings()
    configure_logging(settings.log_level, settings.log_format, settings.service_name)

    ocr = ocr or create_ocr_service(settings)
    llm = llm or create_llm_service(settings)
    http_client = http_client or httpx.Client(timeout=httpx.Timeout(settings.download_timeout_seconds, connect=5.0))

    pipeline = DocumentPipeline(
        fetcher=DocumentFetcher(
            http_client,
            settings.allowed_source_prefixes,
            settings.ai_service_api_key.get_secret_value(),
            settings.max_document_bytes,
        ),
        text_extractor=TextExtractionService(ocr, settings),
        cleaner=TextCleaner(),
        language_detector=LanguageDetector(),
        classifier=DocumentClassifier(llm, settings.llm_classification_threshold),
        entity_extractor=EntityExtractionService(llm, settings.default_currency, settings.llm_max_input_chars),
        summarizer=Summarizer(llm, settings.summary_max_sentences, settings.llm_max_input_chars),
        llm=llm,
        max_text_chars=settings.max_text_chars,
    )

    @asynccontextmanager
    async def lifespan(_: FastAPI) -> AsyncIterator[None]:
        logger.info(
            "AI service started",
            extra={"ocr_provider": ocr.name, "ocr_available": ocr.is_available(), "llm_enabled": llm.enabled,
                   "llm_model": llm.model},
        )
        yield
        http_client.close()
        llm.close()

    app = FastAPI(
        title="DocuMind AI Service",
        version="1.0.0",
        description="Internal document processing service (OCR, classification, extraction, summarization).",
        lifespan=lifespan,
    )
    app.state.settings = settings
    app.state.pipeline = pipeline
    app.state.ocr = ocr
    app.state.llm = llm

    app.include_router(router)
    app.mount("/metrics", make_asgi_app())
    _register_middleware(app)
    _register_error_handlers(app)
    return app


def _register_middleware(app: FastAPI) -> None:
    @app.middleware("http")
    async def correlation_and_metrics(request: Request, call_next):
        correlation_id = resolve_correlation_id(request.headers.get(CORRELATION_HEADER))
        token = correlation_id_var.set(correlation_id)
        started = time.perf_counter()
        status = 500
        try:
            try:
                response = await call_next(request)
            except Exception:
                # Se captura aquí (y no en un exception_handler global) para responder con el
                # correlation_id todavía en contexto
                PROCESSING_ERRORS.labels(error_code=ErrorCode.INTERNAL_ERROR.value).inc()
                logger.exception("Unexpected error while processing the request")
                response = _error(ErrorCode.INTERNAL_ERROR, "Unexpected error while processing the document")
            status = response.status_code
            response.headers[CORRELATION_HEADER] = correlation_id
            return response
        finally:
            elapsed = time.perf_counter() - started
            # Se etiqueta con la plantilla de la ruta, no con la URL, para no disparar la cardinalidad
            route = getattr(request.scope.get("route"), "path", "unmatched")
            if not request.url.path.startswith("/metrics"):
                HTTP_REQUESTS.labels(request.method, route, str(status)).inc()
                HTTP_LATENCY.labels(request.method, route).observe(elapsed)
                logger.info(
                    "HTTP request",
                    extra={"method": request.method, "path": route, "status": status,
                           "duration_ms": int(elapsed * 1000)},
                )
            correlation_id_var.reset(token)


def _error(code: ErrorCode, message: str) -> JSONResponse:
    body = {"error": {"code": code.value, "message": message, "correlation_id": correlation_id_var.get()}}
    return JSONResponse(status_code=code.status_code, content=body)


def _register_error_handlers(app: FastAPI) -> None:
    @app.exception_handler(ProcessingError)
    async def processing_error(_: Request, ex: ProcessingError) -> JSONResponse:
        if ex.code is not ErrorCode.UNAUTHORIZED:
            PROCESSING_ERRORS.labels(error_code=ex.code.value).inc()
        log = logger.warning if ex.code.status_code >= 500 else logger.info
        log("Request failed", extra={"error_code": ex.code.value, "error_message": ex.message})
        return _error(ex.code, ex.message)

    @app.exception_handler(RequestValidationError)
    async def validation_error(_: Request, ex: RequestValidationError) -> JSONResponse:
        fields = ", ".join(".".join(str(p) for p in err["loc"][1:]) or "body" for err in ex.errors())
        return _error(ErrorCode.INVALID_REQUEST, f"Invalid request fields: {fields}")
