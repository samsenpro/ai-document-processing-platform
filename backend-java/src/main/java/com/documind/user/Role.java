package com.documind.user;

/**
 * USER solo ve y gestiona sus propios documentos. ADMIN además ve los documentos de toda su
 * organización, consulta la auditoría y da de alta usuarios en la organización.
 */
public enum Role {
    ADMIN,
    USER;

    public String authority() {
        return "ROLE_" + name();
    }
}
