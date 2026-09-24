from app.core.config import Settings
from app.ocr.base import OcrService
from app.ocr.tesseract import TesseractOcrService


def create_ocr_service(settings: Settings) -> OcrService:
    """Punto único donde se elige el proveedor de OCR según la configuración."""
    provider = settings.ocr_provider.lower()
    if provider == "tesseract":
        return TesseractOcrService(settings.ocr_languages, settings.ocr_timeout_seconds)
    raise ValueError(f"Unsupported OCR provider: {settings.ocr_provider}")
