import logging
import time
from collections.abc import Iterator
from contextlib import contextmanager

from app.core.errors import ErrorCode, ProcessingError
from app.core.metrics import DOCUMENTS_PROCESSED, PROCESSING_DURATION, STEP_DURATION
from app.llm.base import LlmService
from app.models.schemas import ProcessingMetadata, ProcessRequest, ProcessResponse
from app.services.classification import DocumentClassifier
from app.services.document_fetcher import DocumentFetcher
from app.services.extraction.service import EntityExtractionService
from app.services.language_detection import LanguageDetector
from app.services.summarization import Summarizer
from app.services.text_cleaning import TextCleaner
from app.services.text_extraction import TextExtractionService

logger = logging.getLogger(__name__)


@contextmanager
def _step(name: str) -> Iterator[None]:
    started = time.perf_counter()
    try:
        yield
    finally:
        STEP_DURATION.labels(step=name).observe(time.perf_counter() - started)


class DocumentPipeline:
    """Documento -> texto -> limpieza -> idioma -> clasificación -> entidades -> resumen.

    Cada paso es un servicio independiente; el pipeline solo los encadena, mide su duración y
    reúne los avisos (por ejemplo, que el LLM no respondió y se usaron las reglas)."""

    def __init__(
        self,
        fetcher: DocumentFetcher,
        text_extractor: TextExtractionService,
        cleaner: TextCleaner,
        language_detector: LanguageDetector,
        classifier: DocumentClassifier,
        entity_extractor: EntityExtractionService,
        summarizer: Summarizer,
        llm: LlmService,
        max_text_chars: int,
    ) -> None:
        self._fetcher = fetcher
        self._text_extractor = text_extractor
        self._cleaner = cleaner
        self._language_detector = language_detector
        self._classifier = classifier
        self._entity_extractor = entity_extractor
        self._summarizer = summarizer
        self._llm = llm
        self._max_text_chars = max_text_chars

    def process(self, request: ProcessRequest) -> ProcessResponse:
        started = time.perf_counter()
        warnings: list[str] = []

        with _step("download"):
            source = self._fetcher.fetch(request.file_url, request.filename, request.content_type)
        with _step("text_extraction"):
            extracted = self._text_extractor.extract(source)
            warnings.extend(extracted.warnings)
        with _step("text_cleaning"):
            text = self._cleaner.clean(extracted.text)
        if not text:
            raise ProcessingError(ErrorCode.NO_TEXT_EXTRACTED, "No text could be extracted from the document")

        with _step("language_detection"):
            language = self._language_detector.detect(text)
        with _step("classification"):
            classification = self._classifier.classify(text, request.document_type, warnings)
        with _step("entity_extraction"):
            extraction = self._entity_extractor.extract(text, classification.document_type, language, warnings)
        with _step("summarization"):
            summary = self._summarizer.summarize(
                text, classification.document_type, extraction.entities, language, warnings
            )

        elapsed = time.perf_counter() - started
        PROCESSING_DURATION.observe(elapsed)
        DOCUMENTS_PROCESSED.labels(document_type=classification.document_type.value).inc()
        truncated = len(text) > self._max_text_chars
        uses_llm = "LLM" in (classification.method, summary.method) or extraction.method == "RULES+LLM"

        response = ProcessResponse(
            document_id=request.document_id,
            document_type=classification.document_type,
            confidence=classification.confidence,
            language=language,
            text=text[: self._max_text_chars],
            summary=summary.text,
            entities=extraction.entities,
            processing_time_ms=int(elapsed * 1000),
            metadata=ProcessingMetadata(
                source_format=extracted.source_format.value,
                page_count=extracted.page_count,
                ocr_used=extracted.ocr_pages > 0,
                ocr_pages=extracted.ocr_pages,
                char_count=len(text),
                text_truncated=truncated,
                classification_method=classification.method,
                extraction_method=extraction.method,
                summary_method=summary.method,
                llm_model=self._llm.model if uses_llm else None,
                warnings=warnings,
            ),
        )
        logger.info(
            "Document processed",
            extra={
                "document_id": str(request.document_id),
                "document_type": classification.document_type.value,
                "confidence": classification.confidence,
                "source_format": extracted.source_format.value,
                "pages": extracted.page_count,
                "ocr_pages": extracted.ocr_pages,
                "duration_ms": response.processing_time_ms,
                "warnings": len(warnings),
            },
        )
        return response
