from collections.abc import Callable
from typing import Any

import httpx
import pytest
from fastapi.testclient import TestClient
from PIL import Image

from app.core.config import Settings
from app.llm.base import LlmError, LlmService
from app.main import create_app
from app.ocr.base import OcrService

API_KEY = "test-internal-api-key-0123456789abcdef"
SOURCE_PREFIX = "http://java-api:8080/internal/v1/documents/"


class FakeOcr(OcrService):
    """OCR simulado: devuelve un texto fijo y cuenta las imágenes recibidas."""

    name = "fake"

    def __init__(self, text: str = "", available: bool = True) -> None:
        self.text = text
        self.available = available
        self.calls = 0

    def extract_text(self, image: Image.Image) -> str:
        self.calls += 1
        return self.text

    def is_available(self) -> bool:
        return self.available


class FakeLlm(LlmService):
    """LLM simulado: responde por operación (classification, extraction, summarization)."""

    def __init__(self, responses: dict[str, str | Exception] | None = None, model: str = "fake-model") -> None:
        self.responses = responses or {}
        self._model = model
        self.calls: list[dict[str, Any]] = []

    @property
    def enabled(self) -> bool:
        return True

    @property
    def model(self) -> str | None:
        return self._model

    def complete(self, system: str, user: str, *, operation: str, json_mode: bool = False,
                 max_tokens: int = 512) -> str:
        self.calls.append({"operation": operation, "system": system, "user": user, "json_mode": json_mode})
        response = self.responses.get(operation, LlmError(f"no fake response for {operation}"))
        if isinstance(response, Exception):
            raise response
        return response


def make_settings(**overrides: Any) -> Settings:
    values: dict[str, Any] = {
        "ai_service_api_key": API_KEY,
        "document_source_allowed_prefixes": SOURCE_PREFIX,
        "log_format": "text",
        "log_level": "WARNING",
    }
    values.update(overrides)
    return Settings(**values)


def document_server(documents: dict[str, bytes | tuple[int, bytes]], seen: list[httpx.Request] | None = None
                    ) -> httpx.Client:
    """Cliente HTTP cuyo transporte simula el endpoint interno del backend Java."""

    def handler(request: httpx.Request) -> httpx.Response:
        if seen is not None:
            seen.append(request)
        entry = documents.get(str(request.url))
        if entry is None:
            return httpx.Response(404)
        status, body = entry if isinstance(entry, tuple) else (200, entry)
        return httpx.Response(status, content=body)

    return httpx.Client(transport=httpx.MockTransport(handler))


@pytest.fixture
def settings() -> Settings:
    return make_settings()


@pytest.fixture
def client_factory() -> Callable[..., TestClient]:
    def factory(documents: dict[str, bytes | tuple[int, bytes]] | None = None, *, ocr: OcrService | None = None,
                llm: LlmService | None = None, seen: list[httpx.Request] | None = None,
                **settings_overrides: Any) -> TestClient:
        app = create_app(
            make_settings(**settings_overrides),
            ocr=ocr or FakeOcr(),
            llm=llm,
            http_client=document_server(documents or {}, seen),
        )
        return TestClient(app)

    return factory
