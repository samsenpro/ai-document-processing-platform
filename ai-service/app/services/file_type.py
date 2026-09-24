import io
import zipfile

from app.core.errors import ErrorCode, ProcessingError
from app.models.document import SourceFormat

_IMAGE_SIGNATURES = (
    b"\x89PNG\r\n\x1a\n",
    b"\xff\xd8\xff",  # JPEG
    b"II*\x00",  # TIFF little-endian
    b"MM\x00*",  # TIFF big-endian
    b"BM",  # BMP
)


def detect_format(content: bytes) -> SourceFormat:
    """Detecta el formato por los bytes del archivo, nunca por la extensión ni el Content-Type
    declarado (ambos los controla quien sube el archivo)."""
    if not content:
        raise ProcessingError(ErrorCode.NO_TEXT_EXTRACTED, "Document is empty")
    if content.startswith(b"%PDF-"):
        return SourceFormat.PDF
    if content.startswith(_IMAGE_SIGNATURES) or (content[:4] == b"RIFF" and content[8:12] == b"WEBP"):
        return SourceFormat.IMAGE
    if content.startswith(b"PK\x03\x04"):
        if _is_docx(content):
            return SourceFormat.DOCX
        raise ProcessingError(ErrorCode.UNSUPPORTED_FORMAT, "ZIP archives other than DOCX are not supported")
    if _looks_like_text(content):
        return SourceFormat.TEXT
    raise ProcessingError(ErrorCode.UNSUPPORTED_FORMAT, "Unsupported document format")


def _is_docx(content: bytes) -> bool:
    try:
        with zipfile.ZipFile(io.BytesIO(content)) as archive:
            return "word/document.xml" in archive.namelist()
    except zipfile.BadZipFile:
        return False


def _looks_like_text(content: bytes) -> bool:
    sample = content[:8192]
    if b"\x00" in sample:
        return False
    try:
        sample.decode("utf-8")
        return True
    except UnicodeDecodeError as ex:
        # La muestra puede cortar un carácter multibyte justo al final
        if ex.start >= len(sample) - 3:
            return True
    # Texto con codificación heredada (Windows-1252/Latin-1): se acepta si casi todo es imprimible
    decoded = sample.decode("cp1252", errors="replace")
    printable = sum(c.isprintable() or c in "\n\r\t" for c in decoded)
    return printable / len(decoded) > 0.95
