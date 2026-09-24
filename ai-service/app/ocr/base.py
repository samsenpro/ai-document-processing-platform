from abc import ABC, abstractmethod

from PIL import Image


class OcrError(Exception):
    """El proveedor de OCR falló al procesar una imagen."""


class OcrService(ABC):
    """Abstracción del proveedor de OCR. El resto del pipeline solo conoce esta interfaz,
    así que cambiar Tesseract por otro proveedor (Textract, Google Vision...) no toca la lógica."""

    name: str

    @abstractmethod
    def extract_text(self, image: Image.Image) -> str:
        """Devuelve el texto reconocido en la imagen (puede ser vacío)."""

    @abstractmethod
    def is_available(self) -> bool:
        """Indica si el proveedor está operativo (binario instalado, credenciales válidas...)."""
