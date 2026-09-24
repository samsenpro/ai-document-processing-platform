import json

from app.llm.base import DisabledLlmService, LlmError
from app.models.schemas import DocumentType
from app.services.extraction.service import EntityExtractionService
from tests import samples
from tests.conftest import FakeLlm


def extract(text: str, document_type: DocumentType, language: str = "es", llm=None, warnings=None):
    service = EntityExtractionService(llm or DisabledLlmService(), "COP", 12_000)
    return service.extract(text, document_type, language, warnings if warnings is not None else [])


def test_spanish_invoice():
    result = extract(samples.INVOICE_ES, DocumentType.INVOICE)
    assert result.method == "RULES"
    assert result.entities == {
        "invoice_number": "FE-10234",
        "supplier": "ACME SOLUCIONES S.A.S.",
        "customer": "Industrias Andinas LTDA",
        "date": "2026-03-15",
        "subtotal": 3_500_000.0,
        "tax": 665_000.0,
        "total": 4_165_000.0,
        "currency": "COP",
    }


def test_invoice_number_tolerates_ocr_misreads():
    text = samples.INVOICE_ES.replace("ELECTRÓNICA", "ELECTRXNICA")
    assert extract(text, DocumentType.INVOICE).entities["invoice_number"] == "FE-10234"


def test_english_invoice():
    entities = extract(samples.INVOICE_EN, DocumentType.INVOICE, language="en").entities
    assert entities["invoice_number"] == "INV-2026-0042"
    assert entities["supplier"] == "Northwind Traders Inc."
    assert entities["customer"] == "Contoso Ltd."
    assert entities["date"] == "2026-03-03"
    assert (entities["subtotal"], entities["tax"], entities["total"]) == (1200.0, 102.0, 1302.0)
    assert entities["currency"] == "USD"


def test_receipt():
    entities = extract(samples.RECEIPT_ES, DocumentType.RECEIPT).entities
    assert entities["merchant"] == "SUPERMERCADO LA ECONOMÍA S.A.S."
    assert entities["date"] == "2026-05-02"
    assert entities["total"] == 10_300.0
    assert entities["tax"] == 1_645.0
    assert entities["payment_method"] == "CASH"


def test_spanish_contract():
    entities = extract(samples.CONTRACT_ES, DocumentType.CONTRACT).entities
    assert entities["parties"] == ["INMOBILIARIA LOS ANDES S.A.S.", "Juan Carlos Pérez Gómez"]
    assert entities["contract_type"] == "arrendamiento de vivienda urbana"
    assert entities["start_date"] == "2026-02-01"
    assert entities["end_date"] == "2027-01-31"
    assert entities["important_clauses"] == [
        "CLÁUSULA PRIMERA - OBJETO",
        "CLÁUSULA SEGUNDA - CANON",
        "CLÁUSULA TERCERA - DURACIÓN",
        "CLÁUSULA CUARTA - TERMINACIÓN",
    ]


def test_english_contract():
    entities = extract(samples.CONTRACT_EN, DocumentType.CONTRACT, language="en").entities
    assert entities["parties"] == ["Globex Corporation", "Initech LLC"]
    assert entities["contract_type"] == "software development services"
    assert entities["start_date"] == "2026-01-10"
    assert entities["end_date"] == "2026-12-31"
    assert entities["important_clauses"][0] == "Clause 1 - Scope of Services"


def test_resume():
    entities = extract(samples.RESUME_ES, DocumentType.RESUME).entities
    assert entities["full_name"] == "María Fernanda López Ruiz"
    assert entities["email"] == "maria.lopez@example.com"
    assert entities["phone"] == "+57 300 123 4567"
    assert entities["skills"] == ["Java", "Spring Boot", "Python", "FastAPI", "PostgreSQL", "Docker", "Kubernetes"]
    assert entities["education"] == ["Ingeniería de Sistemas - Universidad Nacional de Colombia"]


