import io
import zipfile

import pytest

from app.core.errors import ErrorCode, ProcessingError
from app.models.document import SourceDocument, SourceFormat
from app.services.file_type import detect_format
from app.services.text_extraction import TextExtractionService
from tests.conftest import FakeOcr, make_settings
from tests.documents import docx_file, scanned_pdf, text_image, text_pdf


def service(ocr: FakeOcr | None = None, **settings) -> TextExtractionService:
    return TextExtractionService(ocr or FakeOcr("texto reconocido por OCR"), make_settings(**settings))


def extract(content: bytes, ocr: FakeOcr | None = None, **settings):
    return service(ocr, **settings).extract(SourceDocument(content))


def test_detects_format_from_content_not_from_name():
    assert detect_format(text_pdf(["hola"])) is SourceFormat.PDF
    assert detect_format(text_image(["hola"])) is SourceFormat.IMAGE
    assert detect_format(text_image(["hola"], "JPEG")) is SourceFormat.IMAGE
    assert detect_format(docx_file(["hola"])) is SourceFormat.DOCX
    assert detect_format("Factura número 1 — café".encode()) is SourceFormat.TEXT


def test_rejects_binary_and_non_docx_archives():
    with pytest.raises(ProcessingError) as binary:
        detect_format(b"\x00\x01\x02\x03binary")
    assert binary.value.code is ErrorCode.UNSUPPORTED_FORMAT

    archive = io.BytesIO()
    with zipfile.ZipFile(archive, "w") as zf:
        zf.writestr("data.csv", "a,b")
    with pytest.raises(ProcessingError) as zipped:
        detect_format(archive.getvalue())
    assert zipped.value.code is ErrorCode.UNSUPPORTED_FORMAT


def test_digital_pdf_uses_the_text_layer_without_ocr():
    ocr = FakeOcr("no debería usarse")
    result = extract(text_pdf(["Factura FE-1 emitida por ACME S.A.S.", "Total a pagar al vencimiento: $ 100.000"]),
                     ocr)
    assert "Factura FE-1 emitida por ACME S.A.S." in result.text
    assert (result.source_format, result.page_count, result.ocr_pages, ocr.calls) == (SourceFormat.PDF, 2, 0, 0)


def test_scanned_pdf_pages_go_through_ocr():
    ocr = FakeOcr("FACTURA escaneada con suficiente texto para superar el umbral")
    result = extract(scanned_pdf(["FACTURA"], pages=2), ocr)
    assert result.ocr_pages == 2 and ocr.calls == 2
    assert result.text.count("FACTURA escaneada") == 2


def test_pdf_page_limit_adds_a_warning():
    result = extract(text_pdf([f"Pagina numero {i} con texto suficiente para no usar OCR" for i in range(3)]),
                     max_pages=2)
    assert result.page_count == 3
    assert "Pagina numero 1" in result.text and "Pagina numero 2" not in result.text
    assert result.warnings == ["Only the first 2 of 3 pages were processed"]


def test_images_are_processed_with_ocr():
    ocr = FakeOcr("RECIBO DE CAJA")
    result = extract(text_image(["RECIBO"]), ocr)
    assert (result.text, result.source_format, result.ocr_pages) == ("RECIBO DE CAJA", SourceFormat.IMAGE, 1)


def test_image_without_available_ocr_fails_with_a_clear_code():
    with pytest.raises(ProcessingError) as error:
        extract(text_image(["RECIBO"]), FakeOcr(available=False))
    assert error.value.code is ErrorCode.OCR_UNAVAILABLE

    with pytest.raises(ProcessingError) as disabled:
        extract(text_image(["RECIBO"]), ocr_enabled=False)
    assert disabled.value.code is ErrorCode.OCR_UNAVAILABLE


def test_docx_paragraphs_and_tables():
    result = extract(docx_file(["Contrato de prestación de servicios"], [["Parte", "Nombre"], ["Cliente", "ACME"]]))
    assert result.source_format is SourceFormat.DOCX
    assert "Contrato de prestación de servicios" in result.text
    assert "Cliente | ACME" in result.text


def test_plain_text_with_legacy_encoding():
    result = extract("Facturación electrónica".encode("cp1252"))
    assert result.text == "Facturación electrónica"


def test_corrupted_pdf():
    with pytest.raises(ProcessingError) as error:
        extract(b"%PDF-1.7\nthis is not really a pdf")
    assert error.value.code is ErrorCode.CORRUPTED_DOCUMENT
