import logging
from functools import cached_property

import pytesseract
from PIL import Image, ImageOps

from app.ocr.base import OcrError, OcrService

logger = logging.getLogger(__name__)


class TesseractOcrService(OcrService):
    """OCR local con Tesseract (binario del sistema, invocado a través de pytesseract)."""

    name = "tesseract"

    def __init__(self, languages: str = "spa+eng", timeout_seconds: float = 60.0) -> None:
        self.languages = languages
        self.timeout_seconds = timeout_seconds

    def extract_text(self, image: Image.Image) -> str:
        # Escala de grises y orientación EXIF corregida: mejora el reconocimiento de fotos de móvil
        prepared = ImageOps.exif_transpose(image).convert("L")
        try:
            return pytesseract.image_to_string(
                prepared, lang=self.languages, config="--oem 1 --psm 3", timeout=self.timeout_seconds
            )
        except (pytesseract.TesseractError, pytesseract.TesseractNotFoundError, RuntimeError) as ex:
            # pytesseract lanza RuntimeError cuando se agota el timeout
            raise OcrError(f"Tesseract failed: {type(ex).__name__}") from ex

    def is_available(self) -> bool:
        return self._version is not None

    @cached_property
    def _version(self) -> str | None:
        try:
            return str(pytesseract.get_tesseract_version())
        except (pytesseract.TesseractNotFoundError, OSError):
            logger.warning("Tesseract binary not found: OCR is unavailable")
            return None
