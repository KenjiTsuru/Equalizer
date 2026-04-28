package com.example.autoeq.models;

import java.util.ArrayList;
import java.util.List;

public class User {
    public String name;
    public String email;
    public String bio;

    public User() {
    }

    public User(String name, String email) {
        this.name = name;
        this.email = email;
    }

    public User(String name, String email, String role, String bio, String skills, String experience, String education) {
        this.name = name;
        this.email = email;
        this.bio = bio;
    }

    // Getters
    public String getName() { return name; }
    public String getEmail() { return email; }
    public String getBio() { return bio; }

}
