package com.example.autoeq;

import android.os.Bundle;
import android.text.TextUtils;
import android.util.Patterns;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.content.Intent;

import androidx.activity.EdgeToEdge;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

import com.example.autoeq.R;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.google.firebase.auth.FirebaseAuth;


public class PasswordResetActivity extends AppCompatActivity {
    private TextInputLayout emailLayout;
    private TextInputEditText emailInput;
    private Button resetPasswordButton;
    private TextView backToLoginLink;
    private FirebaseAuth auth;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        EdgeToEdge.enable(this);
        setContentView(R.layout.activity_password_reset);
        auth = FirebaseAuth.getInstance();

        ViewCompat.setOnApplyWindowInsetsListener(findViewById(android.R.id.content), (v, insets) -> {
            Insets systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars());
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom);
            return insets;
        });

        initViews();
        setupListeners();
    }

    private void initViews() {
        emailLayout = findViewById(R.id.emailLayout);
        emailInput = findViewById(R.id.emailInput);
        resetPasswordButton = findViewById(R.id.resetPasswordButton);
        backToLoginLink = findViewById(R.id.backToLoginLink);
    }

    private void setupListeners() {
        resetPasswordButton.setOnClickListener(v -> attemptPasswordReset());

        backToLoginLink.setOnClickListener(v -> {
            Intent intent = new Intent(PasswordResetActivity.this, LoginActivity.class);
            intent.setFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
            startActivity(intent);
            finish();
        });
    }

    private void attemptPasswordReset() {
        emailLayout.setError(null);

        String email = emailInput.getText() != null ? emailInput.getText().toString().trim() : "";

        boolean isValid = true;
        if (TextUtils.isEmpty(email)) {
            emailLayout.setError("Email is required");
            isValid = false;
        } else if (!Patterns.EMAIL_ADDRESS.matcher(email).matches()) {
            emailLayout.setError("Enter a valid email");
            isValid = false;
        }

        if (isValid) {
            resetPasswordButton.setEnabled(false);

            auth.sendPasswordResetEmail(email)
                    .addOnCompleteListener(task -> {
                        resetPasswordButton.setEnabled(true);
                        if (task.isSuccessful()) {
                            Toast.makeText(this, "Password reset email sent!", Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(this,
                                    "Reset failed: " + (task.getException() != null ? task.getException().getMessage() : ""),
                                    Toast.LENGTH_LONG).show();
                        }
                    });
        }
    }
}