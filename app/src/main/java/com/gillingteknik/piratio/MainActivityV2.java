package com.gillingteknik.piratio;

import android.app.Activity;
import android.app.Dialog;
import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.text.InputFilter;
import android.text.InputType;
import android.view.Gravity;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;

import java.util.Locale;
import java.util.Random;

public class MainActivityV2 extends Activity {
    private static final String PREFS = "piratio_profile";
    private static final String[] SKIN_NAMES = {"Classic", "Crimson", "Ghost", "Royal"};
    private static final int[] SKIN_COSTS = {0, 250, 600, 900};
    private static final int[] SKIN_COLORS = {
            Color.rgb(110, 61, 30), Color.rgb(126, 35, 35),
            Color.rgb(66, 82, 87), Color.rgb(45, 67, 104)
    };
    private static final String ROOM_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final Random random = new Random();
    private SharedPreferences prefs;
    private FrameLayout root;
    private EditText captainNameInput;
    private TextView coinsLabel;
    private TextView accountLabel;
    private AudioController audio;
    private CloudProfileClient cloud;
    private PlayGamesAccountManager playGames;
    private boolean gameRunning;
    private String accountState = "LOCAL SAVE";
    private boolean accountConnected;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        enterImmersiveMode();

        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        root = new FrameLayout(this);
        setContentView(root);

        audio = AudioController.get(this);
        cloud = new CloudProfileClient(this);
        playGames = new PlayGamesAccountManager(this, cloud, (state, connected) -> {
            accountState = state;
            accountConnected = connected;
            refreshProfileUi();
        });

