import re
import unicodedata

_CONTROL = re.compile(r"[\x00-\x08\x0b\x0c\x0e-\x1f\x7f]")
_HYPHENATED_BREAK = re.compile(r"(\w)-\n(\w)")
_SPACES = re.compile(r"[ \t ]+")
_BLANK_LINES = re.compile(r"\n{3,}")


class TextCleaner:
    """Normaliza el texto extraído sin perder la estructura de líneas, que la extracción de
    entidades necesita para localizar etiquetas como "Total:" o "Cliente:"."""

    def clean(self, text: str) -> str:
        text = unicodedata.normalize("NFC", text)
        text = text.replace("\r\n", "\n").replace("\r", "\n").replace("\f", "\n\n")
        text = _CONTROL.sub("", text)
        # Palabras partidas al final de línea por el OCR o el maquetado: "factu-\nración"
        text = _HYPHENATED_BREAK.sub(r"\1\2", text)
        lines = [_SPACES.sub(" ", line).strip() for line in text.split("\n")]
        text = "\n".join(lines)
        return _BLANK_LINES.sub("\n\n", text).strip()
