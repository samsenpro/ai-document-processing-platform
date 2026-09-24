from abc import ABC, abstractmethod
from dataclasses import dataclass
from typing import Any, Literal

from app.models.schemas import DocumentType
from app.services.extraction.parsing import parse_amount, parse_date

FieldKind = Literal["string", "date", "number", "currency", "string_list", "number_list"]


@dataclass(frozen=True)
class FieldSpec:
    kind: FieldKind
    description: str


@dataclass(frozen=True)
class ExtractionContext:
    language: str | None
    default_currency: str


class EntityExtractor(ABC):
    """Extrae los campos propios de un tipo de documento. Cada tipo declara sus campos:
    no todos los documentos comparten el mismo esquema."""

    document_type: DocumentType
    fields: dict[str, FieldSpec]

    @abstractmethod
    def extract(self, text: str, context: ExtractionContext) -> dict[str, Any]:
        """Devuelve los campos encontrados; los que falten se completan con None."""


_MAX_STRING = 300
_MAX_ITEMS = 25


def coerce(value: Any, spec: FieldSpec) -> Any:
    """Valida y normaliza un valor (de las reglas o del LLM) según el tipo del campo.
    Lo que no encaja se descarta como None en lugar de propagar datos inválidos."""
    if value is None or value == "" or value == []:
        return [] if spec.kind.endswith("_list") else None
    match spec.kind:
        case "string":
            text = str(value).strip() if not isinstance(value, (dict, list)) else ""
            return text[:_MAX_STRING] or None
        case "date":
            return parse_date(str(value)) if isinstance(value, str) else None
        case "number":
            if isinstance(value, bool):
                return None
            if isinstance(value, (int, float)):
                return round(float(value), 2)
            return parse_amount(value) if isinstance(value, str) else None
        case "currency":
            code = str(value).strip().upper()
            return code if len(code) == 3 and code.isalpha() else None
        case "string_list":
            if not isinstance(value, list):
                return []
            items = [str(item).strip()[:_MAX_STRING] for item in value if isinstance(item, (str, int, float))]
            return list(dict.fromkeys(item for item in items if item))[:_MAX_ITEMS]
        case "number_list":
            if not isinstance(value, list):
                return []
            numbers = [coerce(item, FieldSpec("number", "")) for item in value]
            return [n for n in numbers if n is not None][:_MAX_ITEMS]
    return None


def is_empty(value: Any) -> bool:
    return value is None or value == []
