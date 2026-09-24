"""Modelos internos que viajan entre los pasos del pipeline."""

from dataclasses import dataclass, field
from enum import StrEnum
from typing import Any

from app.models.schemas import DocumentType


class SourceFormat(StrEnum):
    PDF = "PDF"
    IMAGE = "IMAGE"
    DOCX = "DOCX"
    TEXT = "TEXT"


@dataclass(frozen=True)
class SourceDocument:
    content: bytes
    filename: str | None = None
    declared_content_type: str | None = None


@dataclass
class ExtractedText:
    text: str
    source_format: SourceFormat
    page_count: int
    ocr_pages: int = 0
    warnings: list[str] = field(default_factory=list)


@dataclass(frozen=True)
class Classification:
    document_type: DocumentType
    confidence: float
    method: str  # USER | RULES | LLM


@dataclass(frozen=True)
class Extraction:
    entities: dict[str, Any]
    method: str  # RULES | RULES+LLM


@dataclass(frozen=True)
class Summary:
    text: str
    method: str  # EXTRACTIVE | LLM | NONE
