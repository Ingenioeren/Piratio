package com.gillingteknik.piratio;

import android.app.Activity;
import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;

public final class CloudProfileClient {
    public interface Callback {
        void onResult(boolean success, String message);
    }

    private final Activity activity;
    private final SharedPreferences prefs;
    private final String baseUrl;

    public CloudProfileClient(Activity activity) {
        this.activity = activity;
        this.prefs = activity.getSharedPreferences("piratio_profile", Context.MODE_PRIVATE);
        this.baseUrl = trimSlash(BuildConfig.API_BASE_URL);
    }

    public boolean isConfigured() {
        return !baseUrl.isEmpty();
    }

    public boolean hasSession() {
        return !prefs.getString("cloud_token", "").isEmpty();
    }

    public String providerLabel() {
        return prefs.getString("cloud_provider", "LOCAL");
    }

    public void loginWithGoogleAuthCode(String authCode, Callback callback) {
        if (!isConfigured()) {
            callback(callback, false, "Cloud server is not configured yet");
            return;
        }
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject().put("serverAuthCode", authCode);
                JSONObject response = request("POST", "/api/auth/google", body, false);
                String token = response.getString("token");
                prefs.edit().putString("cloud_token", token).putString("cloud_provider", "GOOGLE_PLAY").apply();
                if (response.has("profile")) mergeProfile(response.getJSONObject("profile"));
                callback(callback, true, "Play Games cloud profile connected");
            } catch (Exception e) {
                callback(callback, false, shortError(e));
            }
        }, "piratio-cloud-login").start();
    }

    public void pullProfile(Callback callback) {
        if (!isConfigured() || !hasSession()) {
            callback(callback, false, "No cloud session");
            return;
        }
        new Thread(() -> {
            try {
                JSONObject response = request("GET", "/api/profile", null, true);
                mergeProfile(response.getJSONObject("profile"));
                callback(callback, true, "Cloud profile restored");
            } catch (Exception e) {
                callback(callback, false, shortError(e));
            }
        }, "piratio-cloud-pull").start();
    }

    /**
     * Only non-authoritative preferences are ever uploaded by the client.
     * Coins, owned skins, XP, HP, damage and rewards are intentionally absent.
     */
    public void pushProfile(Callback callback) {
        if (!isConfigured() || !hasSession()) {
            if (callback != null) callback(callback, false, "No cloud session");
            return;
        }
        new Thread(() -> {
            try {
                JSONObject body = new JSONObject()
                        .put("captainName", prefs.getString("player_name", "Captain"))
                        .put("selectedSkin", Math.max(0, prefs.getInt("selected_skin", 0)))
                        .put("musicEnabled", prefs.getBoolean("music_enabled", true))
                        .put("sfxEnabled", prefs.getBoolean("sfx_enabled", true));
                JSONObject response = request("PUT", "/api/profile", body, true);
                if (response.has("profile")) mergeProfile(response.getJSONObject("profile"));
                if (callback != null) callback(callback, true, "Cloud preferences synced");
            } catch (Exception e) {
                if (callback != null) callback(callback, false, shortError(e));
            }
        }, "piratio-cloud-push").start();
    }

    private void mergeProfile(JSONObject profile) {
        SharedPreferences.Editor edit = prefs.edit();
        if (profile.has("captainName")) edit.putString("player_name", profile.optString("captainName", "Captain"));
        // These values are server-authoritative. Local file edits are overwritten by the server copy.
        if (profile.has("coins")) edit.putInt("coins", Math.max(0, profile.optInt("coins", 0)));
        if (profile.has("skins")) edit.putInt("skins", Math.max(1, profile.optInt("skins", 1)));
        if (profile.has("selectedSkin")) edit.putInt("selected_skin", Math.max(0, profile.optInt("selectedSkin", 0)));
        if (profile.has("musicEnabled")) edit.putBoolean("music_enabled", profile.optBoolean("musicEnabled", true));
        if (profile.has("sfxEnabled")) edit.putBoolean("sfx_enabled", profile.optBoolean("sfxEnabled", true));
        edit.putLong("cloud_updated_at", System.currentTimeMillis()).apply();
    }

    private JSONObject request(String method, String path, JSONObject body, boolean authenticated) throws Exception {
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        connection.setRequestMethod(method);
        connection.setConnectTimeout(7000);
        connection.setReadTimeout(9000);
        connection.setRequestProperty("Accept", "application/json");
        connection.setRequestProperty("Content-Type", "application/json; charset=utf-8");
        if (authenticated) {
            String token = prefs.getString("cloud_token", "");
            connection.setRequestProperty("Authorization", "Bearer " + token);
        }
        if (body != null) {
            connection.setDoOutput(true);
            byte[] bytes = body.toString().getBytes(StandardCharsets.UTF_8);
            try (OutputStream out = connection.getOutputStream()) { out.write(bytes); }
        }
        int code = connection.getResponseCode();
        InputStream stream = code >= 200 && code < 300 ? connection.getInputStream() : connection.getErrorStream();
        String text = readAll(stream);
        connection.disconnect();
        JSONObject json = text.isEmpty() ? new JSONObject() : new JSONObject(text);
        if (code < 200 || code >= 300) throw new IllegalStateException(json.optString("error", "HTTP " + code));
        return json;
    }

    private static String readAll(InputStream stream) throws Exception {
        if (stream == null) return "";
        StringBuilder result = new StringBuilder();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) result.append(line);
        }
        return result.toString();
    }

    private void callback(Callback callback, boolean success, String message) {
        if (callback == null) return;
        activity.runOnUiThread(() -> callback.onResult(success, message));
    }

    private static String trimSlash(String value) {
        if (value == null) return "";
        String result = value.trim();
        while (result.endsWith("/")) result = result.substring(0, result.length() - 1);
        return result;
    }

    private static String shortError(Exception e) {
        String message = e.getMessage();
        if (message == null || message.trim().isEmpty()) return "Cloud connection failed";
        return message.length() > 90 ? message.substring(0, 90) : message;
    }
}
