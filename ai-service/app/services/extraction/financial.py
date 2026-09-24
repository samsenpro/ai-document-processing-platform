"""Extractores de documentos con importes: facturas y recibos."""

import re
from typing import Any

from app.models.schemas import DocumentType
from app.services.extraction.base import EntityExtractor, ExtractionContext, FieldSpec
from app.services.extraction.parsing import (
    clean_name,
    company_lines,
    date_after,
    detect_currency,
    find_dates,
    labeled_amount,
    labeled_value,
)
from app.services.text_utils import fold

_I = re.IGNORECASE

# "electr\w?nica" tolera que el OCR lea mal la tilde ("ELECTRONICA", "ELECTRÓNICA", "ELECTRXNICA")
INVOICE_NUMBER = re.compile(
    r"\b(?:n[uú]mero\s+de\s+factura|invoice\s+number|factura(?:\s+electr\w?nica)?(?:\s+de\s+venta)?|invoice)"
    r"[ \t]*(?:n[o°º]\.?|n[uú]m(?:ero)?\.?|number|#)?[ \t]*[:#.]?[ \t]*([A-Z]{0,6}[- ]?\d[\w\-/]{0,20})",
    _I,
)
SUPPLIER = re.compile(
    r"^\s*(?:proveedor|vendedor|emisor|supplier|vendor|seller|from|raz[oó]n\s+social|empresa)\b", _I | re.M
)
CUSTOMER = re.compile(
    r"^\s*(?:cliente|customer|facturar\s+a|facturado\s+a|bill(?:ed)?\s+to|sold\s+to|adquiri?ente|comprador|"
    r"se[nñ]or(?:es)?|sres\.?)\b",
    _I,
)
ISSUE_DATE = re.compile(
    r"fecha\s+de\s+(?:emisi[oó]n|expedici[oó]n|factura(?:ci[oó]n)?)|invoice\s+date|issue\s+date|date\s+of\s+issue", _I
)
GENERIC_DATE = re.compile(r"\b(?:fecha|date)\b(?!\s+(?:de\s+)?(?:vencimiento|due|nacimiento|of\s+birth))", _I)
SUBTOTAL = re.compile(r"\bsub[\s-]?total\b", _I)
TAX = re.compile(r"\b(?:iva|impuestos?|tax(?:es)?|vat|igv)\b", _I)
TAX_EXCLUDE = re.compile(r"\b(?:tax\s+id|nit|rut|rfc|vat\s+(?:no|number|id))\b", _I)
TOTAL_LABELS = (
    re.compile(r"\btotal\s+a\s+pagar\b|\bamount\s+due\b|\btotal\s+due\b|\bgrand\s+total\b", _I),
    re.compile(r"\bvalor\s+total\b|\btotal\s+factura\b|\btotal\s+general\b", _I),
    re.compile(r"(?<!sub )(?<!sub-)\btotal\b", _I),
)
PAYMENT_METHODS = (
    (re.compile(r"\b(?:tarjeta\s+de\s+credito|credit\s+card)\b"), "CREDIT_CARD"),
    (re.compile(r"\b(?:tarjeta\s+debito|tarjeta\s+de\s+debito|debit\s+card)\b"), "DEBIT_CARD"),
    (re.compile(r"\b(?:visa|mastercard|amex|tarjeta|card)\b"), "CARD"),
    (re.compile(r"\b(?:transferencia|transfer|pse|nequi|daviplata)\b"), "TRANSFER"),
    (re.compile(r"\b(?:efectivo|cash)\b"), "CASH"),
)


def _lines(text: str) -> list[str]:
    return [line for line in text.split("\n") if line.strip()]


def _total(lines: list[str]) -> float | None:
    # Las etiquetas específicas ("total a pagar") ganan a la genérica ("total"); para la genérica se
    # toma la última aparición, porque el total final suele estar al pie del documento
    for index, label in enumerate(TOTAL_LABELS):
        value = labeled_amount(lines, label, prefer_last=index == len(TOTAL_LABELS) - 1)
        if value is not None:
            return value
    return None


