from prometheus_client import Counter, Histogram

# Se definen una sola vez por proceso: el registro global de prometheus_client no admite duplicados

HTTP_REQUESTS = Counter(
    "documind_ai_http_requests_total", "HTTP requests handled", ["method", "route", "status"]
)
HTTP_LATENCY = Histogram(
    "documind_ai_http_request_duration_seconds", "HTTP request latency", ["method", "route"]
)
DOCUMENTS_PROCESSED = Counter(
    "documind_ai_documents_processed_total", "Documents processed successfully", ["document_type"]
)
PROCESSING_ERRORS = Counter(
    "documind_ai_processing_errors_total", "Documents that could not be processed", ["error_code"]
)
PROCESSING_DURATION = Histogram(
    "documind_ai_processing_duration_seconds",
    "End-to-end pipeline duration",
    buckets=(0.1, 0.25, 0.5, 1, 2.5, 5, 10, 20, 30, 60, 120, 240),
)
STEP_DURATION = Histogram(
    "documind_ai_pipeline_step_duration_seconds",
    "Duration of each pipeline step",
    ["step"],
    buckets=(0.005, 0.01, 0.05, 0.1, 0.25, 0.5, 1, 2.5, 5, 10, 30, 60, 120),
)
OCR_PAGES = Counter("documind_ai_ocr_pages_total", "Pages or images processed with OCR")
LLM_REQUESTS = Counter(
    "documind_ai_llm_requests_total", "Requests sent to the LLM provider", ["operation", "outcome"]
)
