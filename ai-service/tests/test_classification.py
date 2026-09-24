import pytest

from app.llm.base import DisabledLlmService, LlmError
from app.models.schemas import DocumentType, RequestedDocumentType
from app.services.classification import DocumentClassifier
from tests import samples
from tests.conftest import FakeLlm

AUTO = RequestedDocumentType.AUTO


@pytest.mark.parametrize(
    ("text", "expected"),
    [
        (samples.INVOICE_ES, DocumentType.INVOICE),
        (samples.INVOICE_EN, DocumentType.INVOICE),
        (samples.RECEIPT_ES, DocumentType.RECEIPT),
        (samples.CONTRACT_ES, DocumentType.CONTRACT),
        (samples.CONTRACT_EN, DocumentType.CONTRACT),
        (samples.RESUME_ES, DocumentType.RESUME),
        (samples.IDENTIFICATION_ES, DocumentType.IDENTIFICATION),
        (samples.REPORT_ES, DocumentType.REPORT),
        (samples.OTHER_ES, DocumentType.OTHER),
    ],
)
def test_rules_classify_each_document_type(text, expected):
    result = DocumentClassifier(DisabledLlmService()).classify(text, AUTO, [])
    assert result.document_type is expected
    assert result.method == "RULES"
    assert 0 < result.confidence <= 0.99


def test_strong_evidence_gives_high_confidence():
    result = DocumentClassifier(DisabledLlmService()).classify_by_rules(samples.INVOICE_ES)
    assert result.confidence >= 0.8


def test_user_selected_type_skips_classification():
    llm = FakeLlm()
    result = DocumentClassifier(llm).classify(samples.OTHER_ES, RequestedDocumentType.CONTRACT, [])
    assert (result.document_type, result.confidence, result.method) == (DocumentType.CONTRACT, 1.0, "USER")
    assert llm.calls == []


def test_llm_is_consulted_only_when_rules_are_not_confident():
    llm = FakeLlm({"classification": '{"document_type": "REPORT", "confidence": 0.83}'})
    classifier = DocumentClassifier(llm, llm_threshold=0.6)

    confident = classifier.classify(samples.INVOICE_ES, AUTO, [])
    assert confident.method == "RULES" and llm.calls == []

    uncertain = classifier.classify(samples.OTHER_ES, AUTO, [])
    assert (uncertain.document_type, uncertain.confidence, uncertain.method) == (DocumentType.REPORT, 0.83, "LLM")
    assert "<document>" in llm.calls[0]["user"]


def test_invalid_llm_answer_falls_back_to_rules_with_a_warning():
    llm = FakeLlm({"classification": '{"document_type": "POEM"}'})
    warnings: list[str] = []
    result = DocumentClassifier(llm).classify(samples.OTHER_ES, AUTO, warnings)
    assert result.method == "RULES" and result.document_type is DocumentType.OTHER
    assert warnings and "unknown document type" in warnings[0]


def test_llm_failure_falls_back_to_rules():
    llm = FakeLlm({"classification": LlmError("timeout")})
    warnings: list[str] = []
    result = DocumentClassifier(llm).classify(samples.OTHER_ES, AUTO, warnings)
    assert result.method == "RULES"
    assert warnings == ["LLM classification unavailable, rules were used: timeout"]
