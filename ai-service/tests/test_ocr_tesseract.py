"""Tests con el binario real de Tesseract (incluido en la imagen Docker de tests)."""

import io

import pytest
from PIL import Image

from app.models.document import SourceDocument
from app.ocr.tesseract import TesseractOcrService
from app.services.text_extraction import TextExtractionService
from tests.conftest import make_settings
from tests.documents import scanned_pdf, text_image

tesseract = TesseractOcrService("spa+eng")
pytestmark = [
    pytest.mark.ocr,
    pytest.mark.skipif(not tesseract.is_available(), reason="Tesseract is not installed"),
]


def test_recognizes_text_in_an_image():
    image = Image.open(io.BytesIO(text_image(["FACTURA ELECTRONICA", "Total a pagar 4.165.000"])))
    text = tesseract.extract_text(image)
    assert "FACTURA" in text.upper()
    assert "4.165.000" in text


def test_scanned_pdf_is_read_through_real_ocr():
    service = TextExtractionService(tesseract, make_settings())
    result = service.extract(SourceDocument(scanned_pdf(["CONTRATO DE ARRENDAMIENTO", "Clausula primera"])))
    assert result.ocr_pages == 1
    assert "CONTRATO" in result.text.upper()
