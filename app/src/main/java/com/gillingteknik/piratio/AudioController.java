package com.gillingteknik.piratio;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.res.AssetFileDescriptor;
import android.media.AudioAttributes;
import android.media.MediaPlayer;
import android.media.SoundPool;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

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
    private static final float MUSIC_VOLUME = 0.52f;
    private static final long INTRO_CROSSFADE_MS = 3500L;
    private static final long FADE_STEP_MS = 50L;

    private static AudioController instance;

    public static synchronized AudioController get(Context context) {
        if (instance == null) instance = new AudioController(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final SharedPreferences prefs;
    private final Random random = new Random();
    private final SoundPool soundPool;
    private final Handler audioHandler = new Handler(Looper.getMainLooper());
    private final int cannonSoundId;
    private final int introResource;
    private final int[] shanties;

    private MediaPlayer musicPlayer;
    private MediaPlayer fadePlayer;
    private Mode mode = Mode.NONE;
    private int lastTrack = -1;
    private String currentTrackKey = "";
    private boolean pausedByLifecycle;
    private Track currentIntroTrack;

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
        startIntroCrossfadeLoop(intro);
    }

    public void playGame() {
        mode = Mode.GAME;
        currentIntroTrack = null;
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
            if (hasAsset(SHANTY_ASSETS[i])) tracks.add(new Track(SHANTY_ASSETS[i], 0, "game:asset:" + i));
        }
        if (!tracks.isEmpty()) return tracks;
        for (int i = 0; i < shanties.length; i++) tracks.add(new Track(null, shanties[i], "game:raw:" + i));
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
        startTrack(tracks.get(index), true);
    }

    private MediaPlayer createPlayer(Track track) throws Exception {
        MediaPlayer player;
        if (track.assetPath != null) {
            AssetFileDescriptor afd = context.getAssets().openFd(track.assetPath);
            player = new MediaPlayer();
            player.setDataSource(afd.getFileDescriptor(), afd.getStartOffset(), afd.getLength());
            afd.close();
            player.setAudioAttributes(new AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_GAME)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build());
            player.prepare();
        } else {
            player = MediaPlayer.create(context, track.resourceId);
        }
        return player;
    }

    private void startIntroCrossfadeLoop(Track track) {
        stopMusicOnly();
        if (!isMusicEnabled()) return;
        try {
            currentIntroTrack = track;
            musicPlayer = createPlayer(track);
            if (musicPlayer == null) return;
            currentTrackKey = track.key;
            musicPlayer.setLooping(false);
            musicPlayer.setVolume(MUSIC_VOLUME, MUSIC_VOLUME);
            musicPlayer.setOnCompletionListener(mp -> {
                if (mode == Mode.MENU && isMusicEnabled() && fadePlayer == null) startIntroCrossfadeLoop(track);
            });
            musicPlayer.start();
            scheduleIntroCrossfade();
        } catch (Exception ignored) {
            releasePlayers();
        }
    }

    private void scheduleIntroCrossfade() {
        audioHandler.removeCallbacksAndMessages(null);
        if (mode != Mode.MENU || musicPlayer == null || currentIntroTrack == null) return;
        try {
            long remainingUntilFade = musicPlayer.getDuration() - musicPlayer.getCurrentPosition() - INTRO_CROSSFADE_MS;
            audioHandler.postDelayed(this::beginIntroCrossfade, Math.max(250L, remainingUntilFade));
        } catch (Exception ignored) { }
    }

    private void beginIntroCrossfade() {
        if (mode != Mode.MENU || !isMusicEnabled() || musicPlayer == null || fadePlayer != null || currentIntroTrack == null) return;
        try {
            final MediaPlayer outgoing = musicPlayer;
            final MediaPlayer incoming = createPlayer(currentIntroTrack);
            if (incoming == null) return;
            fadePlayer = incoming;
            incoming.setLooping(false);
            incoming.setVolume(0f, 0f);
            incoming.start();
            final long startedAt = SystemClock.uptimeMillis();

            Runnable fadeStep = new Runnable() {
                @Override
                public void run() {
                    if (mode != Mode.MENU || fadePlayer != incoming) return;
                    float t = Math.min(1f, (SystemClock.uptimeMillis() - startedAt) / (float) INTRO_CROSSFADE_MS);
                    try { outgoing.setVolume(MUSIC_VOLUME * (1f - t), MUSIC_VOLUME * (1f - t)); } catch (Exception ignored) { }
                    try { incoming.setVolume(MUSIC_VOLUME * t, MUSIC_VOLUME * t); } catch (Exception ignored) { }

                    if (t < 1f) {
                        audioHandler.postDelayed(this, FADE_STEP_MS);
                    } else {
                        releaseOne(outgoing);
                        musicPlayer = incoming;
                        fadePlayer = null;
                        currentTrackKey = currentIntroTrack.key;
                        musicPlayer.setOnCompletionListener(mp -> {
                            if (mode == Mode.MENU && isMusicEnabled() && fadePlayer == null) startIntroCrossfadeLoop(currentIntroTrack);
                        });
                        scheduleIntroCrossfade();
                    }
                }
            };
            audioHandler.post(fadeStep);
        } catch (Exception ignored) {
            if (fadePlayer != null) releaseOne(fadePlayer);
            fadePlayer = null;
            scheduleIntroCrossfade();
        }
    }

    private void startTrack(Track track, boolean advanceOnComplete) {
        stopMusicOnly();
        if (!isMusicEnabled() || track == null) return;
        try {
            musicPlayer = createPlayer(track);
            currentTrackKey = track.key;
            if (musicPlayer == null) return;
            musicPlayer.setVolume(MUSIC_VOLUME, MUSIC_VOLUME);
            musicPlayer.setLooping(false);
            if (advanceOnComplete) {
                musicPlayer.setOnCompletionListener(mp -> {
                    releaseOne(musicPlayer);
                    musicPlayer = null;
                    playNextShanty();
                });
            }
            musicPlayer.start();
        } catch (Exception ignored) {
            releasePlayers();
        }
    }

    public void playCannon() {
        if (!isSfxEnabled() || cannonSoundId <= 0) return;
        // AudioController is created in the menu, so SoundPool has ample time to load before gameplay.
        // Do not gate playback on an async load flag; that flag could miss the callback on some devices.
        soundPool.play(cannonSoundId, 1.0f, 1.0f, 2, 0, 1.0f);
    }

    public void onPause() {
        pausedByLifecycle = false;
        audioHandler.removeCallbacksAndMessages(null);
        if (musicPlayer != null && musicPlayer.isPlaying()) {
            try {
                musicPlayer.pause();
                pausedByLifecycle = true;
            } catch (Exception ignored) { }
        }
        if (fadePlayer != null && fadePlayer.isPlaying()) {
            try {
                fadePlayer.pause();
                pausedByLifecycle = true;
            } catch (Exception ignored) { }
        }
    }

    public void onResume() {
        if (!isMusicEnabled()) return;
        if (mode == Mode.MENU && pausedByLifecycle) {
            // Restart the crossfade loop cleanly after lifecycle interruptions instead of resuming mid-fade.
            Track intro = currentIntroTrack;
            if (intro != null) startIntroCrossfadeLoop(intro);
            pausedByLifecycle = false;
            return;
        }
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
        audioHandler.removeCallbacksAndMessages(null);
        releasePlayers();
        currentTrackKey = "";
    }

    private void releasePlayers() {
        releaseOne(musicPlayer);
        releaseOne(fadePlayer);
        musicPlayer = null;
        fadePlayer = null;
    }

    private void releaseOne(MediaPlayer player) {
        if (player == null) return;
        try { player.setOnCompletionListener(null); } catch (Exception ignored) { }
        try { player.stop(); } catch (Exception ignored) { }
        try { player.release(); } catch (Exception ignored) { }
    }

    public void release() {
        audioHandler.removeCallbacksAndMessages(null);
        releasePlayers();
        soundPool.release();
        instance = null;
    }
}
