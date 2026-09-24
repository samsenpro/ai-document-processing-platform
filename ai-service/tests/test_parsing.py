from datetime import date

import pytest

from app.services.extraction.parsing import (
    clean_name,
    detect_currency,
    find_amounts,
    find_dates,
    find_phone,
    parse_amount,
    parse_date,
)
from app.services.text_cleaning import TextCleaner


@pytest.mark.parametrize(
    ("raw", "expected"),
    [
        ("$ 1.500.000", 1_500_000),
        ("1.234.567,89", 1_234_567.89),
        ("1,234,567.89", 1_234_567.89),
        ("150.000", 150_000),
        ("1500.50", 1500.5),
        ("12,5", 12.5),
        ("USD 1,302.00", 1302),
        ("-45", -45),
        ("abc", None),
    ],
)
def test_parse_amount_handles_both_separator_conventions(raw, expected):
    assert parse_amount(raw) == expected


def test_find_amounts_ignores_percentages():
    assert find_amounts("IVA 19%: $ 665.000") == [665_000]


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        ("Fecha: 15/03/2026", date(2026, 3, 15)),
        ("Date: 2026-03-15", date(2026, 3, 15)),
        ("el 1 de febrero de 2026", date(2026, 2, 1)),
        ("March 3, 2026", date(2026, 3, 3)),
        ("12-AGO-1990", date(1990, 8, 12)),
        ("03/25/2026", date(2026, 3, 25)),  # el segundo número no puede ser mes: formato mm/dd
        ("15.03.26", date(2026, 3, 15)),
    ],
)
def test_find_dates_supports_spanish_and_english_formats(text, expected):
    assert find_dates(text) == [expected]


def test_find_dates_skips_impossible_dates_and_keeps_order():
    assert find_dates("31/02/2026 luego 01/04/2026 y 2026-01-10") == [date(2026, 4, 1), date(2026, 1, 10)]


def test_parse_date_returns_iso_string():
    assert parse_date("15 de marzo de 2026") == "2026-03-15"
    assert parse_date("sin fecha") is None


@pytest.mark.parametrize(
    ("text", "language", "expected"),
    [
        ("Total USD 100", "es", "USD"),
        ("Total € 100", "en", "EUR"),
        ("Total $ 100.000", "es", "COP"),
        ("Total $ 100", "en", "USD"),
        ("valor en pesos colombianos", "es", "COP"),
        ("sin importes", "es", None),
    ],
)
def test_detect_currency(text, language, expected):
    assert detect_currency(text, language, "COP") == expected


def test_find_phone_prefers_labeled_numbers():
    assert find_phone(["Factura 900123456", "Tel: +57 300 123 4567"]) == "+57 300 123 4567"


def test_clean_name_removes_tax_ids():
    assert clean_name("Industrias Andinas LTDA NIT 800.987.654-1") == "Industrias Andinas LTDA"
    assert clean_name("Juan Pérez, identificado con cédula 123") == "Juan Pérez"


def test_text_cleaner_normalizes_whitespace_and_hyphenation():
    raw = "Factu-\nración   electrónica\r\n\r\n\r\n\r\nTotal:\t 100\x00"
    assert TextCleaner().clean(raw) == "Facturación electrónica\n\nTotal: 100"
