package com.documind.audit;

import com.documind.auth.AuthenticatedUser;
import com.documind.common.web.PageResponse;
import com.documind.common.web.SortableFields;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/audit-logs")
@Tag(name = "Audit", description = "Audit trail of the organization (ADMIN)")
@SecurityRequirement(name = "bearerAuth")
public class AuditController {

    private final AuditLogRepository repository;

    public AuditController(AuditLogRepository repository) {
        this.repository = repository;
    }

    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    @Transactional(readOnly = true)
    @Operation(summary = "Search audit events of the organization")
    @ApiResponse(responseCode = "403", description = "Only administrators")
    public PageResponse<AuditLogResponse> search(
            @AuthenticationPrincipal AuthenticatedUser admin,
            @RequestParam(required = false) AuditEvent event,
            @RequestParam(required = false) UUID documentId,
            @Parameter(hidden = true) @PageableDefault(size = 50, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return PageResponse.of(repository.search(admin.organizationId(), event, documentId,
                        SortableFields.validate(pageable, Set.of("createdAt", "event"))),
                AuditLogResponse::from);
    }
}
