import logging
import posixpath
from urllib.parse import urlsplit

import httpx

from app.core.correlation import CORRELATION_HEADER, current_correlation_id
from app.core.errors import ErrorCode, ProcessingError
from app.core.security import API_KEY_HEADER
from app.models.document import SourceDocument

logger = logging.getLogger(__name__)

# El backend responde con estos códigos cuando el documento ya no se puede procesar
# (borrado, fuera del estado PROCESSING...): reintentar no cambiaría el resultado
_REJECTED_STATUSES = {403, 404, 409, 410}


class DocumentFetcher:
    """Descarga el documento desde la URL que envía el backend.

    Solo acepta URLs bajo los prefijos permitidos y no sigue redirecciones: el servicio adjunta la
    clave interna a la petición y no debe poder usarse para llegar a otros hosts (SSRF)."""

    def __init__(self, client: httpx.Client, allowed_prefixes: list[str], api_key: str, max_bytes: int) -> None:
        if not allowed_prefixes:
            raise ValueError("At least one allowed document source prefix is required")
        self._client = client
        self._allowed = [urlsplit(p) for p in allowed_prefixes]
        self._api_key = api_key
        self._max_bytes = max_bytes

    def fetch(self, url: str, filename: str | None, content_type: str | None) -> SourceDocument:
        self._check_allowed(url)
        headers = {API_KEY_HEADER: self._api_key, CORRELATION_HEADER: current_correlation_id()}
        try:
            with self._client.stream("GET", url, headers=headers, follow_redirects=False) as response:
                self._check_status(response)
                declared = response.headers.get("content-length")
                if declared and declared.isdigit() and int(declared) > self._max_bytes:
                    raise self._too_large()
                content = bytearray()
                for chunk in response.iter_bytes():
                    content.extend(chunk)
                    if len(content) > self._max_bytes:
                        raise self._too_large()
                return SourceDocument(
                    content=bytes(content),
                    filename=filename,
                    declared_content_type=content_type or response.headers.get("content-type"),
                )
        except httpx.TimeoutException as ex:
            raise ProcessingError(ErrorCode.DOCUMENT_DOWNLOAD_FAILED, "Timed out downloading the document") from ex
        except httpx.TransportError as ex:
            raise ProcessingError(
                ErrorCode.DOCUMENT_DOWNLOAD_FAILED, f"Could not download the document: {type(ex).__name__}"
            ) from ex

    def _check_allowed(self, url: str) -> None:
        parts = urlsplit(url)
        # Se normaliza la ruta para que "../" no permita salir del prefijo permitido
        path = posixpath.normpath(parts.path) if parts.path else "/"
        valid = (
            parts.scheme in ("http", "https")
            and "@" not in parts.netloc
            and ".." not in parts.path.split("/")
            and any(
                parts.scheme == allowed.scheme
                and parts.netloc.lower() == allowed.netloc.lower()
                and (path + "/").startswith(allowed.path.rstrip("/") + "/")
                for allowed in self._allowed
            )
        )
        if not valid:
            raise ProcessingError(ErrorCode.INVALID_SOURCE_URL, "Document URL is not an allowed source")

    def _check_status(self, response: httpx.Response) -> None:
        if response.status_code == 200:
            return
        if response.status_code in _REJECTED_STATUSES:
            raise ProcessingError(
                ErrorCode.DOCUMENT_SOURCE_REJECTED,
                f"Document source rejected the download (HTTP {response.status_code})",
            )
        raise ProcessingError(
            ErrorCode.DOCUMENT_DOWNLOAD_FAILED, f"Document source returned HTTP {response.status_code}"
        )

    def _too_large(self) -> ProcessingError:
        return ProcessingError(
            ErrorCode.DOCUMENT_TOO_LARGE, f"Document exceeds the maximum size of {self._max_bytes} bytes"
        )
