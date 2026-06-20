package com.semirisk.model;

/**
 * In-memory user account. The password field stores a PBKDF2 hash string
 * (format: "pbkdf2$iterations$salt$hash"). Empty string "" means the account
 * is recovered from DB and real authentication goes through the DB path.
 */
public record UserAccount(String username, String password, String displayName, String role, boolean enabled) {
    public UserAccount(String username, String password, String displayName, String role) {
        this(username, password, displayName, role, true);
    }
}
