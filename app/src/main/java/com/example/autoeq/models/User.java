package com.example.autoeq.models;

public class User {
    public String name;
    public String email;

    public User() {
    }

    public User(String name, String email) {
        this.name = name;
        this.email = email;
    }

    // Getters
    public String getName() { return name; }
    public String getEmail() { return email; }
}
