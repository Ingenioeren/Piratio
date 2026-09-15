package com.gillingteknik.piratio;

import android.content.Context;
import android.graphics.Canvas;
import android.view.MotionEvent;

import java.lang.reflect.Field;

/**
 * Thin prototype bridge around GameView. It watches the local player's fire cooldown
 * so cannon SFX are emitted for this device's player only, never for bots/remotes.
 */
public class AudioGameView extends GameView {
    private Field playerField;
    private Field cooldownField;
    private Field aliveField;
    private Field fireHeldField;

    public AudioGameView(Context context) {
        super(context);
        bindReflection();
    }

    private void bindReflection() {
        try {
            playerField = GameView.class.getDeclaredField("player");
            playerField.setAccessible(true);
            fireHeldField = GameView.class.getDeclaredField("fireHeld");
            fireHeldField.setAccessible(true);
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

    private boolean fireHeld() {
        try {
            return fireHeldField != null && fireHeldField.getBoolean(this);
        } catch (Exception ignored) {
            return false;
        }
    }

    private void maybePlayShot(float before, float after) {
        if (localPlayerAlive() && fireHeld() && after > 0.10f && after > before + 0.10f) {
            AudioController.get(getContext()).playCannon();
        }
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float before = cooldown();
        boolean result = super.onTouchEvent(event);
        float after = cooldown();
        maybePlayShot(before, after);
        return result;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float before = cooldown();
        super.onDraw(canvas);
        float after = cooldown();
        maybePlayShot(before, after);
    }
}
