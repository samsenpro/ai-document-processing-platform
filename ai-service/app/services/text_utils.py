import unicodedata


def fold(text: str) -> str:
    """Minúsculas y sin tildes, para comparar palabras clave ("Cédula" == "cedula")."""
    decomposed = unicodedata.normalize("NFKD", text.lower())
    return "".join(c for c in decomposed if not unicodedata.combining(c))


def excerpt(text: str, max_chars: int) -> str:
    """Recorta el texto para el LLM conservando el principio y el final del documento,
    donde suelen estar los datos clave (cabecera, totales, firmas)."""
    if len(text) <= max_chars:
        return text
    head = int(max_chars * 0.75)
    tail = max_chars - head
    return f"{text[:head]}\n[...]\n{text[-tail:]}"
