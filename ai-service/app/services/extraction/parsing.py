"""Parsers de bajo nivel (importes, fechas, monedas, etiquetas) compartidos por los extractores.

Están pensados para documentos en español (formato colombiano) e inglés."""

import re
from collections import Counter
from datetime import date

from app.services.text_utils import fold

# ---- Importes ----

# "1.234.567,89", "1,234,567.89", "150.000", "1500.50", "42"
_AMOUNT = re.compile(r"(?<![\d.,])-?(?:\d{1,3}(?:[.,]\d{3})+(?:[.,]\d{1,2})?|\d+(?:[.,]\d{1,2})?)(?![\d%])(?!\s*%)")


def parse_amount(raw: str) -> float | None:
    """Convierte un importe escrito con cualquier convención de separadores a número."""
    value = re.sub(r"[^\d.,-]", "", raw)
    negative = value.startswith("-")
    value = value.lstrip("-")
    if not value or not any(c.isdigit() for c in value):
        return None
    if "," in value and "." in value:
        # El separador que aparece último es el decimal
        decimal = "," if value.rfind(",") > value.rfind(".") else "."
        thousands = "." if decimal == "," else ","
        value = value.replace(thousands, "").replace(decimal, ".")
    elif "," in value or "." in value:
        separator = "," if "," in value else "."
        parts = value.split(separator)
        # Varias apariciones o exactamente tres dígitos detrás: separador de miles ("150.000")
        is_thousands = len(parts) > 2 or len(parts[-1]) == 3
        value = value.replace(separator, "" if is_thousands else ".")
    try:
        number = float(value)
    except ValueError:
        return None
    return round(-number if negative else number, 2)


def find_amounts(text: str) -> list[float]:
    amounts = []
    for match in _AMOUNT.finditer(text):
        value = parse_amount(match.group())
        if value is not None:
            amounts.append(value)
    return amounts


# ---- Fechas ----

_MONTHS = {
    "enero": 1, "ene": 1, "january": 1, "jan": 1,
    "febrero": 2, "feb": 2, "february": 2,
    "marzo": 3, "mar": 3, "march": 3,
    "abril": 4, "abr": 4, "april": 4, "apr": 4,
    "mayo": 5, "may": 5,
    "junio": 6, "jun": 6, "june": 6,
    "julio": 7, "jul": 7, "july": 7,
    "agosto": 8, "ago": 8, "august": 8, "aug": 8,
    "septiembre": 9, "setiembre": 9, "sep": 9, "sept": 9, "september": 9,
    "octubre": 10, "oct": 10, "october": 10,
    "noviembre": 11, "nov": 11, "november": 11,
    "diciembre": 12, "dic": 12, "december": 12, "dec": 12,
}

_ISO_DATE = re.compile(r"\b(\d{4})[-/.](\d{1,2})[-/.](\d{1,2})\b")
_NUMERIC_DATE = re.compile(r"\b(\d{1,2})[/.-](\d{1,2})[/.-](\d{4}|\d{2})\b")
# "15 de marzo de 2026", "15 March 2026", "12-AGO-1990" (formato de la cédula colombiana)
_DAY_MONTH_YEAR = re.compile(
    r"\b(\d{1,2})(?:\s+(?:de\s+)?|[-/.])([a-z]{3,10})\.?,?(?:\s+(?:de\s+|del\s+)?|[-/.])(\d{4})\b"
)
_MONTH_DAY_YEAR = re.compile(r"\b([a-z]{3,9})\.?\s+(\d{1,2})(?:st|nd|rd|th)?,?\s+(?:de\s+)?(\d{4})\b")


def _safe_date(year: int, month: int, day: int) -> date | None:
    try:
        return date(year, month, day)
    except ValueError:
        return None


def find_dates(text: str) -> list[date]:
    """Fechas en orden de aparición (sin repetir). Las fechas numéricas se leen como día/mes/año,
    salvo que el segundo número no pueda ser un mes."""
    folded = fold(text)
    found: list[tuple[int, date]] = []

    for m in _ISO_DATE.finditer(folded):
        if d := _safe_date(int(m[1]), int(m[2]), int(m[3])):
            found.append((m.start(), d))
    for m in _NUMERIC_DATE.finditer(folded):
        day, month, year = int(m[1]), int(m[2]), int(m[3])
        if year < 100:
            year += 2000 if year < 70 else 1900
        if month > 12 and day <= 12:
            day, month = month, day
        if d := _safe_date(year, month, day):
            found.append((m.start(), d))
    for m in _DAY_MONTH_YEAR.finditer(folded):
        if (month := _MONTHS.get(m[2])) and (d := _safe_date(int(m[3]), month, int(m[1]))):
            found.append((m.start(), d))
    for m in _MONTH_DAY_YEAR.finditer(folded):
        if (month := _MONTHS.get(m[1])) and (d := _safe_date(int(m[3]), month, int(m[2]))):
            found.append((m.start(), d))

    ordered: list[date] = []
    for _, d in sorted(found, key=lambda item: item[0]):
        if d not in ordered:
            ordered.append(d)
    return ordered


def parse_date(raw: str) -> str | None:
    dates = find_dates(raw)
    return dates[0].isoformat() if dates else None


# ---- Monedas ----

_CURRENCY_CODES = re.compile(r"\b(COP|USD|EUR|MXN|ARS|CLP|PEN|BRL|GBP|CAD)\b")
_CURRENCY_WORDS = (
    ("pesos colombianos", "COP"),
    ("pesos mexicanos", "MXN"),
    ("dolares", "USD"),
    ("dollars", "USD"),
    ("euros", "EUR"),
)


