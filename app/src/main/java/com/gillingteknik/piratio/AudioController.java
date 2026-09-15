package com.gillingteknik.piratio;

import android.content.Context;
import android.content.SharedPreferences;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.SoundPool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public final class AudioController {
    private static AudioController instance;

    public static synchronized AudioController get(Context context) {
        if (instance == null) instance = new AudioController(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final Random random = new Random();
    private final SoundPool soundPool;
    private final int cannonSoundId;
    private boolean cannonLoaded;
    private MediaPlayer musicPlayer;
    private Mode mode = Mode.NONE;
    private int lastTrack = -1;
    private boolean pausedByLifecycle;

    private final int[] shanties = {
            R.raw.piratio_sea_shanty_1,
            R.raw.piratio_sea_shanty_2,
            R.raw.piratio_sea_shanty_3,
            R.raw.piratio_sea_shanty_4,
            R.raw.piratio_sea_shanty_5
    };

    private enum Mode { NONE, MENU, GAME }

    private AudioController(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences("piratio_profile", Context.MODE_PRIVATE);
        AudioAttributes attributes = new AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build();
        soundPool = new SoundPool.Builder()
                .setMaxStreams(4)
                .setAudioAttributes(attributes)
                .build();
        soundPool.setOnLoadCompleteListener((pool, sampleId, status) -> {
            if (sampleId == cannonSoundIdSafe() && status == 0) cannonLoaded = true;
        });
        cannonSoundId = soundPool.load(context, R.raw.cannon_shot, 1);
    }

    private int cannonSoundIdSafe() {
        return cannonSoundId;
    }

    public boolean isMusicEnabled() {
        return prefs.getBoolean("music_enabled", true);
    }

    public boolean isSfxEnabled() {
        return prefs.getBoolean("sfx_enabled", true);
    }

    public void setMusicEnabled(boolean enabled) {
        prefs.edit().putBoolean("music_enabled", enabled).apply();
        if (!enabled) {
            stopMusic();
        } else if (mode == Mode.MENU) {
            playMenu();
        } else if (mode == Mode.GAME) {
            playGame();
        }
    }

    public void setSfxEnabled(boolean enabled) {
        prefs.edit().putBoolean("sfx_enabled", enabled).apply();
    }

    public void playMenu() {
        mode = Mode.MENU;
        if (!isMusicEnabled()) {
            stopMusicOnly();
            return;
        }
        if (musicPlayer != null && musicPlayer.isPlaying() && currentResourceTag == R.raw.piratio_intro) return;
        startTrack(R.raw.piratio_intro, true, false);
    }

    public void playGame() {
        mode = Mode.GAME;
        if (!isMusicEnabled()) {
            stopMusicOnly();
            return;
        }
        if (musicPlayer != null && musicPlayer.isPlaying() && currentResourceTag != R.raw.piratio_intro) return;
        playNextShanty();
    }

    private int currentResourceTag = -1;

    private void playNextShanty() {
        if (mode != Mode.GAME || !isMusicEnabled()) return;
        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < shanties.length; i++) if (i != lastTrack) candidates.add(i);
        if (candidates.isEmpty()) candidates.add(0);
        Collections.shuffle(candidates, random);
        int index = candidates.get(0);
        lastTrack = index;
        startTrack(shanties[index], false, true);
    }

    private void startTrack(int resourceId, boolean looping, boolean advanceOnComplete) {
        stopMusicOnly();
        if (!isMusicEnabled()) return;
        try {
            musicPlayer = MediaPlayer.create(context, resourceId);
            currentResourceTag = resourceId;
            if (musicPlayer == null) return;
            musicPlayer.setVolume(0.52f, 0.52f);
            musicPlayer.setLooping(looping);
            if (advanceOnComplete) {
                musicPlayer.setOnCompletionListener(mp -> {
                    releasePlayer();
                    playNextShanty();
                });
            }
            musicPlayer.start();
        } catch (Exception ignored) {
            releasePlayer();
        }
    }

    public void playCannon() {
        if (!isSfxEnabled() || !cannonLoaded) return;
        soundPool.play(cannonSoundId, 0.82f, 0.82f, 1, 0, 1.0f);
    }

    public void onPause() {
        pausedByLifecycle = false;
        if (musicPlayer != null && musicPlayer.isPlaying()) {
            try {
                musicPlayer.pause();
                pausedByLifecycle = true;
            } catch (Exception ignored) { }
        }
    }

    public void onResume() {
        if (!isMusicEnabled()) return;
        if (pausedByLifecycle && musicPlayer != null) {
            try {
                musicPlayer.start();
                pausedByLifecycle = false;
                return;
            } catch (Exception ignored) { }
        }
        if (mode == Mode.MENU) playMenu();
        else if (mode == Mode.GAME) playGame();
    }

    private void stopMusic() {
        stopMusicOnly();
    }

    private void stopMusicOnly() {
        releasePlayer();
        currentResourceTag = -1;
    }

    private void releasePlayer() {
        if (musicPlayer == null) return;
        try { musicPlayer.stop(); } catch (Exception ignored) { }
        try { musicPlayer.release(); } catch (Exception ignored) { }
        musicPlayer = null;
    }

    public void release() {
        releasePlayer();
        soundPool.release();
        instance = null;
    }
}
