package com.example.autoeq;

import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

import com.example.autoeq.R;
import com.example.autoeq.validators.LoginValidator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthInvalidCredentialsException;
import com.google.firebase.auth.FirebaseAuthInvalidUserException;
import com.google.firebase.auth.FirebaseUser;

public class LoginActivity extends AppCompatActivity {

    private TextInputLayout emailLayout, passwordLayout;
    private TextInputEditText emailInput, passwordInput;
    private Button loginButton;
    private TextView signUpLink, statusMessage, forgotPasswordLink;
    private FirebaseAuth auth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_login);

        auth = FirebaseAuth.getInstance();

        initViews();
        setupListeners();
    }

    @Override
    public void onStart() {
        super.onStart();
        // AT-7: Check if user is signed in (non-null) and update UI accordingly.
        FirebaseUser currentUser = auth.getCurrentUser();
        if (currentUser != null) {
            updateUiForLoggedInUser("Welcome back! You are already logged in.");
        }
    }

    private void initViews() {
        emailLayout = findViewById(R.id.emailLayout);
        passwordLayout = findViewById(R.id.passwordLayout);
        emailInput = findViewById(R.id.emailInput);
        passwordInput = findViewById(R.id.passwordInput);
        loginButton = findViewById(R.id.loginButton);
        signUpLink = findViewById(R.id.signUpLink);
        statusMessage = findViewById(R.id.statusMessage);
        forgotPasswordLink = findViewById(R.id.forgotPasswordLink);
    }

    private void setupListeners() {
        signUpLink.setOnClickListener(v -> {
            Intent intent = new Intent(LoginActivity.this, SignUpActivity.class);
            startActivity(intent);
        });

        loginButton.setOnClickListener(v -> attemptLogin());

        forgotPasswordLink.setOnClickListener(v -> {
            Intent intent = new Intent(LoginActivity.this, PasswordResetActivity.class);
            startActivity(intent);
        });
    }

    private void attemptLogin() {
        // Reset errors
        emailLayout.setError(null);
        passwordLayout.setError(null);
        statusMessage.setVisibility(View.GONE);

        String email = emailInput.getText().toString().trim();
        String password = passwordInput.getText().toString();

        boolean isValid = true;

        if (!LoginValidator.isValidEmail(email)) {
            emailLayout.setError("Enter a valid email");
            isValid = false;
        }

        if (!LoginValidator.isPasswordNotEmpty(password)) {
            passwordLayout.setError("Password cannot be empty");
            isValid = false;
        }

        if (isValid) {
            loginButton.setEnabled(false);
            auth.signInWithEmailAndPassword(email, password)
                    .addOnCompleteListener(this, task -> {
                        loginButton.setEnabled(true);
                        if (task.isSuccessful()) {
                            // AT-1: Successful login
                            updateUiForLoggedInUser("Login Successful!");
                        } else {
                            // AT-2 & AT-5: Handle failed login
                            String errorMessage = "Login failed. Please try again.";
                            try {
                                if (task.getException() != null) {
                                    throw task.getException();
                                }
                            } catch (FirebaseAuthInvalidUserException | FirebaseAuthInvalidCredentialsException e) {
                                // AT-2: Invalid credentials
                                errorMessage = "Invalid email or password.";
                            } catch (FirebaseNetworkException e) {
                                // AT-5: Network error
                                errorMessage = "Network error. Please check your internet connection.";
                            } catch (Exception e) {
                                // Other exceptions
                                errorMessage = "Error: " + e.getLocalizedMessage();
                                Log.e("LoginActivity", "Authentication failed.", e);
                            }
                            statusMessage.setTextColor(ContextCompat.getColor(this, android.R.color.holo_red_dark));
                            statusMessage.setText(errorMessage);
                            statusMessage.setVisibility(View.VISIBLE);
                        }
                    });
        }
    }

    private void updateUiForLoggedInUser(String message) {
        emailLayout.setVisibility(View.GONE);
        passwordLayout.setVisibility(View.GONE);
        loginButton.setVisibility(View.GONE);
        signUpLink.setVisibility(View.GONE);

        statusMessage.setTextColor(ContextCompat.getColor(this, android.R.color.holo_green_dark));
        statusMessage.setText(message);
        statusMessage.setVisibility(View.VISIBLE);

        new Handler(Looper.getMainLooper()).postDelayed(() -> {
            Intent intent = new Intent(LoginActivity.this, MainActivity.class);
            startActivity(intent);
            finish();
        }, 1000);
    }
}

