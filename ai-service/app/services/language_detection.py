import re

from langdetect import DetectorFactory, LangDetectException, detect_langs

from app.services.text_utils import fold

# langdetect es probabilístico: con semilla fija el mismo texto da siempre el mismo resultado
DetectorFactory.seed = 0

_LETTERS = re.compile(r"[^\W\d_]", re.UNICODE)
_WORD = re.compile(r"[a-z]+")

# Palabras propias de cada idioma (sin las compartidas con francés, portugués o italiano, como
# "de", "la" o "en"), incluido el vocabulario habitual de los documentos de negocio
_KEYWORDS = {
    "es": set(
        "el los las del que por con para una y fecha factura numero nombre nombres apellidos cliente valor pago "
        "cedula nacimiento lugar contrato senor senores direccion cantidad descripcion vencimiento informe "
        "experiencia habilidades clausula arrendador arrendatario pagar recibo gracias compra".split()
    ),
    "en": set(
        "the of and to with is this that from by date invoice bill amount due payment terms number name "
        "agreement services street birth experience skills report description quantity whereas hereby "
        "receipt thank purchase".split()
    ),
}
# Por encima de esta longitud y probabilidad, langdetect es fiable y se usa directamente
_RELIABLE_LETTERS = 200
_RELIABLE_PROBABILITY = 0.99


class LanguageDetector:
    """Detecta el idioma del documento.

    langdetect falla con textos cortos y muy estructurados (una cédula o una factura son listas de
    etiquetas y cifras, no prosa). Por eso solo se confía en él cuando el texto es largo y el modelo
    está muy seguro; en otro caso deciden las palabras propias del español y el inglés, los idiomas
    principales del dominio, y langdetect queda como respaldo para el resto de idiomas."""

    def __init__(self, min_letters: int = 20, min_probability: float = 0.5, sample_chars: int = 5000) -> None:
        self._min_letters = min_letters
        self._min_probability = min_probability
        self._sample_chars = sample_chars

    def detect(self, text: str) -> str | None:
        """Código ISO 639-1 del idioma ("es", "en"...) o None si el texto no alcanza para decidir."""
        sample = text[: self._sample_chars]
        letters = len(_LETTERS.findall(sample))
        if letters < self._min_letters:
            return None
        statistical = self._detect_statistically(sample)
        if statistical and letters >= _RELIABLE_LETTERS and statistical[1] >= _RELIABLE_PROBABILITY:
            return statistical[0]
        by_keywords = self._detect_by_keywords(sample)
        if by_keywords:
            return by_keywords
        if statistical and statistical[1] >= self._min_probability:
            return statistical[0]
        return None

    @staticmethod
    def _detect_statistically(sample: str) -> tuple[str, float] | None:
        try:
            best = detect_langs(sample)[0]
        except LangDetectException:
            return None
        return best.lang, best.prob

    @staticmethod
    def _detect_by_keywords(sample: str) -> str | None:
        words = _WORD.findall(fold(sample))
        scores = {lang: sum(1 for word in words if word in keywords) for lang, keywords in _KEYWORDS.items()}
        (best, top), (_, second) = sorted(scores.items(), key=lambda item: item[1], reverse=True)
        # Evidencia clara: varias palabras y el doble que el otro idioma
        return best if top >= 3 and top >= 2 * second else None
