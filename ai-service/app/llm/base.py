import json
import re
from abc import ABC, abstractmethod
from typing import Any


class LlmError(Exception):
    """El LLM no respondió o su respuesta no es utilizable."""


_FENCE = re.compile(r"^```(?:json)?\s*|\s*```$", re.IGNORECASE)


class LlmService(ABC):
    """Abstracción del proveedor de LLM. Los servicios del pipeline solo dependen de esta
    interfaz; el proveedor concreto se elige por configuración."""

    @property
    @abstractmethod
    def enabled(self) -> bool: ...

    @property
    @abstractmethod
    def model(self) -> str | None: ...

    @abstractmethod
    def complete(
        self, system: str, user: str, *, operation: str, json_mode: bool = False, max_tokens: int = 512
    ) -> str:
        """Devuelve el texto generado por el modelo."""

    def complete_json(self, system: str, user: str, *, operation: str, max_tokens: int = 512) -> dict[str, Any]:
        raw = self.complete(system, user, operation=operation, json_mode=True, max_tokens=max_tokens)
        return parse_json_object(raw)

    def close(self) -> None:  # pragma: no cover - por defecto no hay recursos
        return None


def parse_json_object(raw: str) -> dict[str, Any]:
    """Extrae el objeto JSON de la respuesta, tolerando bloques de Markdown o texto alrededor."""
    text = _FENCE.sub("", raw.strip())
    start, end = text.find("{"), text.rfind("}")
    if start == -1 or end <= start:
        raise LlmError("LLM response does not contain a JSON object")
    try:
        value = json.loads(text[start : end + 1])
    except json.JSONDecodeError as ex:
        raise LlmError("LLM response is not valid JSON") from ex
    if not isinstance(value, dict):
        raise LlmError("LLM response is not a JSON object")
    return value


class DisabledLlmService(LlmService):
    """Se usa cuando no hay proveedor configurado: el pipeline recurre a sus algoritmos locales."""

    @property
    def enabled(self) -> bool:
        return False

    @property
    def model(self) -> str | None:
        return None

    def complete(
        self, system: str, user: str, *, operation: str, json_mode: bool = False, max_tokens: int = 512
    ) -> str:
        raise LlmError("LLM is not configured")
