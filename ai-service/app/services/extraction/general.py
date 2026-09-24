"""Extractores de informes y de documentos sin tipo específico."""

import re
from typing import Any

from app.models.schemas import DocumentType
from app.services.extraction.base import EntityExtractor, ExtractionContext, FieldSpec
from app.services.extraction.parsing import detect_currency, find_amounts, find_dates, find_emails

_I = re.IGNORECASE

_NUMBERED_HEADING = re.compile(r"^\d{1,2}(?:\.\d{1,2})*\.?\s+[A-ZÁÉÍÓÚÑ][^\n]{2,80}$")
_KNOWN_HEADING = re.compile(
    r"^(?:resumen\s+ejecutivo|executive\s+summary|introducci[oó]n|introduction|antecedentes|background|"
    r"objetivos?|objectives?|metodolog[ií]a|methodology|resultados|results|hallazgos|findings|an[aá]lisis|"
    r"analysis|discusi[oó]n|discussion|conclusi[oó]n(?:es)?|conclusions?|recomendaciones|recommendations|"
    r"anexos?|appendix|referencias|references)\s*:?$",
    _I,
)
_MONEY = re.compile(r"(?:[$€£]|\b(?:COP|USD|EUR|MXN)\b)\s?[\d.,]+\d|[\d.,]*\d\s?(?:COP|USD|EUR|MXN)\b")


class ReportExtractor(EntityExtractor):
    document_type = DocumentType.REPORT
    fields = {
        "title": FieldSpec("string", "Title of the report"),
        "date": FieldSpec("date", "Date of the report"),
        "sections": FieldSpec("string_list", "Main section headings"),
    }

    def extract(self, text: str, context: ExtractionContext) -> dict[str, Any]:
        lines = [line.strip() for line in text.split("\n") if line.strip()]
        title = next((line for line in lines[:3] if 3 <= len(line) <= 150), None)
        sections: list[str] = []
        for line in lines:
            if (_NUMBERED_HEADING.match(line) or _KNOWN_HEADING.match(line)) and line != title:
                heading = line.rstrip(":")
                if heading not in sections:
                    sections.append(heading)
        dates = find_dates(text)
        return {"title": title, "date": dates[0].isoformat() if dates else None, "sections": sections[:15]}


class GenericExtractor(EntityExtractor):
    """Para OTHER: solo datos que se pueden reconocer en cualquier documento."""

    document_type = DocumentType.OTHER
    fields = {
        "dates": FieldSpec("string_list", "Dates mentioned in the document (ISO 8601)"),
        "amounts": FieldSpec("number_list", "Monetary amounts mentioned in the document"),
        "emails": FieldSpec("string_list", "Email addresses"),
        "currency": FieldSpec("currency", "ISO 4217 currency code of the amounts"),
    }

    def extract(self, text: str, context: ExtractionContext) -> dict[str, Any]:
        # Solo cuentan como importes las cifras con símbolo o código de moneda: sin eso cualquier
        # número (años, referencias, teléfonos) parecería un importe
        amounts = [value for match in _MONEY.finditer(text) for value in find_amounts(match.group())[:1]]
        return {
            "dates": [d.isoformat() for d in find_dates(text)[:10]],
            "amounts": amounts[:10],
            "emails": find_emails(text)[:10],
            "currency": detect_currency(text, context.language, context.default_currency),
        }