def detect_currency(text: str, language: str | None, default_currency: str) -> str | None:
    codes = Counter(_CURRENCY_CODES.findall(text.upper()))
    if codes:
        return codes.most_common(1)[0][0]
    if "€" in text:
        return "EUR"
    if "£" in text:
        return "GBP"
    folded = fold(text)
    if "us$" in folded:
        return "USD"
    for word, code in _CURRENCY_WORDS:
        if word in folded:
            return code
    if "$" in text:
        # "$" solo es ambiguo: en español se asume la moneda local configurada
        return default_currency if language != "en" else "USD"
    return None


# ---- Contacto ----

EMAIL = re.compile(r"[\w.+-]+@[\w-]+(?:\.[\w-]+)+")
_INTERNATIONAL_PHONE = re.compile(r"\+\d{1,3}[\s.-]?(?:\(?\d{1,4}\)?[\s.-]?)?\d{3}[\s.-]?\d{3,4}(?:[\s.-]?\d{1,4})?")
_PHONE_LABEL = re.compile(r"\b(?:tel(?:efono)?|phone|movil|mobile|celular|cel)\b\.?\s*[:.]?\s*", re.IGNORECASE)
_PHONE_VALUE = re.compile(r"\+?[\d\s().-]{7,20}\d")


def find_emails(text: str) -> list[str]:
    return list(dict.fromkeys(EMAIL.findall(text)))


def find_phone(lines: list[str]) -> str | None:
    for line in lines:
        label = _PHONE_LABEL.search(fold(line))
        if label and (value := _PHONE_VALUE.search(line[label.end():])):
            return _clean_phone(value.group())
    for line in lines:
        if match := _INTERNATIONAL_PHONE.search(line):
            return _clean_phone(match.group())
    return None


def _clean_phone(raw: str) -> str | None:
    digits = re.sub(r"\D", "", raw)
    if not 7 <= len(digits) <= 15:
        return None
    return ("+" if raw.strip().startswith("+") else "") + " ".join(raw.replace("+", "").split())


# ---- Valores con etiqueta ("Cliente: ACME") ----

_SEPARATORS = " \t:;-–—#."
_TRAILING = " \t:;-–—#"  # el punto final se conserva: forma parte de "Ltd." o "S.A.S."


def labeled_value(lines: list[str], label: re.Pattern[str], max_length: int = 200) -> str | None:
    """Valor que sigue a la etiqueta en la misma línea o, si la línea termina en la etiqueta,
    en la siguiente línea no vacía (habitual en tablas y en texto de OCR)."""
    for index, line in enumerate(lines):
        match = label.search(line)
        if not match:
            continue
        value = line[match.end():].lstrip(_SEPARATORS).rstrip(_TRAILING)
        if not value:
            following = (nxt for nxt in lines[index + 1 : index + 3] if nxt.strip())
            value = next((nxt.lstrip(_SEPARATORS).rstrip(_TRAILING) for nxt in following), "")
        if value:
            return value[:max_length]
    return None


def labeled_amount(lines: list[str], label: re.Pattern[str], exclude: re.Pattern[str] | None = None,
                   prefer_last: bool = False) -> float | None:
    """Importe asociado a una etiqueta. Se toma el último importe de la línea (en "IVA 19% 190.000"
    el valor es 190.000) y, si la línea no tiene ninguno, el primero de la línea siguiente."""
    found: list[float] = []
    for index, line in enumerate(lines):
        match = label.search(line)
        if not match or (exclude and exclude.search(line)):
            continue
        amounts = find_amounts(line[match.end():])
        if not amounts and index + 1 < len(lines):
            amounts = find_amounts(lines[index + 1])[:1]
        if amounts:
            found.append(amounts[-1])
            if not prefer_last:
                break
    if not found:
        return None
    return found[-1] if prefer_last else found[0]


def date_after(text: str, label: re.Pattern[str], window: int = 80) -> str | None:
    """Primera fecha que aparece poco después de la etiqueta."""
    for match in label.finditer(text):
        if dates := find_dates(text[match.end() : match.end() + window]):
            return dates[0].isoformat()
    return None


# ---- Nombres de empresa ----

COMPANY_SUFFIX = re.compile(
    r"\b(?:S\.?\s?A\.?\s?S\.?|S\.?\s?A\.?|LTDA\.?|S\.?\s?EN\s?C\.?|E\.?\s?U\.?|INC\.?|LLC|LTD\.?|CORP\.?|"
    r"CORPORATION|GMBH|S\.?\s?L\.?|S\.?\s?DE\s?R\.?\s?L\.?)(?=[\s,.;)]|$)",
    re.IGNORECASE,
)
_TRAILING_ID = re.compile(r"[\s,;]+(?:con\s+)?(?:NIT|C\.?\s?C\.?|RUT|RFC|Tax\s+ID|VAT|identificad[oa])\b.*$",
                          re.IGNORECASE)


def clean_name(value: str | None) -> str | None:
    """Quita identificadores fiscales y puntuación sobrante de un nombre de persona o empresa."""
    if not value:
        return None
    value = _TRAILING_ID.sub("", value).strip(" \t,;:-–\"'“”")
    return value[:150] or None


def company_lines(lines: list[str]) -> list[str]:
    return [line.strip() for line in lines if COMPANY_SUFFIX.search(line) and len(line.strip()) <= 120]
