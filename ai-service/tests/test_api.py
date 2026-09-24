import json
import uuid

import httpx

from tests import samples
from tests.conftest import API_KEY, SOURCE_PREFIX, FakeLlm, FakeOcr
from tests.documents import scanned_pdf, text_pdf

DOC_ID = "5f1c8f5e-2b1d-4a57-9c1e-8c0d4f3b2a10"
URL = f"{SOURCE_PREFIX}{DOC_ID}/content"
HEADERS = {"X-Internal-Api-Key": API_KEY}


def payload(document_type: str = "AUTO", url: str = URL) -> dict:
    return {"document_id": DOC_ID, "file_url": url, "document_type": document_type, "filename": "doc.pdf"}


def test_process_requires_the_internal_api_key(client_factory):
    client = client_factory({URL: samples.INVOICE_ES.encode()})
    missing = client.post("/api/v1/process", json=payload())
    wrong = client.post("/api/v1/process", json=payload(), headers={"X-Internal-Api-Key": "wrong"})
    assert missing.status_code == wrong.status_code == 401
    assert missing.json()["error"]["code"] == "UNAUTHORIZED"


def test_processes_a_digital_invoice_end_to_end(client_factory):
    client = client_factory({URL: text_pdf(samples.INVOICE_ES.split("\n\n"))})
    response = client.post("/api/v1/process", json=payload(), headers=HEADERS)

    assert response.status_code == 200
    body = response.json()
    assert body["document_id"] == DOC_ID
    assert body["status"] == "COMPLETED"
    assert body["document_type"] == "INVOICE"
    assert body["confidence"] >= 0.8
    assert body["language"] == "es"
    assert body["entities"]["invoice_number"] == "FE-10234"
    assert body["entities"]["total"] == 4_165_000
    assert body["summary"].startswith("Factura FE-10234 emitida por ACME SOLUCIONES S.A.S.")
    assert body["metadata"]["source_format"] == "PDF"
    assert body["metadata"]["ocr_used"] is False
    assert body["metadata"]["classification_method"] == "RULES"
    assert body["metadata"]["llm_model"] is None
    assert body["processing_time_ms"] >= 0


def test_scanned_document_reports_ocr_usage(client_factory):
    ocr = FakeOcr(samples.RECEIPT_ES)
    client = client_factory({URL: scanned_pdf(["RECIBO"])}, ocr=ocr)
    body = client.post("/api/v1/process", json=payload(), headers=HEADERS).json()
    assert body["document_type"] == "RECEIPT"
    assert body["metadata"]["ocr_used"] is True and body["metadata"]["ocr_pages"] == 1
    assert body["entities"]["total"] == 10_300


def test_user_selected_type_is_respected(client_factory):
    client = client_factory({URL: samples.OTHER_ES.encode()})
    body = client.post("/api/v1/process", json=payload("CONTRACT"), headers=HEADERS).json()
    assert (body["document_type"], body["confidence"]) == ("CONTRACT", 1.0)
    assert body["metadata"]["classification_method"] == "USER"


def test_llm_results_and_model_are_reported(client_factory):
    llm = FakeLlm({
        "classification": json.dumps({"document_type": "REPORT", "confidence": 0.7}),
        "extraction": json.dumps({"title": "Otro título", "sections": ["Reunión de planeación", "Agenda"]}),
        "summarization": "Convocatoria a la reunión de planeación del 5 de junio.",
    })
    client = client_factory({URL: samples.OTHER_ES.encode()}, llm=llm)
    body = client.post("/api/v1/process", json=payload(), headers=HEADERS).json()
    assert body["document_type"] == "REPORT"
    # El título lo encuentran las reglas y se conserva; de las secciones del LLM solo queda la que
    # aparece en el documento
    assert body["entities"]["title"] == "Hola equipo,"
    assert body["entities"]["sections"] == ["Reunión de planeación"]
    assert body["summary"] == "Convocatoria a la reunión de planeación del 5 de junio."
    assert body["metadata"]["llm_model"] == "fake-model"
    assert body["metadata"]["extraction_method"] == "RULES+LLM"


def test_llm_outage_degrades_to_local_algorithms(client_factory):
    client = client_factory({URL: samples.CONTRACT_ES.encode()}, llm=FakeLlm())  # sin respuestas: todo falla
    body = client.post("/api/v1/process", json=payload(), headers=HEADERS).json()
    assert body["document_type"] == "CONTRACT"
    assert body["metadata"]["summary_method"] == "EXTRACTIVE"
    assert body["metadata"]["warnings"]


def test_empty_documents_are_rejected_as_unprocessable(client_factory):
    client = client_factory({URL: text_pdf([""])}, ocr=FakeOcr(""))
    response = client.post("/api/v1/process", json=payload(), headers=HEADERS)
    assert response.status_code == 422
    assert response.json()["error"]["code"] == "NO_TEXT_EXTRACTED"


def test_source_errors_are_reported_with_their_codes(client_factory):
    client = client_factory({URL: (409, b"")})
    rejected = client.post("/api/v1/process", json=payload(), headers=HEADERS)
    assert (rejected.status_code, rejected.json()["error"]["code"]) == (422, "DOCUMENT_SOURCE_REJECTED")

    ssrf = client.post("/api/v1/process", json=payload(url="http://169.254.169.254/latest/meta-data"),
                       headers=HEADERS)
    assert (ssrf.status_code, ssrf.json()["error"]["code"]) == (422, "INVALID_SOURCE_URL")


def test_invalid_payload(client_factory):
    response = client_factory().post("/api/v1/process", json={"document_id": "not-a-uuid"}, headers=HEADERS)
    assert response.status_code == 422
    assert response.json()["error"]["code"] == "INVALID_REQUEST"


def test_correlation_id_is_propagated_to_the_source_and_the_response(client_factory):
    seen: list[httpx.Request] = []
    client = client_factory({URL: samples.INVOICE_ES.encode()}, seen=seen)
    correlation_id = uuid.uuid4().hex
    response = client.post("/api/v1/process", json=payload(),
                           headers={**HEADERS, "X-Correlation-Id": correlation_id})
    assert response.headers["X-Correlation-Id"] == correlation_id
    assert seen[0].headers["X-Correlation-Id"] == correlation_id


def test_unexpected_errors_do_not_leak_details(client_factory, monkeypatch):
    client = client_factory({URL: samples.INVOICE_ES.encode()})

    def boom(*args, **kwargs):
        raise RuntimeError("secret internal detail")

    monkeypatch.setattr(client.app.state.pipeline, "process", boom)
    response = client.post("/api/v1/process", json=payload(), headers=HEADERS)
    assert response.status_code == 500
    assert response.json()["error"] == {
        "code": "INTERNAL_ERROR",
        "message": "Unexpected error while processing the document",
        "correlation_id": response.headers["X-Correlation-Id"],
    }


def test_health_reports_components(client_factory):
    body = client_factory(ocr=FakeOcr(available=False)).get("/api/v1/health").json()
    assert body["status"] == "DEGRADED"
    assert body["ocr"] == {"provider": "fake", "available": False}
    assert body["llm"] == {"enabled": False, "model": None}


def test_metrics_are_exposed(client_factory):
    client = client_factory({URL: samples.INVOICE_ES.encode()})
    client.post("/api/v1/process", json=payload(), headers=HEADERS)
    metrics = client.get("/metrics/").text
    assert 'documind_ai_documents_processed_total{document_type="INVOICE"}' in metrics
    assert "documind_ai_pipeline_step_duration_seconds_bucket" in metrics
    assert 'route="/api/v1/process"' in metrics
