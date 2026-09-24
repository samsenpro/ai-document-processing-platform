import re
from typing import Any

from app.services.extraction.base import FieldKind
from app.services.extraction.parsing import find_dates
from app.services.text_utils import fold

_SPACES = re.compile(r"\s+")


class Grounding:
    """Comprueba que un valor propuesto por el LLM aparece realmente en el documento.

    Un modelo pequeño tiende a "completar" lo que falta (una moneda que el recibo no menciona, un
    importe calculado). Solo se aceptan valores anclados en el texto: el LLM ayuda a localizar datos,
    no a inventarlos."""

    def __init__(self, text: str) -> None:
        self._folded = _SPACES.sub(" ", fold(text))
        self._upper = text.upper()
        self._digits = re.sub(r"\D", "", text)
        self._dates = {d.isoformat() for d in find_dates(text)}

    def keep(self, value: Any, kind: FieldKind) -> Any:
        match kind:
            case "string":
                return value if value is not None and self._has_text(value) else None
            case "string_list":
                return [item for item in value if self._has_text(item)]
            case "number":
                return value if value is not None and self._has_number(value) else None
            case "number_list":
                return [item for item in value if self._has_number(item)]
            case "date":
                return value if value in self._dates else None
            case "currency":
                return value if value is not None and re.search(rf"\b{value}\b", self._upper) else None
        return None

    def _has_text(self, value: str) -> bool:
        return _SPACES.sub(" ", fold(value)).strip() in self._folded

    def _has_number(self, value: float) -> bool:
        # Se comparan solo los dígitos de la parte entera: "4.165.000", "4,165,000" y "4165000" coinciden
        return str(int(abs(value))) in self._digits
