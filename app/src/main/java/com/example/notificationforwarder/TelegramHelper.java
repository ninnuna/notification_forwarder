package com.example.notificationforwarder;

import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.io.IOException;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

public class TelegramHelper implements MessageHelper{

    private static final String TAG = "TelegramHelper";
    private static final String API_URL = "https://api.telegram.org/bot%s/sendMessage";
    private static final int TIMEOUT_MS = 10000;
    private static final String MSG_STRING = "<b>Sub:</b> %s\n<b>Msg:</b> %s\n<i>%s</i>\n";
    private static final String MSG_STRING_1 = "<b>Info:</b> %s\n<i>%s</i>\n";

    @Override
    public void sendMessage(String webToken, String title, String text, String senderInfo, Consumer<Boolean> callback) {
        Thread thread = new Thread(() -> {
            final String message = (!(title.isEmpty() || text.isEmpty()) ?
                    String.format(MSG_STRING, title, text, senderInfo) :
                    String.format(MSG_STRING_1, title.isEmpty() ? text : title, senderInfo)) + "-".repeat(senderInfo.length());
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

                String urlParameters = "&parse_mode=HTML&chat_id=" + tokenNChat[1] + "&text=" + message;
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
                new Handler(Looper.getMainLooper()).post(() -> callback.accept(finalSuccess));
            }
        });

        thread.start();
    }
}
