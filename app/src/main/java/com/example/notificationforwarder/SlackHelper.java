package com.example.notificationforwarder;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

public class SlackHelper implements MessageHelper {

    private static final String TAG = "SlackHelper";
    private static final String API_URL = "https://hooks.slack.com/services/%s";
    private static final int TIMEOUT_MS = 10000;
    private static final String MSG_STRING = "*Sub:* %s\n*Msg:* %s\n_%s_\n";
    private static final String MSG_STRING_1 = "*Info:* %s\n_%s_\n";

    @Override
    public void sendMessage(String webToken, String title, String text, String senderInfo, Consumer<Boolean> callback) {
        Thread thread = new Thread(() -> {
            boolean success = false;
            HttpURLConnection conn = null;

            final String message = (!(title.isEmpty() || text.isEmpty()) ?
                    String.format(MSG_STRING, title, text, senderInfo) :
                    String.format(MSG_STRING_1, title.isEmpty() ? text : title, senderInfo)) + "-".repeat(senderInfo.length());

            try {
                URL url = new URL(String.format(API_URL, webToken));
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/json");
                conn.setDoOutput(true);
                conn.setConnectTimeout(TIMEOUT_MS);
                conn.setReadTimeout(TIMEOUT_MS);

                String payload = "{\"text\": " + JSONObject.quote(message) + "}";

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(payload.getBytes("UTF-8"));
                }

                int responseCode = conn.getResponseCode();

                if (responseCode == HttpURLConnection.HTTP_OK) {
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getInputStream(), StandardCharsets.UTF_8))) {
                        StringBuilder response = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            response.append(line);
                        }
                        success = response.toString().contains("\"ok\":true");
                    }
                } else {
                    Log.e(TAG, "HTTP error: " + responseCode);
                    try (BufferedReader reader = new BufferedReader(
                            new InputStreamReader(conn.getErrorStream(), StandardCharsets.UTF_8))) {
                        StringBuilder response = new StringBuilder();
                        String line;
                        while ((line = reader.readLine()) != null) {
                            response.append(line);
                        }
                        Log.e(TAG, "Error response: " + response);
                    }
                }
            } catch (IOException e) {
                Log.e(TAG, "Exception sending message", e);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }

                final boolean finalSuccess = success;
                new Handler(Looper.getMainLooper()).post(() -> callback.accept(finalSuccess));
            }
        });

        thread.start();
    }
}
