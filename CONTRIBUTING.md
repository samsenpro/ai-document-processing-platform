# Guía de contribución

Gracias por tu interés en DocuMind AI. Esta guía resume cómo trabajar en el repositorio.

## Ramas y commits

- `main` contiene solo versiones publicadas (commits con el formato `X.Y - descripción`).
- El desarrollo se hace en `dev` o en ramas que salen de `dev` (`feat/...`, `fix/...`).
- Los mensajes de commit usan prefijos: `feat:`, `fix:`, `test:`, `docs:`, `build:`, `refactor:`.

## Entorno de desarrollo

| Componente | Requisitos |
|---|---|
| Backend (`backend-java/`) | Java 21 (Maven Wrapper incluido) y Docker para los tests de integración |
| Servicio de IA (`ai-service/`) | Python 3.12+ y Tesseract, o solo Docker |
| Frontend (`frontend/`) | Node.js 20.19+ |

Para trabajar en un servicio concreto puedes levantar solo la infraestructura:

```bash
cp .env.example .env              # rellena los secretos
docker compose up -d postgres redis python-ai
```

## Tests

Todos los tests deben pasar antes de abrir un pull request.

```bash
# Backend: unitarios + integración (Testcontainers levanta PostgreSQL y Redis)
cd backend-java && ./mvnw verify

# Servicio de IA: tests y lint dentro de la imagen de tests (incluye Tesseract)
docker build --target test -t documind-ai-test ai-service
docker run --rm documind-ai-test
docker run --rm documind-ai-test ruff check app tests

# Frontend: compilación de producción (plantillas con strictTemplates)
cd frontend && npm ci && npx ng build
```

## Criterios de código

- Java: los controladores solo traducen HTTP; la lógica vive en servicios. Las llamadas al servicio de
  IA pasan siempre por `AiProcessingClient`, nunca desde un controlador.
- Python: cada paso del pipeline es un servicio independiente. El OCR y el LLM se usan a través de sus
  abstracciones (`OcrService`, `LlmService`).
- Ningún secreto en el código ni en los logs: toda configuración sensible llega por variables de entorno.
- Los errores de la API siguen RFC 7807 con un `code` estable; añade el código nuevo a `ErrorCode`.
- Cada cambio de comportamiento viene acompañado de su test.

## Reportar problemas

Abre un issue con los pasos para reproducirlo, el resultado esperado y el obtenido. Si el problema
ocurre en una petición concreta, incluye el `correlationId` de la respuesta: permite localizarla en los
logs de los dos servicios.
