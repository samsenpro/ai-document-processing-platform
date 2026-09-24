package com.documind.organization;

import com.documind.audit.AuditEvent;
import com.documind.audit.AuditService;
import com.documind.auth.AuthenticatedUser;
import com.documind.common.web.PageResponse;
import com.documind.exception.ApiException;
import com.documind.exception.ErrorCode;
import com.documind.organization.OrganizationDtos.CreateMemberRequest;
import com.documind.organization.OrganizationDtos.OrganizationResponse;
import com.documind.user.User;
import com.documind.user.UserRepository;
import com.documind.user.UserResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Pageable;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class OrganizationService {

    private final OrganizationRepository organizationRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditService auditService;

    public OrganizationService(OrganizationRepository organizationRepository, UserRepository userRepository,
                               PasswordEncoder passwordEncoder, AuditService auditService) {
        this.organizationRepository = organizationRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditService = auditService;
    }

    @Transactional(readOnly = true)
    public OrganizationResponse current(AuthenticatedUser user) {
        Organization organization = organizationRepository.findById(user.organizationId())
                .orElseThrow(() -> new ApiException(ErrorCode.RESOURCE_NOT_FOUND));
        return new OrganizationResponse(organization.getId(), organization.getName(), organization.getCreatedAt());
    }

    @Transactional(readOnly = true)
    public PageResponse<UserResponse> members(AuthenticatedUser admin, Pageable pageable) {
        return PageResponse.of(userRepository.findByOrganization_Id(admin.organizationId(), pageable),
                UserResponse::from);
    }

    /** Solo un ADMIN puede dar de alta usuarios, y siempre en su propia organización. */
    @Transactional
    public UserResponse createMember(AuthenticatedUser admin, CreateMemberRequest request, String ip) {
        String email = User.normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_REGISTERED);
        }
        Organization organization = organizationRepository.getReferenceById(admin.organizationId());
        User member;
        try {
            member = userRepository.saveAndFlush(new User(organization, email,
                    passwordEncoder.encode(request.password()), request.fullName(), request.role()));
        } catch (DataIntegrityViolationException ex) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_REGISTERED);
        }
        auditService.record(AuditEvent.USER_CREATED, admin.organizationId(), admin.id(), null, ip,
                Map.of("createdUserId", member.getId().toString(), "role", request.role().name()));
        return UserResponse.from(member);
    }
}
