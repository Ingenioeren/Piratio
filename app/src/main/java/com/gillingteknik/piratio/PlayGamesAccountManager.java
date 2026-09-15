package com.gillingteknik.piratio;

import android.app.Activity;

import com.google.android.gms.games.AuthenticationResult;
import com.google.android.gms.games.GamesSignInClient;
import com.google.android.gms.games.PlayGames;

public final class PlayGamesAccountManager {
    public interface Listener { void onState(String state, boolean connected); }

    private final Activity activity;
    private final CloudProfileClient cloud;
    private final Listener listener;
    private final GamesSignInClient signInClient;
    private final IntegrityGuard integrity;

    public PlayGamesAccountManager(Activity activity, CloudProfileClient cloud, Listener listener) {
        this.activity = activity;
        this.cloud = cloud;
        this.listener = listener;
        this.signInClient = PlayGames.getGamesSignInClient(activity);
        this.integrity = new IntegrityGuard(activity);
        this.integrity.warmUp();
    }

    public boolean isConfigured() {
        return BuildConfig.PGS_WEB_CLIENT_ID != null
                && !BuildConfig.PGS_WEB_CLIENT_ID.trim().isEmpty()
                && cloud.isConfigured();
    }

    public void connect(boolean interactive) {
        if (!isConfigured()) {
            state("LOCAL PRACTICE • account backend not configured", false);
            return;
        }
        state("Checking Play Games account…", false);
        signInClient.isAuthenticated().addOnCompleteListener(task -> {
            if (!task.isSuccessful()) {
                state("Play Games unavailable", false);
                return;
            }
            AuthenticationResult result = task.getResult();
            if (result != null && result.isAuthenticated()) {
                requestServerCode();
            } else if (interactive) {
                signInClient.signIn().addOnCompleteListener(signTask -> {
                    if (signTask.isSuccessful() && signTask.getResult() != null && signTask.getResult().isAuthenticated()) requestServerCode();
                    else state("Play Games sign-in cancelled", false);
                });
            } else {
                state("PLAY GAMES • tap Sync Account to sign in", false);
            }
        });
    }

    private void requestServerCode() {
        state("Authenticating cloud profile…", false);
        signInClient.requestServerSideAccess(BuildConfig.PGS_WEB_CLIENT_ID, false)
                .addOnCompleteListener(task -> {
                    if (!task.isSuccessful() || task.getResult() == null || task.getResult().isEmpty()) {
                        state("Could not obtain Play Games server code", false);
                        return;
                    }
                    String authCode = task.getResult();
                    if (!integrity.isConfigured()) {
                        // Debug/development builds can still exercise auth; production backend can require attestation.
                        cloud.loginWithGoogleAuthCode(authCode, "", IntegrityGuard.hash("auth-google:" + authCode), this::finishLogin);
                        return;
                    }
                    state("Verifying official app build…", false);
                    integrity.tokenFor("auth-google:" + authCode, (success, token, requestHash, message) -> {
                        if (!success) {
                            state(message, false);
                            return;
                        }
                        cloud.loginWithGoogleAuthCode(authCode, token, requestHash, this::finishLogin);
                    });
                });
    }

    private void finishLogin(boolean success, String message) {
        if (!success) {
            state(message, false);
            return;
        }
        cloud.pullProfile((pullSuccess, pullMessage) ->
                state(pullSuccess ? "PLAY GAMES • VERIFIED CLOUD" : message, true));
    }

    private void state(String text, boolean connected) {
        activity.runOnUiThread(() -> {
            if (listener != null) listener.onState(text, connected);
        });
    }
}
