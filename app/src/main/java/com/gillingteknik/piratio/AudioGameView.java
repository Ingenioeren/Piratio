package com.gillingteknik.piratio;

import android.content.Context;
import android.graphics.Canvas;
import android.view.MotionEvent;

import java.lang.reflect.Field;

/**
 * Thin bridge around GameView. It watches only the local player's fire cooldown and
 * emits one cannon SFX when that cooldown jumps from ready to reloading.
 */
public class AudioGameView extends GameView {
    private Field playerField;
    private Field cooldownField;
    private Field aliveField;

    public AudioGameView(Context context) {
        super(context);
        bindReflection();
    }

    private void bindReflection() {
        try {
            playerField = GameView.class.getDeclaredField("player");
            playerField.setAccessible(true);
            Object player = playerField.get(this);
            if (player != null) {
                cooldownField = player.getClass().getDeclaredField("fireCooldown");
                cooldownField.setAccessible(true);
                aliveField = player.getClass().getDeclaredField("alive");
                aliveField.setAccessible(true);
            }
        } catch (Exception ignored) { }
    }

    public void setCaptainName(String name) {
        try {
            Object player = playerField == null ? null : playerField.get(this);
            if (player == null) return;
            Field nameField = player.getClass().getDeclaredField("name");
            nameField.setAccessible(true);
            nameField.set(player, name);
        } catch (Exception ignored) { }
    }

    private float cooldown() {
        try {
            Object player = playerField == null ? null : playerField.get(this);
            if (player == null || cooldownField == null) return -1f;
            return cooldownField.getFloat(player);
        } catch (Exception ignored) {
            return -1f;
        }
    }

    private boolean localPlayerAlive() {
        try {
            Object player = playerField == null ? null : playerField.get(this);
            return player != null && aliveField != null && aliveField.getBoolean(player);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void maybePlayShot(float before, float after, boolean aliveBefore, boolean aliveAfter) {
        // A real shot changes fireCooldown from ready/near-ready to the ship's reload time.
        // Requiring the player to be alive on both sides avoids a false sound on respawn.
        if (aliveBefore && aliveAfter && before >= 0f && after > 0.15f && after > before + 0.10f) {
            AudioController.get(getContext()).playCannon();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float before = cooldown();
        boolean aliveBefore = localPlayerAlive();
        boolean result = super.onTouchEvent(event);
        float after = cooldown();
        boolean aliveAfter = localPlayerAlive();
        maybePlayShot(before, after, aliveBefore, aliveAfter);
        return result;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float before = cooldown();
        boolean aliveBefore = localPlayerAlive();
        super.onDraw(canvas);
        float after = cooldown();
        boolean aliveAfter = localPlayerAlive();
        maybePlayShot(before, after, aliveBefore, aliveAfter);
    }
}
