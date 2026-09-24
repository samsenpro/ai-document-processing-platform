# Diagramas de secuencia

## Subida de un documento

La petición termina en cuanto el documento está guardado y el job creado; el procesamiento ocurre
después, en segundo plano.

```mermaid
sequenceDiagram
    autonumber
    actor U as Usuario
    participant W as Frontend (nginx)
    participant A as API Java
    participant R as Redis
    participant S as File Storage
    participant P as PostgreSQL

    U->>W: Selecciona archivo
    W->>A: POST /api/v1/documents (multipart, JWT, X-Idempotency-Key)
    A->>R: Rate limit (INCR ratelimit:upload:{user})
    A->>A: Valida tamaño y firma binaria del archivo
    A->>R: SET NX idempotency:upload:{user}:{key}
    A->>S: Guarda el archivo (escritura atómica, SHA-256)
    A->>P: BEGIN · INSERT document (UPLOADED → PROCESSING)
    A->>P: INSERT document_processing (QUEUED) · INSERT audit_log
    A->>P: COMMIT
    A->>R: XADD documind:processing:jobs (tras el commit)
    A->>R: HSET document:processing:{id} stage=QUEUED
    A->>R: SET idempotency ... DONE|{documentId}
    A-->>W: 201 Created (status PROCESSING)
    W-->>U: Detalle del documento (consulta el estado cada 2 s)
```

## Procesamiento de un documento

```mermaid
sequenceDiagram
    autonumber
    participant R as Redis Stream
    participant WK as Worker (Java)
    participant P as PostgreSQL
    participant AI as Servicio de IA (Python)
    participant A as API Java /internal

    R->>WK: XREADGROUP (un solo worker recibe el job)
    WK->>P: QUEUED → RUNNING (bloqueo optimista)
    WK->>R: HSET stage=CALLING_AI_SERVICE
    WK->>AI: POST /api/v1/process {document_id, file_url} + clave interna + X-Correlation-Id
    AI->>A: GET /internal/v1/documents/{id}/content + clave interna
    A-->>AI: Archivo (solo si el documento está en PROCESSING)
    AI->>AI: Texto → (OCR) → limpieza → idioma → clasificación → entidades → resumen
    AI-->>WK: 200 {document_type, confidence, entities, summary, text, metadata}
    WK->>R: HSET stage=SAVING_RESULT
    WK->>P: BEGIN · INSERT document_result (JSONB) · processing COMPLETED · document COMPLETED · audit · COMMIT
    WK->>R: HSET stage=COMPLETED
    WK->>R: XACK
```

## Fallo del procesamiento

Si Python no responde, Resilience4j reintenta con espera exponencial; cuando el circuito se abre, los
jobs siguientes fallan al instante. Ningún documento queda bloqueado en `PROCESSING`.

```mermaid
sequenceDiagram
    autonumber
    participant WK as Worker (Java)
    participant RT as Retry
    participant CB as Circuit Breaker
    participant AI as Servicio de IA
    participant P as PostgreSQL
    actor U as Usuario

    WK->>RT: process(request)
    RT->>CB: intento 1
    CB->>AI: POST /api/v1/process
    AI--xCB: conexión rechazada / timeout / 5xx
    RT->>CB: intento 2 (tras 2 s)
    CB->>AI: POST /api/v1/process
    AI--xCB: fallo
    RT->>CB: intento 3 (tras 4 s)
    CB->>AI: POST /api/v1/process
    AI--xCB: fallo
    RT-->>WK: AiServiceUnavailableException
    WK->>P: processing FAILED (AI_SERVICE_UNAVAILABLE) · document FAILED · audit
    Note over CB: Con ≥ 50 % de fallos en las últimas llamadas el circuito se abre (30 s)
    WK->>CB: siguiente job
    CB-->>WK: CallNotPermittedException (sin llamar a Python)
    WK->>P: FAILED (AI_SERVICE_CIRCUIT_OPEN)
    U->>WK: POST /api/v1/documents/{id}/process (reintento cuando Python vuelve)
    Note over WK,P: FAILED → PROCESSING: nuevo intento (attempt 2)
```

Los errores del propio documento (formato no soportado, PDF corrupto, sin texto) llegan como 4xx: no
se reintentan, no abren el circuito y el procesamiento termina en `FAILED` con el código que devuelve
Python (`UNSUPPORTED_FORMAT`, `NO_TEXT_EXTRACTED`...).
