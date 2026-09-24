package com.documind.document;

/** Tipo indicado por el usuario al subir el documento. AUTO deja que el servicio de IA lo detecte. */
public enum RequestedDocumentType {
    AUTO,
    INVOICE,
    CONTRACT,
    RESUME,
    RECEIPT,
    IDENTIFICATION,
    REPORT,
    OTHER
}
