import hmac

from fastapi import Header, Request

from app.core.errors import ErrorCode, ProcessingError

API_KEY_HEADER = "X-Internal-Api-Key"


def require_api_key(
    request: Request,
    x_internal_api_key: str | None = Header(default=None, alias=API_KEY_HEADER),
) -> None:
    """Solo el backend Java conoce la clave interna. La comparación es de tiempo constante."""
    expected = request.app.state.settings.ai_service_api_key.get_secret_value()
    if not x_internal_api_key or not hmac.compare_digest(x_internal_api_key.encode(), expected.encode()):
        raise ProcessingError(ErrorCode.UNAUTHORIZED, "Missing or invalid internal API key")
