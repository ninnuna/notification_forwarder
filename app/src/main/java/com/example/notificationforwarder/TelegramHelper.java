package com.example.notificationforwarder;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public class TelegramHelper {

    private static final String TAG = "TelegramHelper";
    private static final String API_URL = "https://api.telegram.org/bot%s/sendMessage";
    private static final int TIMEOUT_MS = 10000;

    public interface SendMessageCallback {
        void onComplete(boolean success);
    }

    public static void sendMessage(String webToken, String message, SendMessageCallback callback) {
        Thread thread = new Thread(() -> {
            boolean success = false;
            HttpURLConnection conn = null;
            String[] tokenNChat = webToken.split("@@@");
            try {
                URL url = new URL(String.format(API_URL, tokenNChat[0]));
                conn = (HttpURLConnection) url.openConnection();
                conn.setRequestMethod("POST");
                conn.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                conn.setDoOutput(true);
                conn.setConnectTimeout(TIMEOUT_MS);
                conn.setReadTimeout(TIMEOUT_MS);

                String urlParameters = "&parse_mode=MarkdownV2&chat_id=" + tokenNChat[1] + "&text=" + message.replace("-","\\-");
                byte[] postData = urlParameters.getBytes(StandardCharsets.UTF_8);

                try (OutputStream os = conn.getOutputStream()) {
                    os.write(postData);
                }

                int responseCode = conn.getResponseCode();
                if (responseCode == HttpURLConnection.HTTP_OK) {
                    System.out.println("Message sent successfully!");
                } else {
                    System.err.println("HTTP error occurred. Response Code: " + responseCode);
                    System.err.println("Response content: " + new String(conn.getErrorStream().readAllBytes(), StandardCharsets.UTF_8));
                }
            } catch (IOException e) {
                Log.e(TAG, "Exception sending message", e);
            } finally {
                if (conn != null) {
                    conn.disconnect();
                }

                final boolean finalSuccess = success;
                new Handler(Looper.getMainLooper()).post(() -> callback.onComplete(finalSuccess));
            }
        });

        thread.start();
    }
}
