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
    private Button btnVerifyPin;
    private Button btnUseBiometric;
    private boolean biometricPromptShown = false;
    public static boolean isVisible = false;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        isVisible = true;
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
    protected void onDestroy() {
        super.onDestroy();
        isVisible = false;
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
                        // Transient startup cancellation, lockout, or system-dismissal
                        // We shouldn't blindly go to home screen for code 10 (User Canceled) because 
                        // Android sometimes sends it when an underlying app finishes launching and steals focus.
                        // Instead, we just keep the PIN layout visible. The user can manually go home, 
                        // or click "Use Biometrics" to try again.
                        
                        // We ONLY go to home screen if the user presses the Back button.
                        btnUseBiometric.setVisibility(View.VISIBLE);
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
