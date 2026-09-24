from functools import lru_cache

from pydantic import Field, SecretStr
from pydantic_settings import BaseSettings, SettingsConfigDict


class Settings(BaseSettings):
    """Configuración del servicio. Todo valor sensible llega por variables de entorno."""

    model_config = SettingsConfigDict(extra="ignore", case_sensitive=False)

    service_name: str = "documind-ai-service"
    log_level: str = "INFO"
    log_format: str = Field(default="json", pattern="^(json|text)$")

    # Clave interna compartida con el backend Java (cabecera X-Internal-Api-Key)
    ai_service_api_key: SecretStr = Field(min_length=32)

    # Solo se descargan documentos de estos prefijos (protección contra SSRF). Separados por comas.
    document_source_allowed_prefixes: str = "http://java-api:8080/internal/v1/documents/"
    download_timeout_seconds: float = Field(default=30.0, gt=0)
    max_document_bytes: int = Field(default=20 * 1024 * 1024, gt=0)
    max_pages: int = Field(default=50, gt=0)
    max_text_chars: int = Field(default=200_000, gt=0)

    # OCR
    ocr_provider: str = "tesseract"
    ocr_enabled: bool = True
    ocr_languages: str = "spa+eng"
    ocr_dpi: int = Field(default=200, ge=72, le=600)
    ocr_timeout_seconds: float = Field(default=60.0, gt=0)
    # Una página de PDF con menos caracteres que esto se considera escaneada y pasa por OCR
    ocr_min_chars_per_page: int = Field(default=25, ge=0)

    # LLM (opcional). Sin LLM_BASE_URL o LLM_MODEL el pipeline usa solo sus algoritmos locales.
    llm_api_key: SecretStr = SecretStr("")
    llm_model: str = ""
    llm_base_url: str = ""
    llm_timeout_seconds: float = Field(default=60.0, gt=0)
    llm_temperature: float = Field(default=0.1, ge=0, le=2)
    llm_max_input_chars: int = Field(default=12_000, gt=0)
    # Si la clasificación por reglas no alcanza esta confianza se consulta al LLM
    llm_classification_threshold: float = Field(default=0.6, ge=0, le=1)

    # Moneda asumida cuando un importe solo lleva "$" (en Colombia "$" es el peso)
    default_currency: str = Field(default="COP", min_length=3, max_length=3)
    summary_max_sentences: int = Field(default=3, gt=0)

    @property
    def allowed_source_prefixes(self) -> list[str]:
        return [p.strip() for p in self.document_source_allowed_prefixes.split(",") if p.strip()]

    @property
    def llm_enabled(self) -> bool:
        return bool(self.llm_base_url.strip() and self.llm_model.strip())


@lru_cache
def get_settings() -> Settings:
    return Settings()
