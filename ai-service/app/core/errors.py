from enum import StrEnum


class ErrorCode(StrEnum):
    """Códigos de error del servicio. El backend Java decide si reintenta según el status HTTP:
    los 4xx son definitivos (el documento no se puede procesar) y los 5xx son transitorios."""

    UNAUTHORIZED = "UNAUTHORIZED"
    INVALID_REQUEST = "INVALID_REQUEST"
    INVALID_SOURCE_URL = "INVALID_SOURCE_URL"
    DOCUMENT_SOURCE_REJECTED = "DOCUMENT_SOURCE_REJECTED"
    DOCUMENT_TOO_LARGE = "DOCUMENT_TOO_LARGE"
    UNSUPPORTED_FORMAT = "UNSUPPORTED_FORMAT"
    CORRUPTED_DOCUMENT = "CORRUPTED_DOCUMENT"
    NO_TEXT_EXTRACTED = "NO_TEXT_EXTRACTED"
    DOCUMENT_DOWNLOAD_FAILED = "DOCUMENT_DOWNLOAD_FAILED"
    OCR_UNAVAILABLE = "OCR_UNAVAILABLE"
    INTERNAL_ERROR = "INTERNAL_ERROR"

    @property
    def status_code(self) -> int:
        return _STATUS[self]


_STATUS: dict[ErrorCode, int] = {
    ErrorCode.UNAUTHORIZED: 401,
    ErrorCode.INVALID_REQUEST: 422,
    ErrorCode.INVALID_SOURCE_URL: 422,
    ErrorCode.DOCUMENT_SOURCE_REJECTED: 422,
    ErrorCode.DOCUMENT_TOO_LARGE: 413,
    ErrorCode.UNSUPPORTED_FORMAT: 415,
    ErrorCode.CORRUPTED_DOCUMENT: 422,
    ErrorCode.NO_TEXT_EXTRACTED: 422,
    ErrorCode.DOCUMENT_DOWNLOAD_FAILED: 502,
    ErrorCode.OCR_UNAVAILABLE: 503,
    ErrorCode.INTERNAL_ERROR: 500,
}


class ProcessingError(Exception):
    """Error esperado del pipeline, con un código estable que se devuelve al cliente."""

    def __init__(self, code: ErrorCode, message: str) -> None:
        super().__init__(message)
        self.code = code
        self.message = message
