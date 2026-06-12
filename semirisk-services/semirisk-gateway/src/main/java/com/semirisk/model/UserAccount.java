package com.semirisk.model;

public record UserAccount(String username, String password, String displayName, String role, boolean enabled) {
    public UserAccount(String username, String password, String displayName, String role) {
        this(username, password, displayName, role, true);
    }
}
