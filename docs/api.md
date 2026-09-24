# API

La documentación interactiva (OpenAPI / Swagger UI) está en `http://localhost:8096/swagger-ui.html`
con el stack de Docker levantado. Los ejemplos usan el frontend como puerta de entrada
(`http://localhost:4300`), que reenvía `/api/**` al backend.

Todas las respuestas llevan la cabecera `X-Correlation-Id` (se respeta la que envíe el cliente).

## Autenticación

| Método | Ruta | Descripción |
|---|---|---|
| POST | `/api/v1/auth/register` | Crea una organización y su primer usuario (ADMIN). Devuelve tokens |
| POST | `/api/v1/auth/login` | Email y contraseña. Devuelve tokens |
| POST | `/api/v1/auth/refresh` | Cambia un refresh token por un par nuevo; el anterior queda revocado |

```bash
curl -s -X POST http://localhost:4300/api/v1/auth/register \
  -H 'Content-Type: application/json' \
  -d '{"email":"ana@acme.com","password":"S3cure-Passw0rd!","fullName":"Ana Gómez","organizationName":"ACME S.A.S."}'
```

```json
{
  "accessToken": "eyJhbGciOiJIUzI1NiJ9...",
  "refreshToken": "c2FtcGxlLXJlZnJlc2gtdG9rZW4...",
  "tokenType": "Bearer",
  "expiresIn": 900,
  "user": { "id": "7c0e...", "email": "ana@acme.com", "fullName": "Ana Gómez", "role": "ADMIN", "organizationId": "91d2..." }
}
```

El resto de endpoints requieren `Authorization: Bearer <accessToken>`.

## Documentos

| Método | Ruta | Descripción |
|---|---|---|
| POST | `/api/v1/documents` | Sube un documento (multipart `file`). Parámetros: `documentType` (`AUTO` por defecto), `autoProcess` (`true` por defecto). Admite `X-Idempotency-Key` |
| GET | `/api/v1/documents` | Lista paginada (`page`, `size`, `sort`) con filtros `status` y `type`. USER: sus documentos; ADMIN: los de la organización |
| GET | `/api/v1/documents/{id}` | Detalle con el último intento de procesamiento y las acciones permitidas |
| DELETE | `/api/v1/documents/{id}` | Borra el documento, su archivo y su resultado (409 si se está procesando) |
| GET | `/api/v1/documents/stats` | Totales por estado y tipo y tiempo medio de procesamiento (dashboard) |

Formatos aceptados: PDF, PNG, JPEG, TIFF, WEBP, DOCX y TXT, hasta 20 MB. El formato se detecta por la
firma binaria del archivo; el Content-Type declarado se ignora.

```bash
curl -s -X POST http://localhost:4300/api/v1/documents \
  -H "Authorization: Bearer $TOKEN" \
  -H "X-Idempotency-Key: upload-factura-marzo-001" \
  -F "file=@factura.pdf" -F "documentType=AUTO"
```

```json
{
  "id": "f2b7c1d0-5c1e-4d4e-9a55-2a8f2f0c3b11",
  "filename": "factura.pdf",
  "contentType": "application/pdf",
  "sizeBytes": 4087,
  "requestedType": "AUTO",
  "status": "PROCESSING",
  "latestProcessing": { "attempt": 1, "status": "QUEUED", "queuedAt": "2026-09-23T15:04:05Z" },
  "actions": { "process": false, "retry": false, "delete": false },
  "createdAt": "2026-09-23T15:04:05Z"
}
```

## Procesamiento y resultados

| Método | Ruta | Descripción |
|---|---|---|
| POST | `/api/v1/documents/{id}/process` | Procesa un documento `UPLOADED` o reintenta uno `FAILED`. Responde 202 al instante. Admite `X-Idempotency-Key` |
| GET | `/api/v1/documents/{id}/processing` | Estado: intento actual, etapa en tiempo real (Redis) e historial de intentos |
| GET | `/api/v1/documents/{id}/result` | Resultado estructurado (409 si el documento no está `COMPLETED`) |

