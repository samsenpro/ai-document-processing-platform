from fastapi import APIRouter, Depends, Request

from app.core.security import require_api_key
from app.models.schemas import (
    ComponentHealth,
    ErrorResponse,
    HealthResponse,
    LlmHealth,
    ProcessRequest,
    ProcessResponse,
)
from app.services.pipeline import DocumentPipeline

router = APIRouter(prefix="/api/v1")


def get_pipeline(request: Request) -> DocumentPipeline:
    return request.app.state.pipeline


@router.post(
    "/process",
    response_model=ProcessResponse,
    dependencies=[Depends(require_api_key)],
    summary="Run the document processing pipeline",
    responses={
        401: {"model": ErrorResponse}, 413: {"model": ErrorResponse}, 415: {"model": ErrorResponse},
        422: {"model": ErrorResponse}, 502: {"model": ErrorResponse}, 503: {"model": ErrorResponse},
    },
)
def process_document(payload: ProcessRequest, pipeline: DocumentPipeline = Depends(get_pipeline)) -> ProcessResponse:
    # Endpoint síncrono a propósito: FastAPI lo ejecuta en su threadpool y el trabajo pesado
    # (OCR, parseo de PDF) no bloquea el event loop
    return pipeline.process(payload)


@router.get("/health", response_model=HealthResponse, summary="Service health")
def health(request: Request) -> HealthResponse:
    settings = request.app.state.settings
    ocr = request.app.state.ocr
    llm = request.app.state.llm
    ocr_available = settings.ocr_enabled and ocr.is_available()
    return HealthResponse(
        # Sin OCR el servicio sigue procesando PDFs digitales, DOCX y texto: queda degradado, no caído
        status="UP" if ocr_available or not settings.ocr_enabled else "DEGRADED",
        service=settings.service_name,
        ocr=ComponentHealth(provider=ocr.name, available=ocr_available),
        llm=LlmHealth(enabled=llm.enabled, model=llm.model),
    )
