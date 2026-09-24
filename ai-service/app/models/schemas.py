"""Contrato HTTP del servicio (lo que envía y recibe el backend Java)."""

from enum import StrEnum
from typing import Any, Literal
from uuid import UUID

from pydantic import BaseModel, Field


class DocumentType(StrEnum):
    INVOICE = "INVOICE"
    CONTRACT = "CONTRACT"
    RESUME = "RESUME"
    RECEIPT = "RECEIPT"
    IDENTIFICATION = "IDENTIFICATION"
    REPORT = "REPORT"
    OTHER = "OTHER"


class RequestedDocumentType(StrEnum):
    """Tipo pedido por el usuario. AUTO deja que el pipeline lo detecte."""

    AUTO = "AUTO"
    INVOICE = "INVOICE"
    CONTRACT = "CONTRACT"
    RESUME = "RESUME"
    RECEIPT = "RECEIPT"
    IDENTIFICATION = "IDENTIFICATION"
    REPORT = "REPORT"
    OTHER = "OTHER"


class ProcessRequest(BaseModel):
    document_id: UUID
    file_url: str = Field(min_length=1, max_length=2048)
    document_type: RequestedDocumentType = RequestedDocumentType.AUTO
    filename: str | None = Field(default=None, max_length=255)
    content_type: str | None = Field(default=None, max_length=255)


class ProcessingMetadata(BaseModel):
    source_format: str
    page_count: int
    ocr_used: bool
    ocr_pages: int
    char_count: int
    text_truncated: bool
    classification_method: Literal["USER", "RULES", "LLM"]
    extraction_method: Literal["RULES", "RULES+LLM"]
    summary_method: Literal["EXTRACTIVE", "LLM", "NONE"]
    llm_model: str | None = None
    warnings: list[str] = Field(default_factory=list)


class ProcessResponse(BaseModel):
    document_id: UUID
    status: Literal["COMPLETED"] = "COMPLETED"
    document_type: DocumentType
    confidence: float = Field(ge=0, le=1)
    language: str | None
    text: str
    summary: str
    # Cada tipo de documento tiene sus propios campos: el modelo es deliberadamente flexible
    entities: dict[str, Any]
    processing_time_ms: int
    metadata: ProcessingMetadata


class ComponentHealth(BaseModel):
    provider: str
    available: bool


class LlmHealth(BaseModel):
    enabled: bool
    model: str | None = None


class HealthResponse(BaseModel):
    status: Literal["UP", "DEGRADED"]
    service: str
    ocr: ComponentHealth
    llm: LlmHealth


class ErrorBody(BaseModel):
    code: str
    message: str
    correlation_id: str


class ErrorResponse(BaseModel):
    error: ErrorBody
