import re
import uuid
from contextvars import ContextVar

CORRELATION_HEADER = "X-Correlation-Id"

_VALID = re.compile(r"^[A-Za-z0-9._-]{1,64}$")

# El contexto se copia al threadpool donde FastAPI ejecuta los endpoints síncronos,
# así que el ID llega a todos los logs del pipeline
correlation_id_var: ContextVar[str] = ContextVar("correlation_id", default="-")


def resolve_correlation_id(value: str | None) -> str:
    """Reutiliza el ID recibido si es seguro para los logs; si no, genera uno nuevo."""
    if value and _VALID.match(value):
        return value
    return uuid.uuid4().hex


def current_correlation_id() -> str:
    return correlation_id_var.get()
