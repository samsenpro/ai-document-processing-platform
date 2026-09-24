import re
from typing import Any

from app.models.schemas import DocumentType
from app.services.extraction.base import EntityExtractor, ExtractionContext, FieldSpec
from app.services.extraction.parsing import clean_name, date_after

_I = re.IGNORECASE

_ROLE_LINE = re.compile(
    r"^\s*(?:el\s+|la\s+)?(?:contratante|contratista|arrendador|arrendatario|empleador|trabajador|comprador|"
    r"vendedor|prestador(?:\s+del\s+servicio)?|client|contractor|provider|landlord|tenant|employer|employee|"
    r"buyer|seller|licensor|licensee)\s*[:\-–]\s*(.+)$",
    _I,
)
_BETWEEN_ES = re.compile(
    r"\bentre\s+(.{3,160}?)\s*,?\s+y\s+(.{3,160}?)(?=,|;|\.\s|\s+quien|\s+identificad|\s+mayor\s+de|"
    r"\s+con\s+(?:nit|c\.?c)|\s+se\s+(?:celebra|suscribe))",
    _I,
)
_BETWEEN_EN = re.compile(r"\bbetween\s+(.{3,160}?)\s*(?:\([^)]*\))?\s*,?\s+and\s+(.{3,160}?)(?=\s*\(|,|;|\.\s)", _I)

_START = re.compile(
    r"fecha\s+de\s+inicio|a\s+partir\s+del?|desde\s+el|iniciar[aá]\s+el|comenzar[aá]\s+el|start\s+date|"
    r"commencement\s+date|effective\s+date|effective\s+as\s+of|starting\s+on|commencing\s+on",
    _I,
)
_END = re.compile(
    r"fecha\s+de\s+(?:terminaci[oó]n|finalizaci[oó]n|vencimiento)|hasta\s+el|terminar[aá]\s+el|"
    r"finalizar[aá]\s+el|end\s+date|expiration\s+date|termination\s+date|ends?\s+on|ending\s+on|until|"
    r"expires?\s+on",
    _I,
)
_TYPE_ES = re.compile(
    r"\bcontrato\s+de\s+([a-záéíóúñ ]{3,60}?)(?=\s+(?:celebrado|suscrito|entre|n[°º.o]|que)\b|[\n.,:;(]|$)", _I
)
_TYPE_EN = re.compile(r"\b((?:[a-z]+\s+){0,3}[a-z]+)\s+agreement\b", _I)
_ARTICLE = re.compile(r"^(?:this|the|an?|el|la)\s+", _I)

_ORDINALS = (
    r"primera|segunda|tercera|cuarta|quinta|sexta|s[eé]ptima|octava|novena|d[eé]cima(?:\s+\w+)?|"
    r"und[eé]cima|duod[eé]cima"
)
_CLAUSE_HEADING = re.compile(
    rf"^(?:(?:cl[aá]usula|clause|art[ií]culo|article|section|secci[oó]n)\s+(?:\d+|{_ORDINALS}|[ivxlc]+)\b"
    rf"|(?:{_ORDINALS})\s*[.:\-–])",
    _I,
)
_MAX_CLAUSES = 10


class ContractExtractor(EntityExtractor):
    document_type = DocumentType.CONTRACT
    fields = {
        "parties": FieldSpec("string_list", "Names of the parties that sign the contract"),
        "start_date": FieldSpec("date", "Date the contract starts"),
        "end_date": FieldSpec("date", "Date the contract ends"),
        "contract_type": FieldSpec("string", "Kind of contract, e.g. lease, services, employment"),
        "important_clauses": FieldSpec("string_list", "Titles of the most relevant clauses"),
    }

    def extract(self, text: str, context: ExtractionContext) -> dict[str, Any]:
        lines = [line.strip() for line in text.split("\n") if line.strip()]
        # Los párrafos de un contrato se cortan en varias líneas: las búsquedas en prosa usan una sola línea
        joined = " ".join(lines)
        return {
            "parties": self._parties(lines, joined),
            "start_date": date_after(joined, _START),
            "end_date": date_after(joined, _END),
            "contract_type": self._contract_type(lines),
            "important_clauses": self._clauses(lines),
        }

    @staticmethod
    def _parties(lines: list[str], joined: str) -> list[str]:
        candidates: list[str | None] = [clean_name(m.group(1)) for line in lines if (m := _ROLE_LINE.match(line))]
        for pattern in (_BETWEEN_ES, _BETWEEN_EN):
            if match := pattern.search(joined):
                candidates.extend(clean_name(match.group(i)) for i in (1, 2))
        parties: list[str] = []
        for name in candidates:
            if name and name.lower() not in (p.lower() for p in parties):
                parties.append(name)
        return parties[:6]

    @staticmethod
    def _contract_type(lines: list[str]) -> str | None:
        head = "\n".join(lines[:15])
        if match := _TYPE_ES.search(head):
            return match.group(1).strip().lower()
        if match := _TYPE_EN.search(head):
            kind = _ARTICLE.sub("", match.group(1).strip()).lower()
            return kind or None
        return None

    @staticmethod
    def _clauses(lines: list[str]) -> list[str]:
        clauses: list[str] = []
        for line in lines:
            heading = _CLAUSE_HEADING.match(line)
            if not heading:
                continue
            # Solo el título de la cláusula, no su contenido: "CLÁUSULA TERCERA - VALOR: El arrendatario
            # pagará..." -> "CLÁUSULA TERCERA - VALOR"
            prefix = heading.group().strip(" .:-–")
            rest = line[heading.end():].strip(" .:-–")
            name = re.split(r":|\.\s|\.$", rest, maxsplit=1)[0].strip()[:80] if rest else ""
            title = f"{prefix} - {name}" if name else prefix
            if title not in clauses:
                clauses.append(title[:120])
            if len(clauses) == _MAX_CLAUSES:
                break
        return clauses
