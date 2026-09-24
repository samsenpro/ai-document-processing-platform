package com.documind.organization;

import com.documind.auth.AuthenticatedUser;
import com.documind.common.web.ClientIp;
import com.documind.common.web.PageResponse;
import com.documind.common.web.SortableFields;
import com.documind.organization.OrganizationDtos.CreateMemberRequest;
import com.documind.organization.OrganizationDtos.OrganizationResponse;
import com.documind.user.UserResponse;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

@RestController
@RequestMapping("/api/v1/organizations/me")
@Tag(name = "Organization", description = "Current organization and its members")
@SecurityRequirement(name = "bearerAuth")
public class OrganizationController {

    private final OrganizationService organizationService;

    public OrganizationController(OrganizationService organizationService) {
        this.organizationService = organizationService;
    }

    @GetMapping
    @Operation(summary = "Organization of the authenticated user")
    public OrganizationResponse current(@AuthenticationPrincipal AuthenticatedUser user) {
        return organizationService.current(user);
    }

    @GetMapping("/users")
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "List organization members (ADMIN)")
    @ApiResponse(responseCode = "403", description = "Only administrators")
    public PageResponse<UserResponse> members(@AuthenticationPrincipal AuthenticatedUser admin,
                                              @Parameter(hidden = true) @PageableDefault(size = 20, sort = "createdAt",
                                                      direction = Sort.Direction.ASC) Pageable pageable) {
        return organizationService.members(admin,
                SortableFields.validate(pageable, Set.of("createdAt", "email", "fullName", "role")));
    }

    @PostMapping("/users")
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize("hasRole('ADMIN')")
    @Operation(summary = "Create a member in the organization (ADMIN)")
    @ApiResponse(responseCode = "201", description = "User created")
    @ApiResponse(responseCode = "403", description = "Only administrators")
    @ApiResponse(responseCode = "409", description = "Email already registered")
    public UserResponse createMember(@AuthenticationPrincipal AuthenticatedUser admin,
                                     @Valid @RequestBody CreateMemberRequest request, HttpServletRequest http) {
        return organizationService.createMember(admin, request, ClientIp.of(http));
    }
}
