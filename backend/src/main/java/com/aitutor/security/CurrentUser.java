package com.aitutor.security;

public class CurrentUser {

    private final Long id;
    private final String username;
    private final String role;

    public CurrentUser(Long id, String username, String role) {
        this.id = id;
        this.username = username;
        this.role = role;
    }

    public Long getId() {
        return id;
    }

    public String getUsername() {
        return username;
    }

    public String getRole() {
        return role;
    }
}
