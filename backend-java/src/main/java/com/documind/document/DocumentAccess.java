package com.documind.document;

import com.documind.auth.AuthenticatedUser;
import com.documind.exception.ApiException;
import com.documind.exception.ErrorCode;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Regla de acceso a documentos, en un único sitio: un USER solo accede a los suyos; un ADMIN, a los
 * de su organización. Un documento ajeno responde 404 (no 403) para no revelar que existe.
 */
@Component
public class DocumentAccess {

    private final DocumentRepository documentRepository;

    public DocumentAccess(DocumentRepository documentRepository) {
        this.documentRepository = documentRepository;
    }

    public Document require(AuthenticatedUser user, UUID documentId) {
        var document = user.isAdmin()
                ? documentRepository.findByIdAndOrganization_Id(documentId, user.organizationId())
                : documentRepository.findByIdAndOwner_Id(documentId, user.id());
        return document.orElseThrow(() -> new ApiException(ErrorCode.DOCUMENT_NOT_FOUND));
    }
}
