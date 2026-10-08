package com.careerlens.core.auth;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {
    private final AuthService authService;

    @GetMapping("/sessions")
    java.util.List<java.util.Map<String,Object>> sessions(@AuthenticationPrincipal AppPrincipal user,@RequestHeader(HttpHeaders.AUTHORIZATION) String token){
        return authService.sessions(user.id(),bearer(token));
    }
    @DeleteMapping("/sessions/{id}") @ResponseStatus(HttpStatus.NO_CONTENT)
    void revoke(@AuthenticationPrincipal AppPrincipal user,@PathVariable java.util.UUID id){authService.revokeSession(user.id(),id);}

    @PostMapping("/register")
    @ResponseStatus(HttpStatus.CREATED)
    AuthService.Session register(@Valid @RequestBody RegisterRequest request) {
        return authService.register(request.email(), request.displayName(), request.password());
    }

    @PostMapping("/login")
    AuthService.Session login(@Valid @RequestBody LoginRequest request) {
        return authService.login(request.email(), request.password(), request.rememberMe());
    }

    @PostMapping("/refresh")
    AuthService.Session refresh(@AuthenticationPrincipal AppPrincipal principal,
                                @RequestHeader(HttpHeaders.AUTHORIZATION) String authorization) {
        return authService.refresh(principal, bearer(authorization));
    }

    @PostMapping("/logout")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    void logout(@RequestHeader(value = HttpHeaders.AUTHORIZATION, required = false) String authorization) {
        authService.logout(bearer(authorization));
    }

    @GetMapping("/me")
    AppPrincipal me(@AuthenticationPrincipal AppPrincipal principal) { return principal; }

    private String bearer(String value) {
        return value != null && value.startsWith("Bearer ") ? value.substring(7) : "";
    }

    record RegisterRequest(@NotBlank @Email String email,
                           @NotBlank @Size(max = 100) String displayName,
                           @NotBlank @Size(min = 8, max = 72) String password) {}
    record LoginRequest(@NotBlank @Email String email, @NotBlank String password, boolean rememberMe) {}
}
