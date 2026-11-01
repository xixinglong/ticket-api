package com.example.ticket;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/admin/users")
public class UserManagementController {
    private final UserManagementService service;

    public UserManagementController(UserManagementService service) {
        this.service = service;
    }

    public record CreateRequest(String username, String password, String role) {}
    public record EnabledRequest(Boolean enabled) {}
    public record PasswordRequest(String password) {}

    @GetMapping
    public UserManagementService.Page list(@RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return service.list(page, size);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ManagedUserResponse create(@RequestBody CreateRequest request) {
        return service.create(request.username(), request.password(), request.role());
    }

    @PatchMapping("/{id}/enabled")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void enabled(@PathVariable long id, @RequestBody EnabledRequest request) {
        service.setEnabled(id, request.enabled());
    }

    @PostMapping("/{id}/reset-password")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(@PathVariable long id, @RequestBody PasswordRequest request) {
        service.resetPassword(id, request.password());
    }
}
