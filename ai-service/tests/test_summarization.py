from app.llm.base import DisabledLlmService, LlmError
from app.models.schemas import DocumentType
from app.services.summarization import Summarizer, extractive_summary, template_summary
from tests import samples
from tests.conftest import FakeLlm


def test_invoice_template_summary_uses_only_extracted_data():
    entities = {"invoice_number": "FE-1", "supplier": "ACME S.A.S.", "customer": "Andina LTDA",
                "date": "2026-03-15", "total": 4_165_000.0, "currency": "COP"}
    assert template_summary(DocumentType.INVOICE, entities, "es") == (
        "Factura FE-1 emitida por ACME S.A.S. a Andina LTDA el 2026-03-15 por un total de 4.165.000 COP."
    )
    assert template_summary(DocumentType.INVOICE, entities, "en") == (
        "Invoice FE-1 issued by ACME S.A.S. to Andina LTDA on 2026-03-15 for a total of 4,165,000 COP."
    )


def test_template_is_skipped_without_data():
    assert template_summary(DocumentType.INVOICE, {"invoice_number": None}, "es") is None


def test_extractive_summary_keeps_document_order():
    sentences = extractive_summary(samples.REPORT_ES, 2)
    assert len(sentences) == 2
    assert samples.REPORT_ES.replace("\n", " ").find(sentences[0]) < samples.REPORT_ES.replace("\n", " ").find(
        sentences[1]
    )


def test_extractive_summary_skips_headings_and_contact_lines():
    sentences = extractive_summary(samples.REPORT_ES, 3) + extractive_summary(samples.RESUME_ES, 3)
    assert sentences
    assert all(s.endswith(".") for s in sentences)
    assert not any(s.startswith(("Informe trimestral", "Resumen ejecutivo", "María Fernanda")) for s in sentences)
    assert not any("@" in s for s in sentences)


def test_prose_documents_combine_template_and_extractive_sentences():
    entities = {"parties": ["A S.A.S.", "B LTDA"], "contract_type": "arrendamiento", "start_date": None,
                "end_date": None}
    summary = Summarizer(DisabledLlmService()).summarize(samples.CONTRACT_ES, DocumentType.CONTRACT, entities,
                                                         "es", [])
    assert summary.method == "EXTRACTIVE"
    assert summary.text.startswith("Contrato de arrendamiento entre A S.A.S. y B LTDA.")
    assert len(summary.text) > 60


def test_llm_summary_is_used_when_available():
    llm = FakeLlm({"summarization": "Factura de ACME por 4.165.000 COP."})
    summary = Summarizer(llm).summarize(samples.INVOICE_ES, DocumentType.INVOICE, {}, "es", [])
    assert (summary.text, summary.method) == ("Factura de ACME por 4.165.000 COP.", "LLM")
    assert "en español" in llm.calls[0]["system"]


def test_llm_failure_falls_back_to_extractive_summary():
    warnings: list[str] = []
    llm = FakeLlm({"summarization": LlmError("LLM request timed out")})
    summary = Summarizer(llm).summarize(samples.REPORT_ES, DocumentType.REPORT, {}, "es", warnings)
    assert summary.method == "EXTRACTIVE" and summary.text
    assert "timed out" in warnings[0]


def test_llm_summary_is_normalized_to_a_plain_paragraph():
    llm = FakeLlm({"summarization": "## Resumen\n- **Factura** FE-10234 de ACME SOLUCIONES S.A.S.\n"
                                    "- Total a pagar de 4.165.000 COP"})
    summary = Summarizer(llm).summarize(samples.INVOICE_ES, DocumentType.INVOICE, {}, "es", [])
    assert summary.text == "Resumen Factura FE-10234 de ACME SOLUCIONES S.A.S. Total a pagar de 4.165.000 COP"


def test_llm_summary_in_another_language_is_discarded():
    warnings: list[str] = []
    llm = FakeLlm({"summarization": "This is an invoice issued by ACME SOLUCIONES S.A.S. to Industrias Andinas LTDA "
                                    "for a total amount due of 4,165,000 with the payment terms of the agreement."})
    summary = Summarizer(llm).summarize(samples.REPORT_ES, DocumentType.REPORT, {}, "es", warnings)
    assert summary.method == "EXTRACTIVE"
    assert "instead of 'es'" in warnings[0]
