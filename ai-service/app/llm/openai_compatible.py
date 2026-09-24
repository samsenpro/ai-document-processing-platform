import logging
import time

import httpx

from app.core.correlation import CORRELATION_HEADER, current_correlation_id
from app.core.metrics import LLM_REQUESTS
from app.llm.base import LlmError, LlmService

logger = logging.getLogger(__name__)


class OpenAICompatibleLlmService(LlmService):
    """Cliente para cualquier API compatible con /chat/completions de OpenAI
    (OpenAI, Groq, OpenRouter, Together, Ollama, vLLM, LM Studio...)."""

    def __init__(
        self,
        base_url: str,
        model: str,
        api_key: str = "",
        timeout_seconds: float = 60.0,
        temperature: float = 0.1,
        client: httpx.Client | None = None,
    ) -> None:
        self._model = model
        self._temperature = temperature
        headers = {"Authorization": f"Bearer {api_key}"} if api_key else {}
        self._client = client or httpx.Client(
            base_url=base_url.rstrip("/"),
            headers=headers,
            timeout=httpx.Timeout(timeout_seconds, connect=5.0),
        )

    @property
    def enabled(self) -> bool:
        return True

    @property
    def model(self) -> str | None:
        return self._model

    def complete(
        self, system: str, user: str, *, operation: str, json_mode: bool = False, max_tokens: int = 512
    ) -> str:
        payload: dict = {
            "model": self._model,
            "messages": [{"role": "system", "content": system}, {"role": "user", "content": user}],
            "temperature": self._temperature,
            "max_tokens": max_tokens,
        }
        if json_mode:
            payload["response_format"] = {"type": "json_object"}

        started = time.perf_counter()
        try:
            response = self._client.post(
                "/chat/completions", json=payload, headers={CORRELATION_HEADER: current_correlation_id()}
            )
            response.raise_for_status()
            content = response.json()["choices"][0]["message"]["content"]
        except httpx.TimeoutException as ex:
            self._record(operation, "timeout", started)
            raise LlmError("LLM request timed out") from ex
        except httpx.HTTPStatusError as ex:
            self._record(operation, "http_error", started)
            # Nunca se registra el cuerpo: puede reflejar el prompt con el contenido del documento
            raise LlmError(f"LLM provider returned HTTP {ex.response.status_code}") from ex
        except (httpx.HTTPError, KeyError, IndexError, TypeError, ValueError) as ex:
            self._record(operation, "error", started)
            raise LlmError(f"LLM request failed: {type(ex).__name__}") from ex

        if not isinstance(content, str) or not content.strip():
            self._record(operation, "empty", started)
            raise LlmError("LLM returned an empty response")
        self._record(operation, "success", started)
        return content

    def close(self) -> None:
        self._client.close()

    def _record(self, operation: str, outcome: str, started: float) -> None:
        LLM_REQUESTS.labels(operation=operation, outcome=outcome).inc()
        logger.info(
            "LLM call finished",
            extra={
                "operation": operation,
                "outcome": outcome,
                "model": self._model,
                "duration_ms": int((time.perf_counter() - started) * 1000),
            },
        )
