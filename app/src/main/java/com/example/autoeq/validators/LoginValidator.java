package com.example.autoeq.validators;

import java.util.regex.Pattern;

public class LoginValidator {

    private static final Pattern EMAIL_PATTERN = Pattern.compile("^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$");

    public static boolean isValidEmail(String email) {
        return email != null && !email.trim().isEmpty() && EMAIL_PATTERN.matcher(email).matches();
    }

    public static boolean isPasswordNotEmpty(String password) {
        return password != null && !password.isEmpty();
    }
}
