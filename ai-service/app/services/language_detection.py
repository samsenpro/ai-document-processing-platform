import re

from langdetect import DetectorFactory, LangDetectException, detect_langs

# langdetect es probabilístico: con semilla fija el mismo texto da siempre el mismo resultado
DetectorFactory.seed = 0

_LETTERS = re.compile(r"[^\W\d_]", re.UNICODE)


class LanguageDetector:
    def __init__(self, min_letters: int = 20, min_probability: float = 0.5, sample_chars: int = 5000) -> None:
        self._min_letters = min_letters
        self._min_probability = min_probability
        self._sample_chars = sample_chars

    def detect(self, text: str) -> str | None:
        """Código ISO 639-1 del idioma ("es", "en"...) o None si el texto no alcanza para decidir."""
        sample = text[: self._sample_chars]
        if len(_LETTERS.findall(sample)) < self._min_letters:
            return None
        try:
            best = detect_langs(sample)[0]
        except LangDetectException:
            return None
        return best.lang if best.prob >= self._min_probability else None