```bash
curl -s http://localhost:4300/api/v1/documents/$ID/result -H "Authorization: Bearer $TOKEN"
```

```json
{
  "documentId": "f2b7c1d0-5c1e-4d4e-9a55-2a8f2f0c3b11",
  "documentType": "INVOICE",
  "confidence": 0.99,
  "language": "es",
  "summary": "Factura FE-10234 emitida por ACME SOLUCIONES S.A.S. a Industrias Andinas LTDA el 2026-03-15 por un total de 4.165.000 COP.",
  "entities": {
    "invoice_number": "FE-10234",
    "supplier": "ACME SOLUCIONES S.A.S.",
    "customer": "Industrias Andinas LTDA",
    "date": "2026-03-15",
    "subtotal": 3500000,
    "tax": 665000,
    "total": 4165000,
    "currency": "COP"
  },
  "text": "ACME SOLUCIONES S.A.S. ...",
  "processingTimeMs": 412,
  "metadata": {
    "source_format": "PDF", "page_count": 3, "ocr_used": false, "ocr_pages": 0,
    "classification_method": "RULES", "extraction_method": "RULES", "summary_method": "EXTRACTIVE",
    "llm_model": null, "warnings": []
  }
}
```

### Entidades por tipo de documento

| Tipo | Campos |
|---|---|
| `INVOICE` | `invoice_number`, `supplier`, `customer`, `date`, `subtotal`, `tax`, `total`, `currency` |
| `RECEIPT` | `merchant`, `date`, `total`, `tax`, `currency`, `payment_method` |
| `CONTRACT` | `parties[]`, `start_date`, `end_date`, `contract_type`, `important_clauses[]` |
| `RESUME` | `full_name`, `email`, `phone`, `skills[]`, `education[]` |
| `IDENTIFICATION` | `document_number`, `full_name`, `birth_date`, `expiry_date`, `nationality` |
| `REPORT` | `title`, `date`, `sections[]` |
| `OTHER` | `dates[]`, `amounts[]`, `emails[]`, `currency` |

Las fechas van en ISO 8601 y los importes como números. Un campo no encontrado vale `null` (o `[]`).

## Organización, usuarios y auditoría

| Método | Ruta | Rol | Descripción |
|---|---|---|---|
| GET | `/api/v1/users/me` | cualquiera | Perfil del usuario autenticado |
| GET | `/api/v1/organizations/me` | cualquiera | Organización del usuario |
| GET | `/api/v1/organizations/me/users` | ADMIN | Miembros de la organización |
| POST | `/api/v1/organizations/me/users` | ADMIN | Da de alta un miembro (`USER` o `ADMIN`) |
| GET | `/api/v1/audit-logs` | ADMIN | Eventos de auditoría (filtros `event` y `documentId`) |

Eventos: `USER_REGISTERED`, `USER_CREATED`, `DOCUMENT_UPLOADED`, `PROCESSING_STARTED`,
`PROCESSING_COMPLETED`, `PROCESSING_FAILED`, `DOCUMENT_DELETED`. Cada evento guarda usuario, fecha,
IP, documento y metadatos.

## Salud y métricas

| Ruta | Acceso |
|---|---|
| `GET /actuator/health` | Público (incluye probes `liveness` y `readiness`) |
| `GET /actuator/metrics` | ADMIN |
| `GET /actuator/prometheus` | Red interna (lo consulta Prometheus; nginx no lo expone) |

## Errores

Todas las respuestas de error siguen RFC 7807 (`application/problem+json`) con un `code` estable:

