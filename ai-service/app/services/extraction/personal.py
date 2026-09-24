"""Extractores de documentos de personas: hojas de vida y documentos de identidad."""

import re
from typing import Any

from app.models.schemas import DocumentType
from app.services.extraction.base import EntityExtractor, ExtractionContext, FieldSpec
from app.services.extraction.parsing import date_after, find_emails, find_phone, labeled_value

_I = re.IGNORECASE

_SECTION_HEADER = re.compile(
    r"^(?:experiencia(?:\s+(?:laboral|profesional))?|(?:work|professional)\s+experience|experience|"
    r"educaci[oó]n|education|formaci[oó]n(?:\s+acad[eé]mica)?|estudios|habilidades|skills|technical\s+skills|"
    r"competencias|conocimientos(?:\s+t[eé]cnicos)?|tecnolog[ií]as|herramientas|idiomas|languages|"
    r"referencias|references|perfil(?:\s+profesional)?|profile|summary|resumen|certificaciones|certifications|"
    r"proyectos|projects|contacto|contact|datos\s+personales|logros|achievements|intereses|interests)\s*:?",
    _I,
)
_SKILLS_HEADER = re.compile(
    r"^(?:habilidades|skills|technical\s+skills|competencias|conocimientos(?:\s+t[eé]cnicos)?|tecnolog[ií]as|"
    r"herramientas)\s*:?",
    _I,
)
_EDUCATION_HEADER = re.compile(r"^(?:educaci[oó]n|education|formaci[oó]n(?:\s+acad[eé]mica)?|estudios)\s*:?", _I)
_NAME_LABEL = re.compile(r"^\s*(?:nombre(?:\s+completo)?|full\s+name|name)\b", _I)
_NOT_A_NAME = re.compile(r"hoja\s+de\s+vida|curr[ií]cul|resume|\bcv\b|@|\d|:", _I)
_ITEM_SPLIT = re.compile(r"[,;•·|]|\s+-\s+|^\s*[-*]\s*")


def _section(lines: list[str], header: re.Pattern[str], max_lines: int = 12) -> list[str]:
    """Líneas de una sección: desde su cabecera hasta la siguiente cabecera conocida."""
    for index, line in enumerate(lines):
        match = header.match(line)
        if not match:
            continue
        content = [line[match.end():].strip()] if line[match.end():].strip() else []
        for following in lines[index + 1 : index + 1 + max_lines]:
            if _SECTION_HEADER.match(following) and len(following) < 40:
                break
            content.append(following)
        return content
    return []


class ResumeExtractor(EntityExtractor):
    document_type = DocumentType.RESUME
    fields = {
        "full_name": FieldSpec("string", "Candidate full name"),
        "email": FieldSpec("string", "Contact email"),
        "phone": FieldSpec("string", "Contact phone number"),
        "skills": FieldSpec("string_list", "Technical and professional skills"),
        "education": FieldSpec("string_list", "Degrees or studies"),
    }

    def extract(self, text: str, context: ExtractionContext) -> dict[str, Any]:
        lines = [line.strip() for line in text.split("\n") if line.strip()]
        emails = find_emails(text)
        skills: list[str] = []
        for line in _section(lines, _SKILLS_HEADER):
            skills.extend(item.strip(" .") for item in _ITEM_SPLIT.split(line) if 1 < len(item.strip(" .")) <= 60)
        return {
            "full_name": labeled_value(lines, _NAME_LABEL) or self._name_from_heading(lines),
            "email": emails[0] if emails else None,
            "phone": find_phone(lines),
            "skills": skills,
            "education": [line.lstrip("-*• ") for line in _section(lines, _EDUCATION_HEADER, max_lines=6)],
        }

    @staticmethod
    def _name_from_heading(lines: list[str]) -> str | None:
        # En una hoja de vida el nombre suele ser la primera línea: 2 a 5 palabras sin cifras
        for line in lines[:4]:
            words = line.split()
            if 2 <= len(words) <= 5 and not _NOT_A_NAME.search(line) and all(w[0].isupper() for w in words):
                return line
        return None


_DOCUMENT_NUMBER = re.compile(
    r"(?:c[eé]dula(?:\s+de\s+ciudadan[ií]a)?|c\.\s?c\.|nuip|dni|documento(?:\s+de\s+identidad)?|"
    r"passport\s+(?:no|number)\.?|document\s+(?:no|number)\.?|n[uú]mero|no\.)\s*[:.#]?\s*"
    r"([A-Z]{0,2}\s?\d[\d.\s]{4,16}\d)",
    _I,
)
_SURNAMES = re.compile(r"^\s*(?:apellidos?|surnames?)\b", _I)
_GIVEN_NAMES = re.compile(r"^\s*(?:nombres?|given\s+names?)\b(?!\s+completo)", _I)
_FULL_NAME = re.compile(r"^\s*(?:nombre\s+completo|full\s+name|name)\b", _I)
_NATIONALITY = re.compile(r"^\s*(?:nacionalidad|nationality)\b", _I)
_BIRTH = re.compile(r"fecha\s+de\s+nacimiento|date\s+of\s+birth|nacimiento|born", _I)
_EXPIRY = re.compile(
    r"fecha\s+de\s+(?:vencimiento|expiraci[oó]n)|date\s+of\s+expiry|expiry\s+date|expiration\s+date|"
    r"v[aá]lid[oa]\s+hasta|valid\s+until",
    _I,
)


class IdentificationExtractor(EntityExtractor):
    document_type = DocumentType.IDENTIFICATION
    fields = {
        "document_number": FieldSpec("string", "Identity document number"),
        "full_name": FieldSpec("string", "Holder full name"),
        "birth_date": FieldSpec("date", "Date of birth"),
        "expiry_date": FieldSpec("date", "Expiration date of the document"),
        "nationality": FieldSpec("string", "Nationality of the holder"),
    }

    def extract(self, text: str, context: ExtractionContext) -> dict[str, Any]:
        lines = [line.strip() for line in text.split("\n") if line.strip()]
        number = _DOCUMENT_NUMBER.search(text)
        surnames = labeled_value(lines, _SURNAMES, 80)
        given = labeled_value(lines, _GIVEN_NAMES, 80)
        full_name = " ".join(part for part in (given, surnames) if part) or labeled_value(lines, _FULL_NAME, 120)
        return {
            "document_number": re.sub(r"[\s.]", "", number.group(1)) if number else None,
            "full_name": full_name or None,
            "birth_date": date_after(text, _BIRTH),
            "expiry_date": date_after(text, _EXPIRY),
            "nationality": labeled_value(lines, _NATIONALITY, 60),
        }
