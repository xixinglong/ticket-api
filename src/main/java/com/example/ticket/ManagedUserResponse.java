package com.example.ticket;

/** Deliberately excludes password hashes and authentication versions. */
public record ManagedUserResponse(String id, String username, String role, boolean enabled) {
    static ManagedUserResponse from(UserRow row) {
        return new ManagedUserResponse(Long.toString(row.id()), row.username(), row.role(), row.enabled());
    }
}
