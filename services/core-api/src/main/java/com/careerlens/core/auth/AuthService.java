package com.careerlens.core.auth;

import com.careerlens.core.common.ApiException;
import com.careerlens.core.common.Hashing;
import com.careerlens.core.domain.DataStore;
import com.careerlens.core.domain.Entities.AccessToken;
import com.careerlens.core.domain.Entities.AppUser;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuthService {
    private final DataStore store;
    private final PasswordEncoder passwordEncoder;

    @Value("${careerlens.auth.access-token-hours:24}")
    private long accessTokenHours;
    @Value("${careerlens.auth.remember-token-days:30}")
    private long rememberTokenDays;

    @Transactional
    public Session register(String email, String displayName, String password) {
        int passwordBytes=password.getBytes(java.nio.charset.StandardCharsets.UTF_8).length;
        if(passwordBytes<8||passwordBytes>72)throw ApiException.badRequest("PASSWORD_LENGTH","密码UTF-8编码长度应为8至72字节");
        String normalized = normalizeEmail(email);
        if (findUserByEmail(normalized).isPresent()) {
            throw ApiException.conflict("EMAIL_EXISTS", "该邮箱已注册");
        }
        AppUser user = new AppUser();
        user.setEmail(normalized);
        user.setDisplayName(displayName.trim());
        user.setPasswordHash(passwordEncoder.encode(password));
        user.setRole("USER");
        user.setStatus("ACTIVE");
        store.persist(user);
        store.flush();
        return issue(user);
    }

    @Transactional
    public Session login(String email, String password, boolean rememberMe) {
        AppUser user = findUserByEmail(normalizeEmail(email))
                .orElseThrow(() -> new ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "邮箱或密码错误"));
        if (!"ACTIVE".equals(user.getStatus()) || !passwordEncoder.matches(password, user.getPasswordHash())) {
            throw new ApiException(HttpStatus.UNAUTHORIZED, "BAD_CREDENTIALS", "邮箱或密码错误");
        }
        return issue(user, rememberMe
                ? Instant.now().plus(rememberTokenDays, ChronoUnit.DAYS)
                : Instant.now().plus(accessTokenHours, ChronoUnit.HOURS));
    }

    @Transactional
    public Optional<AppPrincipal> authenticate(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return Optional.empty();
        Optional<AccessToken> found = store.one(
                "select t from AccessToken t where t.tokenHash=:hash and t.expiresAt>:now",
                AccessToken.class, Map.of("hash", Hashing.sha256(rawToken), "now", Instant.now()));
        if (found.isEmpty()) return Optional.empty();
        AccessToken token = found.get();
        AppUser user = store.find(AppUser.class, token.getUserId()).orElse(null);
        if (user == null || !"ACTIVE".equals(user.getStatus())) return Optional.empty();
        token.setLastUsedAt(Instant.now());
        return Optional.of(new AppPrincipal(user.getId(), user.getEmail(), user.getDisplayName(), user.getRole()));
    }

    @Transactional
    public Session refresh(AppPrincipal principal, String currentRawToken) {
        logout(currentRawToken);
        AppUser user = store.find(AppUser.class, principal.id()).orElseThrow(() -> ApiException.notFound("用户"));
        return issue(user);
    }

    @Transactional
    public void logout(String rawToken) {
        if (rawToken == null || rawToken.isBlank()) return;
        store.update("delete from AccessToken t where t.tokenHash=:hash", Map.of("hash", Hashing.sha256(rawToken)));
    }

    public Optional<AppUser> findUserByEmail(String email) {
        return store.one("select u from AppUser u where u.email=:email", AppUser.class, Map.of("email", email));
    }

    @Transactional(readOnly=true)
    public java.util.List<Map<String,Object>> sessions(UUID userId,String currentToken){
        String currentHash=Hashing.sha256(currentToken);
        return store.query("select t from AccessToken t where t.userId=:uid order by t.createdAt desc",AccessToken.class,Map.of("uid",userId))
            .stream().map(token->Map.<String,Object>of("id",token.getId(),"createdAt",token.getCreatedAt(),
                "expiresAt",token.getExpiresAt(),"current",currentHash.equals(token.getTokenHash()))).toList();
    }
    @Transactional
    public void revokeSession(UUID userId,UUID tokenId){
        if(store.update("delete from AccessToken t where t.id=:id and t.userId=:uid",Map.of("id",tokenId,"uid",userId))==0)
            throw ApiException.notFound("会话");
    }

    private Session issue(AppUser user) {
        return issue(user, Instant.now().plus(accessTokenHours, ChronoUnit.HOURS));
    }

    private Session issue(AppUser user, Instant expiresAt) {
        String raw = Hashing.randomToken();
        AccessToken token = new AccessToken();
        token.setUserId(user.getId());
        token.setTokenHash(Hashing.sha256(raw));
        token.setExpiresAt(expiresAt);
        store.persist(token);
        return new Session(raw, "Bearer", expiresAt,
                new UserView(user.getId(), user.getEmail(), user.getDisplayName(), user.getRole()));
    }

    private String normalizeEmail(String email) { return email.trim().toLowerCase(Locale.ROOT); }

    public record Session(String accessToken, String tokenType, Instant expiresAt, UserView user) {}
    public record UserView(UUID id, String email, String displayName, String role) {}
}
