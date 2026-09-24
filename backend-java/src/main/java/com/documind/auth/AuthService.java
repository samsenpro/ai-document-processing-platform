package com.documind.auth;

import com.documind.audit.AuditEvent;
import com.documind.audit.AuditService;
import com.documind.auth.AuthDtos.AuthResponse;
import com.documind.auth.AuthDtos.LoginRequest;
import com.documind.auth.AuthDtos.RegisterRequest;
import com.documind.exception.ApiException;
import com.documind.exception.ErrorCode;
import com.documind.organization.Organization;
import com.documind.organization.OrganizationRepository;
import com.documind.user.Role;
import com.documind.user.User;
import com.documind.user.UserRepository;
import com.documind.user.UserResponse;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.authentication.AuthenticationManager;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@Service
public class AuthService {

    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuthenticationManager authenticationManager;
    private final JwtService jwtService;
    private final RefreshTokenService refreshTokenService;
    private final AuditService auditService;

    public AuthService(UserRepository userRepository, OrganizationRepository organizationRepository,
                       PasswordEncoder passwordEncoder, AuthenticationManager authenticationManager,
                       JwtService jwtService, RefreshTokenService refreshTokenService, AuditService auditService) {
        this.userRepository = userRepository;
        this.organizationRepository = organizationRepository;
        this.passwordEncoder = passwordEncoder;
        this.authenticationManager = authenticationManager;
        this.jwtService = jwtService;
        this.refreshTokenService = refreshTokenService;
        this.auditService = auditService;
    }

    /**
     * El registro público crea una organización nueva y su primer usuario como ADMIN. Los demás
     * miembros los da de alta el ADMIN: nadie puede unirse a una organización ajena por su cuenta.
     */
    @Transactional
    public AuthResponse register(RegisterRequest request, String ip) {
        String email = User.normalizeEmail(request.email());
        if (userRepository.existsByEmail(email)) {
            throw new ApiException(ErrorCode.EMAIL_ALREADY_REGISTERED);
        }
        Organization organization = organizationRepository.save(new Organization(request.organizationName().strip()));
        User user;
        try {
            user = userRepository.saveAndFlush(new User(organization, email,
                    passwordEncoder.encode(request.password()), request.fullName(), Role.ADMIN));
        } catch (DataIntegrityViolationException ex) {
            // Dos registros simultáneos con el mismo email: gana el índice único de la base de datos
            throw new ApiException(ErrorCode.EMAIL_ALREADY_REGISTERED);
        }
        auditService.record(AuditEvent.USER_REGISTERED, organization.getId(), user.getId(), null, ip,
                Map.of("role", Role.ADMIN.name()));
        return tokensFor(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        try {
            var authentication = authenticationManager.authenticate(UsernamePasswordAuthenticationToken
                    .unauthenticated(User.normalizeEmail(request.email()), request.password()));
            AuthenticatedUser principal = (AuthenticatedUser) authentication.getPrincipal();
            return tokensFor(userRepository.getReferenceById(principal.id()));
        } catch (AuthenticationException ex) {
            // Mismo error para email inexistente, contraseña incorrecta o usuario deshabilitado
            throw new ApiException(ErrorCode.INVALID_CREDENTIALS);
        }
    }

    @Transactional(readOnly = true)
    public AuthResponse refresh(String refreshToken) {
        User user = refreshTokenService.consume(refreshToken)
                .flatMap(userRepository::findById)
                .filter(User::isEnabled)
                .orElseThrow(() -> new ApiException(ErrorCode.INVALID_REFRESH_TOKEN));
        return tokensFor(user);
    }

    private AuthResponse tokensFor(User user) {
        String accessToken = jwtService.createAccessToken(AuthenticatedUser.from(user));
        String refreshToken = refreshTokenService.issue(user.getId());
        return new AuthResponse(accessToken, refreshToken, "Bearer", jwtService.accessTokenTtlSeconds(),
                UserResponse.from(user));
    }
}
