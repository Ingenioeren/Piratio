package com.gillingteknik.piratio;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.SoundPool;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

public final class AudioController {
    private static final String INTRO_ASSET = "music/piratio_intro.ogg";
    private static final String[] SHANTY_ASSETS = {
            "music/piratio_sea_shanty_1.ogg",
            "music/piratio_sea_shanty_2.ogg",
            "music/piratio_sea_shanty_3.ogg",
            "music/piratio_sea_shanty_4.ogg",
            "music/piratio_sea_shanty_5.ogg"
    };

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
    private final int introResource;
    private final int[] shanties;

    private boolean cannonLoaded;
    private MediaPlayer musicPlayer;
    private Mode mode = Mode.NONE;
    private int lastTrack = -1;
    private String currentTrackKey = "";
    private boolean pausedByLifecycle;

    private enum Mode { NONE, MENU, GAME }

    private static final class Track {
        final String assetPath;
        final int resourceId;
        final String key;

        Track(String assetPath, int resourceId, String key) {
            this.assetPath = assetPath;
            this.resourceId = resourceId;
            this.key = key;
        }
    }

    private AudioController(Context context) {
        this.context = context;
        this.prefs = context.getSharedPreferences("piratio_profile", Context.MODE_PRIVATE);

        introResource = rawId("piratio_intro");
        shanties = existingRawIds(
                "piratio_sea_shanty_1",
                "piratio_sea_shanty_2",
                "piratio_sea_shanty_3",
                "piratio_sea_shanty_4",
                "piratio_sea_shanty_5"
        );

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

    private int rawId(String name) {
        return context.getResources().getIdentifier(name, "raw", context.getPackageName());
    }

    private int[] existingRawIds(String... names) {
        ArrayList<Integer> ids = new ArrayList<>();
        for (String name : names) {
            int id = rawId(name);
            if (id != 0) ids.add(id);
        }
        int[] result = new int[ids.size()];
        for (int i = 0; i < ids.size(); i++) result[i] = ids.get(i);
        return result;
    }

    private boolean hasAsset(String path) {
        try (AssetFileDescriptor ignored = context.getAssets().openFd(path)) {
            return true;
        } catch (Exception ignored) {
            return false;
        }
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

    public boolean hasSoundtrackAssets() {
        if (hasAsset(INTRO_ASSET) || introResource != 0) return true;
        for (String asset : SHANTY_ASSETS) if (hasAsset(asset)) return true;
        return shanties.length > 0;
    }

    public void setMusicEnabled(boolean enabled) {
        prefs.edit().putBoolean("music_enabled", enabled).apply();
        if (!enabled) stopMusic();
        else if (mode == Mode.MENU) playMenu();
        else if (mode == Mode.GAME) playGame();
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

        Track intro = hasAsset(INTRO_ASSET)
                ? new Track(INTRO_ASSET, 0, "asset:intro")
                : introResource != 0 ? new Track(null, introResource, "raw:intro") : null;
        if (intro == null) {
            stopMusicOnly();
            return;
        }
        if (musicPlayer != null && musicPlayer.isPlaying() && currentTrackKey.equals(intro.key)) return;
        startTrack(intro, true, false);
    }

    public void playGame() {
        mode = Mode.GAME;
        if (!isMusicEnabled()) {
            stopMusicOnly();
            return;
        }
        if (musicPlayer != null && musicPlayer.isPlaying() && currentTrackKey.startsWith("game:")) return;
        playNextShanty();
    }

    private List<Track> gameTracks() {
        ArrayList<Track> tracks = new ArrayList<>();
        for (int i = 0; i < SHANTY_ASSETS.length; i++) {
            if (hasAsset(SHANTY_ASSETS[i])) {
                tracks.add(new Track(SHANTY_ASSETS[i], 0, "game:asset:" + i));
            }
        }
        if (!tracks.isEmpty()) return tracks;
        for (int i = 0; i < shanties.length; i++) {
            tracks.add(new Track(null, shanties[i], "game:raw:" + i));
        }
        return tracks;
    }

    private void playNextShanty() {
        if (mode != Mode.GAME || !isMusicEnabled()) return;
        List<Track> tracks = gameTracks();
        if (tracks.isEmpty()) {
            stopMusicOnly();
            return;
        }

        List<Integer> candidates = new ArrayList<>();
        for (int i = 0; i < tracks.size(); i++) {
            if (i != lastTrack || tracks.size() == 1) candidates.add(i);
        }
        if (candidates.isEmpty()) candidates.add(0);
        Collections.shuffle(candidates, random);
        int index = candidates.get(0);
        lastTrack = index;
        startTrack(tracks.get(index), false, true);
    }

    private void startTrack(Track track, boolean looping, boolean advanceOnComplete) {
        stopMusicOnly();
        if (!isMusicEnabled() || track == null) return;
        try {
            if (track.assetPath != null) {
                AssetFileDescriptor afd = context.getAssets().openFd(track.assetPath);
                musicPlayer = new MediaPlayer();
                musicPlayer.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
                afd.close();
                musicPlayer.setAudioAttributes(new AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_GAME)
                        .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                        .build());
                musicPlayer.prepare();
            } else {
                musicPlayer = MediaPlayer.create(context, track.resourceId);
            }
            currentTrackKey = track.key;
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
        currentTrackKey = "";
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
