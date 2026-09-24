import json
import logging
import sys
from datetime import UTC, datetime

from app.core.correlation import current_correlation_id

# Atributos estándar de LogRecord: todo lo demás que llegue en `extra` se añade al JSON
_RESERVED = set(logging.LogRecord("", 0, "", 0, "", None, None).__dict__) | {"message", "asctime"}


class JsonFormatter(logging.Formatter):
    """Una línea JSON por evento, con el correlation_id para seguir una petición entre servicios."""

    def __init__(self, service: str) -> None:
        super().__init__()
        self.service = service

    def format(self, record: logging.LogRecord) -> str:
        entry = {
            "timestamp": datetime.fromtimestamp(record.created, UTC).isoformat(timespec="milliseconds"),
            "level": record.levelname,
            "service": self.service,
            "logger": record.name,
            "correlation_id": current_correlation_id(),
            "message": record.getMessage(),
        }
        for key, value in record.__dict__.items():
            if key not in _RESERVED and not key.startswith("_"):
                entry[key] = value
        if record.exc_info:
            entry["exception"] = self.formatException(record.exc_info)
        return json.dumps(entry, ensure_ascii=False, default=str)


class TextFormatter(logging.Formatter):
    def __init__(self) -> None:
        super().__init__("%(asctime)s %(levelname)-5s [%(correlation_id)s] %(name)s - %(message)s")

    def format(self, record: logging.LogRecord) -> str:
        record.correlation_id = current_correlation_id()
        return super().format(record)


def configure_logging(level: str, fmt: str, service: str) -> None:
    handler = logging.StreamHandler(sys.stdout)
    handler.setFormatter(JsonFormatter(service) if fmt == "json" else TextFormatter())
    root = logging.getLogger()
    root.handlers[:] = [handler]
    root.setLevel(level.upper())
    # Las peticiones las registra el middleware de la aplicación, con su correlation_id
    logging.getLogger("uvicorn.access").disabled = True
    # httpx registra cada petición a nivel INFO, incluidas las URLs del LLM
    logging.getLogger("httpx").setLevel(logging.WARNING)
