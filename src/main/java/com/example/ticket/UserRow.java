package com.example.ticket;

public record UserRow(long id, String username, String passwordHash, String role, boolean enabled,
        long authVersion) {}
