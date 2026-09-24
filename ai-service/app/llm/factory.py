from app.core.config import Settings
from app.llm.base import DisabledLlmService, LlmService
from app.llm.openai_compatible import OpenAICompatibleLlmService


def create_llm_service(settings: Settings) -> LlmService:
    """Sin LLM_BASE_URL y LLM_MODEL el LLM queda desactivado y el pipeline sigue funcionando."""
    if not settings.llm_enabled:
        return DisabledLlmService()
    return OpenAICompatibleLlmService(
        base_url=settings.llm_base_url,
        model=settings.llm_model,
        api_key=settings.llm_api_key.get_secret_value(),
        timeout_seconds=settings.llm_timeout_seconds,
        temperature=settings.llm_temperature,
    )