def test_identification():
    entities = extract(samples.IDENTIFICATION_ES, DocumentType.IDENTIFICATION).entities
    assert entities == {
        "document_number": "1020304050",
        "full_name": "JUAN CARLOS PÉREZ GÓMEZ",
        "birth_date": "1990-08-12",
        "expiry_date": None,
        "nationality": "Colombiana",
    }


def test_report():
    entities = extract(samples.REPORT_ES, DocumentType.REPORT).entities
    assert entities["title"] == "Informe trimestral de ventas - Primer trimestre 2026"
    assert entities["date"] == "2026-04-10"
    assert entities["sections"] == ["Resumen ejecutivo", "1. Introducción", "2. Resultados", "3. Conclusiones"]


def test_generic_document_only_extracts_universal_data():
    entities = extract(samples.OTHER_ES, DocumentType.OTHER).entities
    assert entities == {"dates": ["2026-06-05"], "amounts": [], "emails": ["coordinacion@example.com"],
                        "currency": None}


def test_llm_only_fills_fields_the_rules_did_not_find():
    llm = FakeLlm({"extraction": json.dumps({
        "expiry_date": "2034-09-20",
        "document_number": "999",  # no se pidió: nunca sustituye al valor de las reglas
    })})
    # Las reglas no reconocen esta forma de expresar el vencimiento; el LLM sí
    text = samples.IDENTIFICATION_ES + "Documento válido para trámites hasta el 20/09/2034"
    result = extract(text, DocumentType.IDENTIFICATION, llm=llm)
    assert result.method == "RULES+LLM"
    assert result.entities["expiry_date"] == "2034-09-20"
    assert result.entities["document_number"] == "1020304050"
    assert '"expiry_date"' in llm.calls[0]["system"] and '"document_number"' not in llm.calls[0]["system"]


def test_llm_values_not_present_in_the_document_are_discarded():
    warnings: list[str] = []
    llm = FakeLlm({"extraction": json.dumps({"currency": "CLP", "merchant": "Tienda Inventada"})})
    receipt = samples.RECEIPT_ES.replace("SUPERMERCADO LA ECONOMÍA S.A.S.", "")  # sin comercio ni moneda
    result = extract(receipt, DocumentType.RECEIPT, llm=llm, warnings=warnings)
    assert result.entities["currency"] is None
    assert result.entities["merchant"] != "Tienda Inventada"
    assert "currency" in warnings[0]


def test_llm_values_found_in_the_document_are_kept():
    llm = FakeLlm({"extraction": json.dumps({"currency": "COP", "invoice_number": "FE-10234"})})
    text = samples.INVOICE_ES.replace("No. FE-10234", "(ref FE-10234)").replace("$", "COP")
    entities = extract(text, DocumentType.INVOICE, llm=llm).entities
    assert entities["invoice_number"] == "FE-10234"
    assert entities["currency"] == "COP"


def test_invalid_llm_values_are_discarded():
    llm = FakeLlm({"extraction": json.dumps({"expiry_date": "not a date"})})
    result = extract(samples.IDENTIFICATION_ES, DocumentType.IDENTIFICATION, llm=llm)
    assert result.entities["expiry_date"] is None


def test_llm_is_not_called_when_rules_found_everything():
    llm = FakeLlm()
    result = extract(samples.INVOICE_ES, DocumentType.INVOICE, llm=llm)
    assert result.method == "RULES" and llm.calls == []


def test_llm_failure_keeps_rule_values():
    warnings: list[str] = []
    llm = FakeLlm({"extraction": LlmError("LLM provider returned HTTP 503")})
    result = extract(samples.IDENTIFICATION_ES, DocumentType.IDENTIFICATION, llm=llm, warnings=warnings)
    assert result.method == "RULES"
    assert result.entities["document_number"] == "1020304050"
    assert "HTTP 503" in warnings[0]
