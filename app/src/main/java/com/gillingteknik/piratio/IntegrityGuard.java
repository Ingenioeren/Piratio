package com.gillingteknik.piratio;

import android.content.Context;
import android.util.Base64;

import com.google.android.play.core.integrity.IntegrityManagerFactory;
import com.google.android.play.core.integrity.StandardIntegrityManager;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

public final class IntegrityGuard {
    public interface Callback {
        void onResult(boolean success, String token, String requestHash, String message);
    }

    private final StandardIntegrityManager manager;
    private volatile StandardIntegrityManager.StandardIntegrityTokenProvider provider;
    private volatile boolean preparing;

    public IntegrityGuard(Context context) {
        manager = IntegrityManagerFactory.createStandard(context.getApplicationContext());
    }

    public boolean isConfigured() {
        return BuildConfig.INTEGRITY_PROJECT_NUMBER > 0L;
    }

    public void warmUp() {
        if (!isConfigured() || provider != null || preparing) return;
        preparing = true;
        manager.prepareIntegrityToken(
                StandardIntegrityManager.PrepareIntegrityTokenRequest.builder()
                        .setCloudProjectNumber(BuildConfig.INTEGRITY_PROJECT_NUMBER)
                        .build()
        ).addOnSuccessListener(result -> {
            provider = result;
            preparing = false;
        }).addOnFailureListener(error -> preparing = false);
    }

    public void tokenFor(String binding, Callback callback) {
        String requestHash = hash(binding);
        if (!isConfigured()) {
            callback.onResult(false, "", requestHash, "Play Integrity is not configured");
            return;
        }
        if (provider == null) {
            warmUp();
            callback.onResult(false, "", requestHash, "Play Integrity is warming up");
            return;
        }
        provider.request(
                StandardIntegrityManager.StandardIntegrityTokenRequest.builder()
                        .setRequestHash(requestHash)
                        .build()
        ).addOnSuccessListener(result -> callback.onResult(true, result.token(), requestHash, "OK"))
         .addOnFailureListener(error -> {
             provider = null;
             warmUp();
             callback.onResult(false, "", requestHash, "Integrity check unavailable");
         });
    }

    public static String hash(String binding) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] bytes = digest.digest(binding.getBytes(StandardCharsets.UTF_8));
            return Base64.encodeToString(bytes, Base64.URL_SAFE | Base64.NO_WRAP | Base64.NO_PADDING);
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