def _issue_date(text: str, lines: list[str]) -> str | None:
    labeled = date_after(text, ISSUE_DATE) or next(
        (d for line in lines if GENERIC_DATE.search(line) and (d := date_after(line, GENERIC_DATE))), None
    )
    if labeled:
        return labeled
    dates = find_dates(text)
    return dates[0].isoformat() if dates else None


def _first_heading(lines: list[str], skip: re.Pattern[str]) -> str | None:
    """Primera línea con aspecto de nombre (la cabecera del documento suele ser el emisor)."""
    for line in lines[:5]:
        stripped = line.strip()
        if 2 < len(stripped) <= 80 and not skip.search(stripped) and sum(c.isalpha() for c in stripped) >= 3:
            return stripped
    return None


class InvoiceExtractor(EntityExtractor):
    document_type = DocumentType.INVOICE
    fields = {
        "invoice_number": FieldSpec("string", "Invoice number or identifier"),
        "supplier": FieldSpec("string", "Company or person that issues the invoice"),
        "customer": FieldSpec("string", "Company or person the invoice is billed to"),
        "date": FieldSpec("date", "Issue date"),
        "subtotal": FieldSpec("number", "Amount before taxes"),
        "tax": FieldSpec("number", "Total tax amount (VAT/IVA)"),
        "total": FieldSpec("number", "Total amount to pay"),
        "currency": FieldSpec("currency", "ISO 4217 currency code"),
    }

    _heading_skip = re.compile(r"factura|invoice|fecha|date|nit\b|\d{4,}", _I)

    def extract(self, text: str, context: ExtractionContext) -> dict[str, Any]:
        lines = _lines(text)
        number = INVOICE_NUMBER.search(text)
        customer = clean_name(labeled_value(lines, CUSTOMER))
        supplier = clean_name(labeled_value(lines, SUPPLIER))
        if not supplier:
            # Sin etiqueta: la primera razón social que no sea el cliente, o la cabecera
            companies = [clean_name(c) for c in company_lines(lines)]
            supplier = next((c for c in companies if c and c != customer), None) or _first_heading(
                lines, self._heading_skip
            )

        subtotal = labeled_amount(lines, SUBTOTAL)
        tax = labeled_amount(lines, TAX, exclude=TAX_EXCLUDE)
        total = _total(lines)
        if total is None and subtotal is not None and tax is not None:
            total = round(subtotal + tax, 2)

        return {
            "invoice_number": number.group(1).strip() if number else None,
            "supplier": supplier,
            "customer": customer,
            "date": _issue_date(text, lines),
            "subtotal": subtotal,
            "tax": tax,
            "total": total,
            "currency": detect_currency(text, context.language, context.default_currency),
        }


class ReceiptExtractor(EntityExtractor):
    document_type = DocumentType.RECEIPT
    fields = {
        "merchant": FieldSpec("string", "Store or business that issued the receipt"),
        "date": FieldSpec("date", "Purchase date"),
        "total": FieldSpec("number", "Total amount paid"),
        "tax": FieldSpec("number", "Tax amount included"),
        "currency": FieldSpec("currency", "ISO 4217 currency code"),
        "payment_method": FieldSpec("string", "CASH, CARD, CREDIT_CARD, DEBIT_CARD or TRANSFER"),
    }

    _heading_skip = re.compile(r"recibo|receipt|ticket|fecha|date|nit\b|\d{4,}", _I)

    def extract(self, text: str, context: ExtractionContext) -> dict[str, Any]:
        lines = _lines(text)
        companies = company_lines(lines)
        merchant = clean_name(companies[0]) if companies else _first_heading(lines, self._heading_skip)
        folded = fold(text)
        payment = next((code for pattern, code in PAYMENT_METHODS if pattern.search(folded)), None)
        return {
            "merchant": merchant,
            "date": _issue_date(text, lines),
            "total": _total(lines),
            "tax": labeled_amount(lines, TAX, exclude=TAX_EXCLUDE),
            "currency": detect_currency(text, context.language, context.default_currency),
            "payment_method": payment,
        }