```json
{
  "type": "about:blank",
  "title": "Conflict",
  "status": 409,
  "detail": "Document cannot move from COMPLETED to PROCESSING",
  "code": "INVALID_STATUS_TRANSITION",
  "correlationId": "5ad398d5a27548ccbf28d01c72823a5f",
  "currentStatus": "COMPLETED",
  "targetStatus": "PROCESSING",
  "timestamp": "2026-09-23T15:05:10Z"
}
```

| Código | HTTP | Cuándo |
|---|---|---|
| `VALIDATION_FAILED` | 400 | Datos inválidos (el detalle por campo va en `errors`) |
| `INVALID_IDEMPOTENCY_KEY` | 400 | Clave con formato inválido (8-100 caracteres) |
| `AUTHENTICATION_REQUIRED` / `INVALID_CREDENTIALS` / `INVALID_REFRESH_TOKEN` | 401 | Sin sesión, credenciales incorrectas o refresh token inválido |
| `ACCESS_DENIED` | 403 | Operación reservada al ADMIN |
| `DOCUMENT_NOT_FOUND` | 404 | No existe o no pertenece al usuario |
| `INVALID_STATUS_TRANSITION` | 409 | Transición no permitida por la máquina de estados |
| `RESULT_NOT_AVAILABLE` | 409 | El documento aún no tiene resultado |
| `IDEMPOTENCY_REQUEST_IN_PROGRESS` | 409 | La primera petición con esa clave aún no terminó |
| `PAYLOAD_TOO_LARGE` | 413 | Archivo de más de 20 MB |
| `UNSUPPORTED_MEDIA_TYPE` | 415 | Formato no soportado |
| `IDEMPOTENCY_KEY_REUSED` | 422 | La clave ya se usó con otra petición |
| `RATE_LIMIT_EXCEEDED` | 429 | Límite superado (cabecera `Retry-After`) |
| `SERVICE_UNAVAILABLE` | 503 | Redis no disponible |

Códigos de error de un procesamiento fallido (`latestProcessing.errorCode`): los del servicio de IA
(`UNSUPPORTED_FORMAT`, `CORRUPTED_DOCUMENT`, `NO_TEXT_EXTRACTED`, `DOCUMENT_TOO_LARGE`,
`OCR_UNAVAILABLE`...) y los del backend (`AI_SERVICE_UNAVAILABLE`, `AI_SERVICE_TIMEOUT`,
`AI_SERVICE_CIRCUIT_OPEN`, `AI_INVALID_RESPONSE`, `QUEUE_UNAVAILABLE`, `PROCESSING_TIMEOUT`).

## Rate limiting

| Política | Límite | Clave |
|---|---|---|
| `auth` (registro, login, refresh) | 10 / minuto | IP |
| `upload` | 20 / minuto | usuario |
| `process` | 10 / minuto | usuario |
| `read` (listado, detalle, estadísticas) | 300 / minuto | usuario |

Las respuestas incluyen `X-RateLimit-Limit` y `X-RateLimit-Remaining`.

## API interna del servicio de IA (Python)

No se publica fuera de la red de Docker. Todas las peticiones a `/api/v1/process` requieren la
cabecera `X-Internal-Api-Key`.

| Método | Ruta | Descripción |
|---|---|---|
| POST | `/api/v1/process` | Ejecuta el pipeline sobre el documento de `file_url` |
| GET | `/api/v1/health` | Estado del servicio, del OCR y del LLM |
| GET | `/metrics/` | Métricas de Prometheus |

```json
{
  "document_id": "f2b7c1d0-5c1e-4d4e-9a55-2a8f2f0c3b11",
  "file_url": "http://java-api:8080/internal/v1/documents/f2b7c1d0-5c1e-4d4e-9a55-2a8f2f0c3b11/content",
  "document_type": "AUTO",
  "filename": "factura.pdf",
  "content_type": "application/pdf"
}
```

Errores: `{"error": {"code": "...", "message": "...", "correlation_id": "..."}}`. Los 4xx indican que
el documento no se puede procesar (Java no reintenta) y los 5xx, fallos transitorios (Java reintenta).
