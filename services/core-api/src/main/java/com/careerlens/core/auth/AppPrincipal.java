package com.careerlens.core.auth;

import java.util.UUID;

public record AppPrincipal(UUID id, String email, String displayName, String role) {}