        showMainMenu();
        playGames.connect(false);
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersiveMode();
    }

    private void enterImmersiveMode() {
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        getWindow().getDecorView().setSystemUiVisibility(
                View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                        | View.SYSTEM_UI_FLAG_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                        | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                        | View.SYSTEM_UI_FLAG_LAYOUT_STABLE
        );
    }

    private void showMainMenu() {
        gameRunning = false;
        audio.playMenu();
        root.removeAllViews();
        root.setBackground(makeGradient(Color.rgb(14, 83, 116), Color.rgb(5, 35, 53)));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.HORIZONTAL);
        page.setPadding(dp(34), dp(22), dp(34), dp(22));
        root.addView(page, match());

        LinearLayout left = new LinearLayout(this);
        left.setOrientation(LinearLayout.VERTICAL);
        left.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(left, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.15f));

        left.addView(label("PIRAT.IO", 46, Color.WHITE, true));
        left.addView(label("SAIL  •  LOOT  •  BUILD YOUR LEGEND", 15, Color.rgb(245, 215, 126), true), top(3));
        left.addView(label("One huge sea • 100 bots • up to 20 captains\nGrow, choose a class after level 5, and keep your account progression.",
                15, Color.rgb(216, 236, 241), false), top(16));

        LinearLayout profile = new LinearLayout(this);
        profile.setOrientation(LinearLayout.VERTICAL);
        profile.setPadding(dp(18), dp(14), dp(18), dp(14));
        profile.setBackground(roundRect(Color.argb(188, 8, 39, 54), 18));
        LinearLayout.LayoutParams profileParams = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        profileParams.topMargin = dp(18);
        profileParams.rightMargin = dp(32);
        left.addView(profile, profileParams);

        profile.addView(label("CAPTAIN NAME", 12, Color.rgb(245, 215, 126), true));
        captainNameInput = new EditText(this);
        captainNameInput.setSingleLine(true);
        captainNameInput.setText(prefs.getString("player_name", "Captain"));
        captainNameInput.setTextColor(Color.WHITE);
        captainNameInput.setHintTextColor(Color.rgb(150, 180, 190));
        captainNameInput.setHint("Choose a name");
        captainNameInput.setTextSize(19);
        captainNameInput.setPadding(dp(12), 0, dp(12), 0);
        captainNameInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(16)});
        captainNameInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        captainNameInput.setBackground(roundRect(Color.rgb(15, 66, 82), 10));
        profile.addView(captainNameInput, fieldParams());

        coinsLabel = label("", 15, Color.rgb(255, 222, 92), true);
        profile.addView(coinsLabel, top(8));
        accountLabel = label(accountState, 12, Color.rgb(184, 216, 224), false);
        profile.addView(accountLabel, top(5));
        refreshProfileUi();

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setGravity(Gravity.CENTER);
        page.addView(actions, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.85f));

        Button play = menuButton("SET SAIL", Color.rgb(49, 153, 93));
        play.setOnClickListener(v -> startGame("OPEN OCEAN"));
        actions.addView(play, menuButtonParams());

        Button create = menuButton("CREATE PARTY", Color.rgb(55, 105, 161));
        create.setOnClickListener(v -> showCreatePartyDialog());
        actions.addView(create, menuButtonParams());

        Button join = menuButton("JOIN FRIEND", Color.rgb(55, 105, 161));
        join.setOnClickListener(v -> showJoinPartyDialog());
        actions.addView(join, menuButtonParams());

        Button skins = menuButton("SKINS & COINS", Color.rgb(126, 86, 35));
        skins.setOnClickListener(v -> showSkinsDialog());
        actions.addView(skins, menuButtonParams());

        Button settings = menuButton("SETTINGS / ACCOUNT", Color.rgb(69, 78, 91));
        settings.setOnClickListener(v -> showSettingsDialog());
        actions.addView(settings, menuButtonParams());

        TextView soundtrack = label("Original Pirat.io soundtrack • Intro + 5 sea shanties", 12,
                Color.rgb(177, 209, 217), false);
        soundtrack.setGravity(Gravity.CENTER);
        actions.addView(soundtrack, top(9));
    }

    private void startGame(String mode) {
        saveCaptainName();
        hideKeyboard();
        if (accountConnected) cloud.pushProfile(null);
        audio.playGame();

        root.removeAllViews();
        FrameLayout gameLayer = new FrameLayout(this);
        root.addView(gameLayer, match());

        AudioGameView game = new AudioGameView(this);
        game.setCaptainName(prefs.getString("player_name", "Captain"));
        gameLayer.addView(game, match());

        TextView badge = label(prefs.getString("player_name", "Captain") + "  •  " + mode, 12, Color.WHITE, true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(12), dp(4), dp(12), dp(4));
        badge.setBackground(roundRect(Color.argb(155, 5, 29, 39), 14));
        FrameLayout.LayoutParams bp = new FrameLayout.LayoutParams(FrameLayout.LayoutParams.WRAP_CONTENT, dp(31), Gravity.TOP | Gravity.CENTER_HORIZONTAL);
        bp.topMargin = dp(7);
        gameLayer.addView(badge, bp);
        gameRunning = true;
    }

    private void showSettingsDialog() {
        Dialog dialog = makeDialog();
        LinearLayout content = dialogContent();
        content.addView(dialogTitle("SETTINGS & ACCOUNT"));

        TextView status = label(accountState, 14, accountConnected ? Color.rgb(126, 225, 150) : Color.rgb(245, 215, 126), true);
        status.setGravity(Gravity.CENTER);
        content.addView(status, top(8));

        Button music = menuButton(audio.isMusicEnabled() ? "MUSIC  ON" : "MUSIC  OFF", Color.rgb(53, 95, 112));
        music.setOnClickListener(v -> {
            audio.setMusicEnabled(!audio.isMusicEnabled());
            music.setText(audio.isMusicEnabled() ? "MUSIC  ON" : "MUSIC  OFF");
        });
        content.addView(music, menuButtonParams());

        Button sfx = menuButton(audio.isSfxEnabled() ? "SFX  ON" : "SFX  OFF", Color.rgb(53, 95, 112));
        sfx.setOnClickListener(v -> {
            audio.setSfxEnabled(!audio.isSfxEnabled());
            sfx.setText(audio.isSfxEnabled() ? "SFX  ON" : "SFX  OFF");
        });
        content.addView(sfx, menuButtonParams());

        Button sync = menuButton("SYNC / SIGN IN WITH PLAY GAMES", Color.rgb(49, 127, 79));
        sync.setOnClickListener(v -> {
            status.setText("Connecting…");
            playGames.connect(true);
        });
        content.addView(sync, menuButtonParams());

        TextView note = label("Cloud save restores captain name, coins, skins and audio settings on devices using the same verified platform account.\nApple Game Center uses the same backend profile contract on iOS.",
                12, Color.rgb(184, 216, 224), false);
        note.setGravity(Gravity.CENTER);
        content.addView(note, top(7));

        Button close = menuButton("CLOSE", Color.rgb(91, 56, 51));
        close.setOnClickListener(v -> {
            if (accountConnected) cloud.pushProfile(null);
            dialog.dismiss();
            refreshProfileUi();
        });
        content.addView(close, menuButtonParams());

        dialog.setContentView(content);
        dialog.show();
        sizeDialog(dialog, 0.58f, 0.88f);
    }

    private void showSkinsDialog() {
        Dialog dialog = makeDialog();
        LinearLayout content = dialogContent();
        content.addView(dialogTitle("SKINS & COINS"));
        TextView balance = label("COINS  " + prefs.getInt("coins", 0), 18, Color.rgb(255, 222, 92), true);
        balance.setGravity(Gravity.CENTER);
        content.addView(balance, top(5));

        int selected = clamp(prefs.getInt("selected_skin", 0), 0, SKIN_NAMES.length - 1);
        int purchased = prefs.getInt("skins", 1);
        for (int i = 0; i < SKIN_NAMES.length; i++) {
            final int skin = i;
            boolean owned = (purchased & (1 << i)) != 0;
            String suffix = selected == i ? " • EQUIPPED" : owned ? " • OWNED" : " • " + SKIN_COSTS[i] + " COINS";
            Button b = menuButton(SKIN_NAMES[i] + suffix, SKIN_COLORS[i]);
            b.setOnClickListener(v -> {
                int coins = prefs.getInt("coins", 0);
                int skins = prefs.getInt("skins", 1);
                if ((skins & (1 << skin)) == 0) {
                    if (coins < SKIN_COSTS[skin]) {
                        Toast.makeText(this, "Not enough coins", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    coins -= SKIN_COSTS[skin];
                    skins |= 1 << skin;
                }
                prefs.edit().putInt("coins", coins).putInt("skins", skins).putInt("selected_skin", skin).apply();
                if (accountConnected) cloud.pushProfile(null);
                dialog.dismiss();
                showSkinsDialog();
                refreshProfileUi();
            });
            content.addView(b, menuButtonParams());
        }

        TextView storeNote = label("Real coin packs will be credited by the server only after Google Play / App Store purchase verification. Test coin buttons are removed from the account build.",
                12, Color.rgb(184, 216, 224), false);
        storeNote.setGravity(Gravity.CENTER);
        content.addView(storeNote, top(8));

        Button close = menuButton("CLOSE", Color.rgb(91, 56, 51));
        close.setOnClickListener(v -> dialog.dismiss());
        content.addView(close, menuButtonParams());
        dialog.setContentView(content);
        dialog.show();
        sizeDialog(dialog, 0.61f, 0.92f);
    }

    private void showCreatePartyDialog() {
        saveCaptainName();
        String roomCode = generateRoomCode();
        Dialog dialog = makeDialog();
        LinearLayout content = dialogContent();
        content.addView(dialogTitle("CREATE PARTY"));
        TextView code = label(roomCode, 34, Color.rgb(255, 222, 92), true);
        code.setGravity(Gravity.CENTER);
        content.addView(code, top(12));
        TextView note = label("Party codes are wired to the room-server protocol. Until the server is deployed, this starts local practice in the same 100-bot ocean.",
                13, Color.rgb(184, 216, 224), false);
        note.setGravity(Gravity.CENTER);
        content.addView(note, top(8));
        Button practice = menuButton("START PARTY PRACTICE", Color.rgb(49, 153, 93));
        practice.setOnClickListener(v -> { dialog.dismiss(); startGame("PARTY " + roomCode + " • LOCAL"); });
        content.addView(practice, menuButtonParams());
        Button close = menuButton("CLOSE", Color.rgb(91, 56, 51));
        close.setOnClickListener(v -> dialog.dismiss());
        content.addView(close, menuButtonParams());
        dialog.setContentView(content);
        dialog.show();
        sizeDialog(dialog, 0.55f, 0.78f);
    }

    private void showJoinPartyDialog() {
        saveCaptainName();
        Dialog dialog = makeDialog();
        LinearLayout content = dialogContent();
        content.addView(dialogTitle("JOIN FRIEND"));
        EditText room = new EditText(this);
        room.setSingleLine(true);
        room.setHint("6-character room code");
        room.setTextColor(Color.WHITE);
        room.setHintTextColor(Color.rgb(150, 180, 190));
        room.setGravity(Gravity.CENTER);
        room.setTextSize(20);
        room.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        room.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        room.setBackground(roundRect(Color.rgb(15, 66, 82), 10));
        content.addView(room, fieldParams());
        Button join = menuButton("JOIN", Color.rgb(55, 105, 161));
        join.setOnClickListener(v -> {
            String code = room.getText().toString().trim().toUpperCase(Locale.US);
            if (code.length() != 6) {
                Toast.makeText(this, "Enter a 6-character room code", Toast.LENGTH_SHORT).show();
                return;
            }
            Toast.makeText(this, "Online room service still needs deployment. Code: " + code, Toast.LENGTH_LONG).show();
        });
        content.addView(join, menuButtonParams());
        Button close = menuButton("CLOSE", Color.rgb(91, 56, 51));
        close.setOnClickListener(v -> dialog.dismiss());
        content.addView(close, menuButtonParams());
        dialog.setContentView(content);
        dialog.show();
        sizeDialog(dialog, 0.55f, 0.72f);
    }

    private void refreshProfileUi() {
        if (coinsLabel != null) coinsLabel.setText("COINS  " + prefs.getInt("coins", 0));
        if (accountLabel != null) {
            accountLabel.setText(accountState);
            accountLabel.setTextColor(accountConnected ? Color.rgb(126, 225, 150) : Color.rgb(184, 216, 224));
        }
        if (captainNameInput != null && accountConnected && !captainNameInput.hasFocus()) {
            captainNameInput.setText(prefs.getString("player_name", "Captain"));
        }
    }

    private void saveCaptainName() {
        if (captainNameInput == null) return;
        String name = sanitizeName(captainNameInput.getText().toString());
        prefs.edit().putString("player_name", name).apply();
    }

    private String sanitizeName(String raw) {
        String value = raw == null ? "" : raw.trim().replaceAll("\\s+", " ");
        if (value.isEmpty()) value = "Captain";
        return value.length() > 16 ? value.substring(0, 16) : value;
    }

    private String generateRoomCode() {
        StringBuilder result = new StringBuilder(6);
        for (int i = 0; i < 6; i++) result.append(ROOM_CHARS.charAt(random.nextInt(ROOM_CHARS.length())));
        return result.toString();
    }

    @Override
    protected void onPause() {
        super.onPause();
        saveCaptainName();
        if (accountConnected) cloud.pushProfile(null);
        audio.onPause();
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (audio != null) audio.onResume();
    }

    @Override
    protected void onDestroy() {
        if (isFinishing() && audio != null) audio.release();
        super.onDestroy();
    }

    @Override
    @Deprecated
    public void onBackPressed() {
        if (gameRunning) {
            if (accountConnected) cloud.pushProfile(null);
            showMainMenu();
            return;
        }
        super.onBackPressed();
    }

    private Dialog makeDialog() {
        Dialog dialog = new Dialog(this);
        if (dialog.getWindow() != null) dialog.getWindow().setBackgroundDrawableResource(android.R.color.transparent);
        return dialog;
    }

    private LinearLayout dialogContent() {
        LinearLayout content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setGravity(Gravity.CENTER_HORIZONTAL);
        content.setPadding(dp(24), dp(17), dp(24), dp(17));
        content.setBackground(roundRect(Color.rgb(12, 57, 72), 22));
        return content;
    }

    private TextView dialogTitle(String text) {
        TextView title = label(text, 27, Color.WHITE, true);
        title.setGravity(Gravity.CENTER);
        return title;
    }

    private void sizeDialog(Dialog dialog, float widthFraction, float heightFraction) {
        Window window = dialog.getWindow();
        if (window == null) return;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams();
        params.copyFrom(window.getAttributes());
        params.width = (int) (getResources().getDisplayMetrics().widthPixels * widthFraction);
        params.height = (int) (getResources().getDisplayMetrics().heightPixels * heightFraction);
        window.setAttributes(params);
    }

    private Button menuButton(String text, int color) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(16);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(9), 0, dp(9), 0);
        button.setBackground(roundRect(color, 13));
        return button;
    }

    private TextView label(String text, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(size);
        if (bold) view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return view;
    }

    private LinearLayout.LayoutParams menuButtonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(54));
        p.setMargins(0, dp(5), 0, dp(5));
        return p;
    }

    private LinearLayout.LayoutParams fieldParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, dp(50));
        p.setMargins(0, dp(7), 0, dp(4));
        return p;
    }

    private LinearLayout.LayoutParams top(int dp) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        p.topMargin = this.dp(dp);
        return p;
    }

    private FrameLayout.LayoutParams match() {
        return new FrameLayout.LayoutParams(FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.MATCH_PARENT);
    }

    private GradientDrawable roundRect(int color, float radiusDp) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(dp((int) radiusDp));
        return drawable;
    }

    private GradientDrawable makeGradient(int start, int end) {
        return new GradientDrawable(GradientDrawable.Orientation.TL_BR, new int[]{start, end});
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private void hideKeyboard() {
        View current = getCurrentFocus();
        if (current == null) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(current.getWindowToken(), 0);
        current.clearFocus();
    }
}
