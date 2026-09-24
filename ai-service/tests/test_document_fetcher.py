import httpx
import pytest

from app.core.correlation import correlation_id_var
from app.core.errors import ErrorCode, ProcessingError
from app.services.document_fetcher import DocumentFetcher
from tests.conftest import API_KEY, SOURCE_PREFIX, document_server

URL = SOURCE_PREFIX + "0b0f9a4e-7d1c-4c4c-9d7e-3f1f5c2b8a11/content"


def fetcher(documents, seen=None, max_bytes=1024) -> DocumentFetcher:
    return DocumentFetcher(document_server(documents, seen), [SOURCE_PREFIX], API_KEY, max_bytes)


def code_of(call) -> ErrorCode:
    with pytest.raises(ProcessingError) as error:
        call()
    return error.value.code


def test_downloads_with_internal_key_and_correlation_id():
    seen: list[httpx.Request] = []
    token = correlation_id_var.set("corr-123")
    try:
        document = fetcher({URL: b"hola"}, seen).fetch(URL, "a.txt", "text/plain")
    finally:
        correlation_id_var.reset(token)
    assert document.content == b"hola"
    assert seen[0].headers["X-Internal-Api-Key"] == API_KEY
    assert seen[0].headers["X-Correlation-Id"] == "corr-123"


@pytest.mark.parametrize(
    "url",
    [
        "http://attacker.example/internal/v1/documents/x/content",
        "http://java-api:8080/actuator/env",
        "http://java-api:8080/internal/v1/documents/../../actuator/env",
        "http://user@java-api:8080/internal/v1/documents/x/content",
        "file:///etc/passwd",
        "https://java-api:8080/internal/v1/documents/x/content",
    ],
)
def test_rejects_urls_outside_the_allowed_prefix(url):
    seen: list[httpx.Request] = []
    assert code_of(lambda: fetcher({}, seen).fetch(url, None, None)) is ErrorCode.INVALID_SOURCE_URL
    assert seen == []  # la clave interna nunca sale hacia un destino no permitido


def test_does_not_follow_redirects():
    documents = {URL: (302, b"")}
    assert code_of(lambda: fetcher(documents).fetch(URL, None, None)) is ErrorCode.DOCUMENT_DOWNLOAD_FAILED


@pytest.mark.parametrize(("status", "expected"), [
    (404, ErrorCode.DOCUMENT_SOURCE_REJECTED),
    (409, ErrorCode.DOCUMENT_SOURCE_REJECTED),
    (500, ErrorCode.DOCUMENT_DOWNLOAD_FAILED),
    (503, ErrorCode.DOCUMENT_DOWNLOAD_FAILED),
])
def test_maps_source_errors(status, expected):
    assert code_of(lambda: fetcher({URL: (status, b"")}).fetch(URL, None, None)) is expected


def test_rejects_documents_over_the_size_limit():
    assert code_of(lambda: fetcher({URL: b"x" * 2048}).fetch(URL, None, None)) is ErrorCode.DOCUMENT_TOO_LARGE


def test_network_errors_are_transient_failures():
    def failing(request: httpx.Request) -> httpx.Response:
        raise httpx.ConnectError("connection refused", request=request)

    client = httpx.Client(transport=httpx.MockTransport(failing))
    subject = DocumentFetcher(client, [SOURCE_PREFIX], API_KEY, 1024)
    assert code_of(lambda: subject.fetch(URL, None, None)) is ErrorCode.DOCUMENT_DOWNLOAD_FAILED
