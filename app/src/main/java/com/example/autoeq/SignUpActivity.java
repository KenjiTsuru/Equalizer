package com.example.autoeq;

import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.autoeq.FirebaseCRUD;
import com.example.autoeq.validators.SignUpValidator;
import com.example.autoeq.models.User;
import com.example.autoeq.R;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;

import com.google.firebase.FirebaseNetworkException;
import com.google.firebase.auth.FirebaseAuth;

public class SignUpActivity extends AppCompatActivity {

    private TextInputLayout nameLayout, emailLayout, passwordLayout, confirmPasswordLayout;
    private TextInputEditText nameInput, emailInput, passwordInput, confirmPasswordInput;
    private Spinner roleSpinner;
    private Button signUpButton;
    private TextView loginLink;
    private FirebaseAuth auth;
    private FirebaseCRUD firebaseCRUD;
    private TextView statusMessage;


    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_sign_up);
        auth = FirebaseAuth.getInstance();
        firebaseCRUD = new FirebaseCRUD();

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        initViews();
        setupSpinner();
        setupListeners();
    }

    private void initViews() {
        nameLayout = findViewById(R.id.nameLayout);
        emailLayout = findViewById(R.id.emailLayout);
        passwordLayout = findViewById(R.id.passwordLayout);
        confirmPasswordLayout = findViewById(R.id.confirmPasswordLayout);

        nameInput = findViewById(R.id.nameInput);
        emailInput = findViewById(R.id.emailInput);
        passwordInput = findViewById(R.id.passwordInput);
        confirmPasswordInput = findViewById(R.id.confirmPasswordInput);

        roleSpinner = findViewById(R.id.roleSpinner);
        signUpButton = findViewById(R.id.signUpButton);
        loginLink = findViewById(R.id.loginLink);

        statusMessage = findViewById(R.id.statusMessage);
    }

    private void setupSpinner() {
        String[] roles = {"Employee", "Employer"};
        ArrayAdapter<String> adapter = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, roles);
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item);
        roleSpinner.setAdapter(adapter);
    }

    private void setupListeners() {
        signUpButton.setOnClickListener(v -> attemptSignUp());

        loginLink.setOnClickListener(v -> {
            Intent intent = new Intent(SignUpActivity.this, LoginActivity.class);
            startActivity(intent);
        });
    }

    private void navigateToDashboard(String userName) {
        Intent intent = new Intent(SignUpActivity.this, HomeFragment.class);
        intent.putExtra("USER_NAME", userName);
        startActivity(intent);
        finish();
    }

    private void attemptSignUp() {
        nameLayout.setError(null);
        emailLayout.setError(null);
        passwordLayout.setError(null);
        confirmPasswordLayout.setError(null);
        statusMessage.setVisibility(android.view.View.GONE);

        String name = nameInput.getText().toString().trim();
        String email = emailInput.getText().toString().trim();
        String password = passwordInput.getText().toString();
        String confirmPassword = confirmPasswordInput.getText().toString();
        String role = roleSpinner.getSelectedItem().toString();

        boolean isValid = true;

        if (!SignUpValidator.isValidName(name)) {
            nameLayout.setError("Name is required");
            isValid = false;
        }

        if (!SignUpValidator.isValidEmail(email)) {
            emailLayout.setError("Enter a valid email");
            isValid = false;
        }

        if (!SignUpValidator.isValidPassword(password)) {
            passwordLayout.setError("Password must be at least 8 characters");
            isValid = false;
        }

        if (!SignUpValidator.passwordsMatch(password, confirmPassword)) {
            confirmPasswordLayout.setError("Passwords do not match");
            isValid = false;
        }

        if (isValid) {
            auth.createUserWithEmailAndPassword(email, password)
                    .addOnCompleteListener(task -> {
                        if (task.isSuccessful() && auth.getCurrentUser() != null) {
                            String userId = auth.getCurrentUser().getUid();
                            User user = new User(name, email, role, "", "", "", "");

                            firebaseCRUD.addOrUpdateUser(userId, user)
                                    .addOnCompleteListener(dbTask -> {
                                        if (dbTask.isSuccessful()) {
                                            statusMessage.setTextColor(android.graphics.Color.parseColor("#22C55E"));
                                            statusMessage.setText("Account created successfully!");
                                            statusMessage.setVisibility(android.view.View.VISIBLE);

                                            navigateToDashboard(name);
                                        } else {
                                            String error = "Database error: " + (dbTask.getException() != null ? dbTask.getException().getMessage() : "Unknown error");
                                            statusMessage.setTextColor(android.graphics.Color.RED);
                                            statusMessage.setText(error);
                                            statusMessage.setVisibility(android.view.View.VISIBLE);
                                            Log.e("SignUpActivity", error, dbTask.getException());
                                        }
                                    });

                        } else {
                            String error;
                            if (task.getException() instanceof FirebaseNetworkException) {
                                error = "Network error. Please check your connection.";
                            } else {
                                error = "Auth error: " + task.getException().getMessage();
                            }
                            statusMessage.setTextColor(android.graphics.Color.RED);
                            statusMessage.setText(error);
                            statusMessage.setVisibility(android.view.View.VISIBLE);
                            Log.e("SignUpActivity", error, task.getException());
                        }
                    });
        }
    }
}