package com.documind.processing;

import com.documind.auth.AuthenticatedUser;
import com.documind.common.idempotency.IdempotencyService;
import com.documind.common.ratelimit.RateLimited;
import com.documind.common.web.ClientIp;
import com.documind.processing.ProcessingDtos.DocumentResultResponse;
import com.documind.processing.ProcessingDtos.ProcessingStatusResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/documents/{id}")
@Tag(name = "Processing", description = "Asynchronous document processing and results")
@SecurityRequirement(name = "bearerAuth")
public class ProcessingController {

    private final ProcessingService processingService;
    private final IdempotencyService idempotencyService;

    public ProcessingController(ProcessingService processingService, IdempotencyService idempotencyService) {
        this.processingService = processingService;
        this.idempotencyService = idempotencyService;
    }

    @PostMapping("/process")
    @RateLimited(policy = "process")
    @Operation(summary = "Start (or retry after a failure) the processing of a document",
            description = "Returns immediately with 202: processing is asynchronous. Poll GET /processing for the status.")
    @ApiResponse(responseCode = "202", description = "Processing queued")
    @ApiResponse(responseCode = "404", description = "Document not found")
    @ApiResponse(responseCode = "409", description = "Document already processing or completed")
    @ApiResponse(responseCode = "429", description = "Rate limit exceeded")
    public ResponseEntity<ProcessingStatusResponse> process(
            @AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id,
            @Parameter(description = "Makes retries of this request safe: the document is queued only once")
            @RequestHeader(value = IdempotencyService.HEADER, required = false) String idempotencyKey,
            HttpServletRequest http) {
        IdempotencyService.Outcome outcome = idempotencyService.execute(user.id(), "process", idempotencyKey,
                id.toString(), () -> processingService.requestProcessing(user, id, ClientIp.of(http)));
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/api/v1/documents/" + id + "/processing"))
                .header("Idempotent-Replayed", Boolean.toString(outcome.replayed()))
                .body(processingService.status(user, id));
    }

    @GetMapping("/processing")
    @Operation(summary = "Processing status: current attempt, live stage and attempt history")
    @ApiResponse(responseCode = "200", description = "Processing status")
    @ApiResponse(responseCode = "404", description = "Document not found")
    public ProcessingStatusResponse processing(@AuthenticationPrincipal AuthenticatedUser user,
                                               @PathVariable UUID id) {
        return processingService.status(user, id);
    }

    @GetMapping("/result")
    @Operation(summary = "Structured result: type, confidence, entities, summary and extracted text")
    @ApiResponse(responseCode = "200", description = "Processing result")
    @ApiResponse(responseCode = "404", description = "Document not found")
    @ApiResponse(responseCode = "409", description = "The document has not been processed successfully yet")
    public DocumentResultResponse result(@AuthenticationPrincipal AuthenticatedUser user, @PathVariable UUID id) {
        return processingService.result(user, id);
    }
}
