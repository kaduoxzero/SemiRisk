package com.semirisk.common;

import java.util.Map;
import java.util.Set;

public final class RolePermissionPolicy {

    private static final Map<String, Set<String>> ROLE_MODULES = Map.of(
            SemiriskConstants.ROLE_ADMIN, Set.of("dashboard", "upload", "analysis", "detail", "report", "alerts", "gis", "enterprise", "knowledge", "system"),
            SemiriskConstants.ROLE_ANALYST, Set.of("dashboard", "upload", "analysis", "detail", "report", "alerts", "gis", "enterprise", "knowledge"),
            SemiriskConstants.ROLE_OPERATOR, Set.of("dashboard", "upload", "alerts", "gis", "enterprise", "knowledge")
    );

    private RolePermissionPolicy() {
    }

    public static boolean canAccess(String role, String module) {
        return ROLE_MODULES.getOrDefault(role, Set.of()).contains(module);
    }

    public static Set<String> modules(String role) {
        return ROLE_MODULES.getOrDefault(role, Set.of());
    }
}

