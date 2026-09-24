package com.documind.support;

import com.github.tomakehurst.wiremock.client.MappingBuilder;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;

/** Respuestas simuladas del servicio de IA (Python) con el mismo contrato que el real. */
public final class AiServiceStubs {

    private AiServiceStubs() {
    }

    public static MappingBuilder processEndpoint() {
        return post(urlEqualTo("/api/v1/process"));
    }

    /**
     * Resultado de una factura. WireMock copia el document_id de la petición en la respuesta, como
     * hace el servicio real.
     */
    public static ResponseDefinitionBuilder invoiceResult() {
        return aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/json")
                .withTransformers("response-template")
                .withBody("""
                        {
                          "document_id": "{{jsonPath request.body '$.document_id'}}",
                          "status": "COMPLETED",
                          "document_type": "INVOICE",
                          "confidence": 0.94,
                          "language": "es",
                          "text": "FACTURA ELECTRONICA DE VENTA No. FE-10234 ... Total a pagar: $ 4.165.000",
                          "summary": "Factura FE-10234 emitida por ACME S.A.S. por un total de 4.165.000 COP.",
                          "entities": {
                            "invoice_number": "FE-10234",
                            "supplier": "ACME S.A.S.",
                            "customer": "Industrias Andinas LTDA",
                            "date": "2026-03-15",
                            "subtotal": 3500000,
                            "tax": 665000,
                            "total": 4165000,
                            "currency": "COP"
                          },
                          "processing_time_ms": 120,
                          "metadata": {"source_format": "PDF", "ocr_used": false, "warnings": []}
                        }""");
    }

    public static ResponseDefinitionBuilder error(int status, String code) {
        return aResponse()
                .withStatus(status)
                .withHeader("Content-Type", "application/json")
                .withBody("""
                        {"error": {"code": "%s", "message": "simulated %s", "correlation_id": "test"}}"""
                        .formatted(code, code));
    }
}
