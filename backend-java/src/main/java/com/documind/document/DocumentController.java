package com.documind.document;

import com.documind.auth.AuthenticatedUser;
import com.documind.common.idempotency.IdempotencyService;
import com.documind.common.ratelimit.RateLimited;
import com.documind.common.web.ClientIp;
import com.documind.common.web.PageResponse;
import com.documind.common.web.SortableFields;
import com.documind.document.DocumentDtos.DocumentResponse;
import com.documind.document.DocumentDtos.DocumentStatsResponse;
import com.documind.document.DocumentDtos.DocumentSummaryResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.net.URI;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents")
@Tag(name = "Documents", description = "Upload, list, inspect and delete documents")
@SecurityRequirement(name = "bearerAuth")
public class DocumentController {

    private static final Set<String> SORTABLE = Set.of("createdAt", "updatedAt", "originalFilename", "status",
            "detectedType", "sizeBytes", "processingTimeMs");

    private final DocumentService documentService;

    public DocumentController(DocumentService documentService) {
        this.documentService = documentService;
    }

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @RateLimited(policy = "upload")
    @Operation(summary = "Upload a document",
            description = "Accepts PDF, PNG, JPEG, TIFF, WEBP, DOCX and TXT. With autoProcess=true (default) the "
                    + "processing is queued right away and the response returns before it finishes.")
    @ApiResponse(responseCode = "201", description = "Document stored (and processing queued if requested)")
    @ApiResponse(responseCode = "400", description = "Empty file or invalid parameters")
    @ApiResponse(responseCode = "413", description = "File too large")
    @ApiResponse(responseCode = "415", description = "Unsupported file type")
    @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
    public ResponseEntity<DocumentResponse> upload(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestPart("file") MultipartFile file,
            @Parameter(description = "Expected type, or AUTO to detect it")
            @RequestParam(defaultValue = "AUTO") RequestedDocumentType documentType,
            @RequestParam(defaultValue = "true") boolean autoProcess,
            @Parameter(description = "Makes retries safe: the same key returns the document created the first time")
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey,
            HttpServletRequest http) {
        DocumentService.UploadResult result = documentService.upload(user, file, documentType, autoProcess,
                idempotencyKey, ClientIp.of(http));
        return ResponseEntity.created(URI.create("/api/v1/documents/" + result.document().id()))
                .header("Idempotent-Replayed", Boolean.toString(result.replayed()))
                .body(result.document());
    }

    @GetMapping
    @RateLimited(policy = "read")
    @Operation(summary = "List documents (own documents; ADMIN sees the whole organization)")
    public PageResponse<DocumentSummaryResponse> list(
            @AuthenticationPrincipal AuthenticatedUser user,
            @RequestParam(required = false) DocumentStatus status,
            @RequestParam(required = false) DocumentType type,
            @Parameter(hidden = true) @PageableDefault(size = 20, sort = "createdAt", direction = Sort.Direction.DESC)
            Pageable pageable) {
        return documentService.list(user, status, type, SortableFields.validate(pageable, SORTABLE));
    }

    @GetMapping("/stats")
    @RateLimited(policy = "read")
    @Operation(summary = "Dashboard statistics: totals by status and type, average processing time")
    public DocumentStatsResponse stats(@AuthenticationPrincipal AuthenticatedUser user) {
        return documentService.stats(user);
    }

    @GetMapping("/{id}")
    @RateLimited(policy = "read")
    @Operation(summary = "Document detail with its latest processing attempt")
    @ApiResponse(responseCode = "404", description = "Document not found")
    public DocumentResponse get(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return documentService.get(user, id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Delete a document, its file and its results")
    @ApiResponse(responseCode = "204", description = "Deleted")
    @ApiResponse(responseCode = "404", description = "Document not found")
    @ApiResponse(responseCode = "409", description = "The document is being processed")
    public void delete(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
                       HttpServletRequest http) {
        documentService.delete(user, id, ClientIp.of(http));
    }
}
