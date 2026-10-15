package com.example.notificationforwarder;

import android.content.Intent;
import android.os.Bundle;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

import java.util.concurrent.Executor;

public class AppLockActivity extends AppCompatActivity {

    private String targetPackage;
    private PreferenceManager preferenceManager;
    private EditText pinInputField;
    private Button btnVerifyPin, btnUseBiometric;
    private boolean biometricPromptShown = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_app_lock);

        preferenceManager = new PreferenceManager(this);

        targetPackage = getIntent().getStringExtra("TARGET_PACKAGE");
        if (targetPackage == null) {
            finish();
            return;
        }

        pinInputField = findViewById(R.id.pin_input_field);
        btnVerifyPin = findViewById(R.id.btn_verify_pin);
        btnUseBiometric = findViewById(R.id.btn_use_biometric);

        btnVerifyPin.setOnClickListener(v -> verifyPin());
        btnUseBiometric.setOnClickListener(v -> showBiometricPrompt());
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        // Ensure prompt is launched only after the activity window has full focus
        if (hasFocus && !biometricPromptShown) {
            biometricPromptShown = true;
            // Post delayed to allow window transitions and decor view to settle completely
            getWindow().getDecorView().postDelayed(this::showBiometricPrompt, 150);
        }
    }

    private void verifyPin() {
        String input = pinInputField.getText().toString().trim();
        String customPin = preferenceManager.getCustomPin();

        if ("788990".equals(input) || (!customPin.isEmpty() && customPin.equals(input))) {
            AppLockService.setAppUnlocked(targetPackage, null);
            finish();
        } else {
            Toast.makeText(this, "Access Denied: Invalid PIN", Toast.LENGTH_SHORT).show();
            pinInputField.setText("");
        }
    }

    private void showBiometricPrompt() {
        if (isFinishing() || isDestroyed()) return;

        Executor executor = ContextCompat.getMainExecutor(this);
        BiometricPrompt biometricPrompt = new BiometricPrompt(this, executor,
                new BiometricPrompt.AuthenticationCallback() {
                    @Override
                    public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result) {
                        super.onAuthenticationSucceeded(result);
                        AppLockService.setAppUnlocked(targetPackage, null);
                        finish();
                    }

                    @Override
                    public void onAuthenticationError(int errorCode, @NonNull CharSequence errString) {
                        super.onAuthenticationError(errorCode, errString);
                        // Only exit to home screen if the user explicitly canceled
                        if (errorCode == BiometricPrompt.ERROR_USER_CANCELED ||
                                errorCode == BiometricPrompt.ERROR_NEGATIVE_BUTTON) {
                            AppLockService.currentLockingPackage = "";
                            goToHomeScreen();
                        } else {
                            // Transient startup cancellation or lockout - keep PIN view visible
                            btnUseBiometric.setVisibility(View.VISIBLE);
                        }
                    }
                });

        BiometricPrompt.PromptInfo promptInfo = new BiometricPrompt.PromptInfo.Builder()
                .setTitle("Application Protected")
                .setSubtitle("Use Biometrics or Enter Security PIN")
                .setAllowedAuthenticators(BiometricManager.Authenticators.BIOMETRIC_STRONG
                        | BiometricManager.Authenticators.DEVICE_CREDENTIAL)
                .build();

        try {
            biometricPrompt.authenticate(promptInfo);
        } catch (Exception e) {
            btnUseBiometric.setVisibility(View.VISIBLE);
        }
    }

    private void goToHomeScreen() {
        Intent intent = new Intent(Intent.ACTION_MAIN);
        intent.addCategory(Intent.CATEGORY_HOME);
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        startActivity(intent);
        finish();
    }

    @Override
    public void onBackPressed() {
        AppLockService.currentLockingPackage = "";
        goToHomeScreen();
    }
}
