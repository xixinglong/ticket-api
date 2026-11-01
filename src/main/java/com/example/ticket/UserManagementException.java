package com.example.ticket;

final class UserManagementException extends RuntimeException {
    final int status;
    final String code;

    UserManagementException(int status, String code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }
}
