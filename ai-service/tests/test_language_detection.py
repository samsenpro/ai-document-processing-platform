import pytest

from app.services.language_detection import LanguageDetector
from tests import samples

# Texto tal como sale de un PDF digital (sin dobles espacios): langdetect puro lo toma por neerlandés
INVOICE_EN_FROM_PDF = """Northwind Traders Inc.
123 Market Street, Seattle

INVOICE
Invoice Number: INV-2026-0042
Invoice Date: March 3, 2026
Bill To: Contoso Ltd.

Consulting services 10 h 1,200.00
Subtotal 1,200.00
Tax (8.5%) 102.00
Amount Due: USD 1,302.00

Payment terms: 30 days"""

FRENCH = (
    "Bonjour à tous, la réunion trimestrielle aura lieu demain matin dans la salle principale du siège. "
    "Merci de préparer vos présentations et d'apporter les chiffres de ventes de chaque région, ainsi que "
    "les prévisions pour le prochain semestre."
)


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        (samples.INVOICE_ES, "es"),
        (samples.RECEIPT_ES, "es"),
        (samples.IDENTIFICATION_ES, "es"),  # langdetect puro: portugués
        (samples.CONTRACT_ES, "es"),
        (samples.RESUME_ES, "es"),
        (samples.INVOICE_EN, "en"),
        (INVOICE_EN_FROM_PDF, "en"),
        (samples.CONTRACT_EN, "en"),
        (FRENCH, "fr"),  # fuera de es/en decide el modelo estadístico
    ],
)
def test_detects_the_document_language(text, expected):
    assert LanguageDetector().detect(text) == expected


def test_short_or_numeric_text_has_no_language():
    assert LanguageDetector().detect("12345 678 90") is None
    assert LanguageDetector().detect("Total") is None
