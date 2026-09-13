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
import android.widget.Space;
import android.widget.TextView;
import android.widget.Toast;

import java.lang.reflect.Field;
import java.util.Locale;
import java.util.Random;

public class MainActivity extends Activity {
    private static final String PREFS = "piratio_profile";
    private static final String[] SKIN_NAMES = {"Classic", "Crimson", "Ghost", "Royal"};
    private static final int[] SKIN_COSTS = {0, 250, 600, 900};
    private static final int[] SKIN_COLORS = {
            Color.rgb(110, 61, 30), Color.rgb(126, 35, 35),
            Color.rgb(66, 82, 87), Color.rgb(45, 67, 104)
    };
    private static final String ROOM_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";

    private final Random random = new Random();
    private FrameLayout root;
    private SharedPreferences prefs;
    private EditText captainNameInput;
    private TextView coinsLabel;
    private boolean gameRunning;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        requestWindowFeature(Window.FEATURE_NO_TITLE);
        enterImmersiveMode();

        prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        root = new FrameLayout(this);
        setContentView(root);
        showMainMenu();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) enterImmersiveMode();
    }

    private void enterImmersiveMode() {
        getWindow().setFlags(
                WindowManager.LayoutParams.FLAG_FULLSCREEN,
                WindowManager.LayoutParams.FLAG_FULLSCREEN
        );
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
        root.removeAllViews();
        root.setBackground(makeGradient(Color.rgb(14, 83, 116), Color.rgb(5, 35, 53), 0));

        LinearLayout page = new LinearLayout(this);
        page.setOrientation(LinearLayout.HORIZONTAL);
        page.setPadding(dp(34), dp(24), dp(34), dp(24));
        root.addView(page, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        LinearLayout hero = new LinearLayout(this);
        hero.setOrientation(LinearLayout.VERTICAL);
        hero.setGravity(Gravity.CENTER_VERTICAL);
        page.addView(hero, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 1.18f));

        TextView title = label("PIRAT.IO", 46, Color.WHITE, true);
        hero.addView(title);

        TextView subtitle = label("SAIL  •  LOOT  •  RULE THE SEA", 16, Color.rgb(245, 215, 126), true);
        LinearLayout.LayoutParams subtitleParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        subtitleParams.topMargin = dp(4);
        hero.addView(subtitle, subtitleParams);

        TextView body = label("Grow from a tiny sloop into a floating fortress.\nFight pirates, hunt merchants and use the wind to escape.",
                16, Color.rgb(219, 238, 242), false);
        LinearLayout.LayoutParams bodyParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        bodyParams.topMargin = dp(22);
        bodyParams.rightMargin = dp(32);
        hero.addView(body, bodyParams);

        LinearLayout profileCard = new LinearLayout(this);
        profileCard.setOrientation(LinearLayout.VERTICAL);
        profileCard.setPadding(dp(18), dp(14), dp(18), dp(14));
        profileCard.setBackground(roundRect(Color.argb(185, 9, 40, 54), dp(18)));
        LinearLayout.LayoutParams profileParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        profileParams.topMargin = dp(24);
        profileParams.rightMargin = dp(34);
        hero.addView(profileCard, profileParams);

        TextView captainLabel = label("CAPTAIN NAME", 13, Color.rgb(245, 215, 126), true);
        profileCard.addView(captainLabel);

        captainNameInput = new EditText(this);
        captainNameInput.setSingleLine(true);
        captainNameInput.setText(prefs.getString("player_name", "Captain"));
        captainNameInput.setTextColor(Color.WHITE);
        captainNameInput.setHintTextColor(Color.rgb(150, 180, 190));
        captainNameInput.setHint("Choose a name");
        captainNameInput.setTextSize(20);
        captainNameInput.setPadding(dp(12), dp(5), dp(12), dp(5));
        captainNameInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(16)});
        captainNameInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_SENTENCES);
        captainNameInput.setBackground(roundRect(Color.rgb(15, 66, 82), dp(10)));
        LinearLayout.LayoutParams nameParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(50)
        );
        nameParams.topMargin = dp(7);
        profileCard.addView(captainNameInput, nameParams);

        coinsLabel = label("", 16, Color.rgb(255, 222, 92), true);
        LinearLayout.LayoutParams coinParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        coinParams.topMargin = dp(10);
        profileCard.addView(coinsLabel, coinParams);
        refreshCoinsLabel();

        LinearLayout actions = new LinearLayout(this);
        actions.setOrientation(LinearLayout.VERTICAL);
        actions.setGravity(Gravity.CENTER);
        page.addView(actions, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.MATCH_PARENT, 0.82f));

        Button quickPlay = menuButton("QUICK PLAY", Color.rgb(49, 153, 93));
        quickPlay.setOnClickListener(v -> startGame("BOT MATCH"));
        actions.addView(quickPlay, menuButtonParams());

        Button createParty = menuButton("CREATE PARTY", Color.rgb(55, 105, 161));
        createParty.setOnClickListener(v -> showCreatePartyDialog());
        actions.addView(createParty, menuButtonParams());

        Button joinFriend = menuButton("JOIN FRIEND", Color.rgb(55, 105, 161));
        joinFriend.setOnClickListener(v -> showJoinPartyDialog());
        actions.addView(joinFriend, menuButtonParams());

        Button skins = menuButton("SKINS & COINS", Color.rgb(126, 86, 35));
        skins.setOnClickListener(v -> showSkinsDialog());
        actions.addView(skins, menuButtonParams());

        TextView botNote = label("Every match keeps bots in the world,\neven when real players join.", 13,
                Color.rgb(184, 216, 224), false);
        botNote.setGravity(Gravity.CENTER);
        LinearLayout.LayoutParams noteParams = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        noteParams.topMargin = dp(13);
        actions.addView(botNote, noteParams);
    }

    private void startGame(String mode) {
        String name = sanitizeName(captainNameInput == null ? "Captain" : captainNameInput.getText().toString());
        prefs.edit().putString("player_name", name).apply();
        hideKeyboard();

        root.removeAllViews();
        FrameLayout gameLayer = new FrameLayout(this);
        root.addView(gameLayer, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        GameView game = new GameView(this);
        applyCaptainName(game, name);
        gameLayer.addView(game, new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT
        ));

        TextView badge = label(name + "  •  " + mode, 12, Color.WHITE, true);
        badge.setGravity(Gravity.CENTER);
        badge.setPadding(dp(12), dp(5), dp(12), dp(5));
        badge.setBackground(roundRect(Color.argb(155, 5, 29, 39), dp(14)));
        FrameLayout.LayoutParams badgeParams = new FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT, dp(32), Gravity.TOP | Gravity.CENTER_HORIZONTAL
        );
        badgeParams.topMargin = dp(8);
        gameLayer.addView(badge, badgeParams);
        gameRunning = true;
    }

    /**
     * Temporary bridge while GameView is still the prototype canvas implementation.
     * The networking refactor will move the player profile into a public session model.
     */
    private void applyCaptainName(GameView game, String name) {
        try {
            Field playerField = GameView.class.getDeclaredField("player");
            playerField.setAccessible(true);
            Object player = playerField.get(game);
            if (player == null) return;
            Field nameField = player.getClass().getDeclaredField("name");
            nameField.setAccessible(true);
            nameField.set(player, name);
        } catch (Exception ignored) {
            // The match badge still shows the selected profile name if this bridge ever changes.
        }
    }

    private void showCreatePartyDialog() {
        saveCaptainName();
        String roomCode = generateRoomCode();
        Dialog dialog = makeDialog();
        LinearLayout content = dialogContent();

        content.addView(dialogTitle("CREATE PARTY"));
        TextView info = label("Share this room code with friends:", 16, Color.rgb(220, 238, 242), false);
        content.addView(info, spaced(dp(8)));

        TextView code = label(roomCode, 34, Color.rgb(255, 222, 92), true);
        code.setGravity(Gravity.CENTER);
        code.setPadding(0, dp(10), 0, dp(10));
        content.addView(code, spaced(dp(8)));

        TextView status = label("Lobby UI is ready. The internet room server is the next networking step, so this code is not live yet.",
                14, Color.rgb(184, 216, 224), false);
        status.setGravity(Gravity.CENTER);
        content.addView(status, spaced(dp(8)));

        Button practice = menuButton("START PARTY PRACTICE", Color.rgb(49, 153, 93));
        practice.setOnClickListener(v -> {
            dialog.dismiss();
            startGame("PARTY " + roomCode + " • LOCAL");
        });
        content.addView(practice, menuButtonParams());

        Button close = menuButton("CLOSE", Color.rgb(91, 56, 51));
        close.setOnClickListener(v -> dialog.dismiss());
        content.addView(close, menuButtonParams());

        dialog.setContentView(content);
        dialog.show();
        sizeDialog(dialog, 0.55f, 0.80f);
    }

    private void showJoinPartyDialog() {
        saveCaptainName();
        Dialog dialog = makeDialog();
        LinearLayout content = dialogContent();
        content.addView(dialogTitle("JOIN FRIEND"));

        EditText roomInput = new EditText(this);
        roomInput.setSingleLine(true);
        roomInput.setHint("6-character room code");
        roomInput.setTextColor(Color.WHITE);
        roomInput.setHintTextColor(Color.rgb(150, 180, 190));
        roomInput.setTextSize(20);
        roomInput.setGravity(Gravity.CENTER);
        roomInput.setFilters(new InputFilter[]{new InputFilter.LengthFilter(6)});
        roomInput.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS);
        roomInput.setBackground(roundRect(Color.rgb(15, 66, 82), dp(10)));
        content.addView(roomInput, fieldParams());

        TextView status = label("Room joining will become live when the multiplayer lobby service is deployed.",
                14, Color.rgb(184, 216, 224), false);
        status.setGravity(Gravity.CENTER);
        content.addView(status, spaced(dp(10)));

        Button join = menuButton("JOIN", Color.rgb(55, 105, 161));
        join.setOnClickListener(v -> {
            String code = roomInput.getText().toString().trim().toUpperCase(Locale.US);
            if (code.length() != 6) {
                Toast.makeText(this, "Enter a 6-character room code", Toast.LENGTH_SHORT).show();
                return;
            }
            Toast.makeText(this, "Room server is not deployed yet — code " + code + " saved for the networking build.", Toast.LENGTH_LONG).show();
        });
        content.addView(join, menuButtonParams());

        Button close = menuButton("CLOSE", Color.rgb(91, 56, 51));
        close.setOnClickListener(v -> dialog.dismiss());
        content.addView(close, menuButtonParams());

        dialog.setContentView(content);
        dialog.show();
        sizeDialog(dialog, 0.55f, 0.72f);
    }

    private void showSkinsDialog() {
        Dialog dialog = makeDialog();
        LinearLayout content = dialogContent();
        content.addView(dialogTitle("SKINS & COINS"));

        TextView balance = label("", 18, Color.rgb(255, 222, 92), true);
        balance.setGravity(Gravity.CENTER);
        content.addView(balance, spaced(dp(4)));

        int purchased = prefs.getInt("skins", 1);
        int selected = Math.max(0, Math.min(SKIN_NAMES.length - 1, prefs.getInt("selected_skin", 0)));

        for (int i = 0; i < SKIN_NAMES.length; i++) {
            final int skin = i;
            boolean owned = (purchased & (1 << i)) != 0;
            String suffix = i == selected ? "  •  EQUIPPED" : owned ? "  •  OWNED" : "  •  " + SKIN_COSTS[i] + " COINS";
            Button skinButton = menuButton(SKIN_NAMES[i] + suffix, SKIN_COLORS[i]);
            skinButton.setOnClickListener(v -> {
                int currentCoins = prefs.getInt("coins", 0);
                int currentPurchased = prefs.getInt("skins", 1);
                boolean isOwned = (currentPurchased & (1 << skin)) != 0;
                if (!isOwned) {
                    if (currentCoins < SKIN_COSTS[skin]) {
                        Toast.makeText(this, "Not enough coins", Toast.LENGTH_SHORT).show();
                        return;
                    }
                    currentCoins -= SKIN_COSTS[skin];
                    currentPurchased |= (1 << skin);
                }
                prefs.edit()
                        .putInt("coins", currentCoins)
                        .putInt("skins", currentPurchased)
                        .putInt("selected_skin", skin)
                        .apply();
                dialog.dismiss();
                refreshCoinsLabel();
                showSkinsDialog();
            });
            content.addView(skinButton, menuButtonParams());
        }

        TextView coinStoreTitle = label("COIN STORE", 15, Color.rgb(245, 215, 126), true);
        coinStoreTitle.setGravity(Gravity.CENTER);
        content.addView(coinStoreTitle, spaced(dp(12)));

        LinearLayout packs = new LinearLayout(this);
        packs.setOrientation(LinearLayout.HORIZONTAL);
        int[] amounts = {1000, 3000, 8000};
        for (int amount : amounts) {
            Button pack = menuButton("+" + amount + "\nTEST BUY", Color.rgb(100, 70, 29));
            pack.setTextSize(13);
            pack.setOnClickListener(v -> {
                prefs.edit().putInt("coins", prefs.getInt("coins", 0) + amount).apply();
                balance.setText("COINS  " + prefs.getInt("coins", 0));
                refreshCoinsLabel();
            });
            LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(64), 1f);
            p.setMargins(dp(5), dp(5), dp(5), dp(5));
            packs.addView(pack, p);
        }
        content.addView(packs, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        ));

        TextView billingNote = label("Coin packs are still test purchases until Google Play Billing is connected.",
                12, Color.rgb(184, 216, 224), false);
        billingNote.setGravity(Gravity.CENTER);
        content.addView(billingNote, spaced(dp(6)));

        Button close = menuButton("CLOSE", Color.rgb(91, 56, 51));
        close.setOnClickListener(v -> dialog.dismiss());
        content.addView(close, menuButtonParams());

        balance.setText("COINS  " + prefs.getInt("coins", 0));
        dialog.setContentView(content);
        dialog.show();
        sizeDialog(dialog, 0.62f, 0.92f);
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
        content.setPadding(dp(24), dp(18), dp(24), dp(18));
        content.setBackground(roundRect(Color.rgb(12, 57, 72), dp(22)));
        return content;
    }

    private TextView dialogTitle(String text) {
        TextView title = label(text, 28, Color.WHITE, true);
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

    private void saveCaptainName() {
        if (captainNameInput == null) return;
        prefs.edit().putString("player_name", sanitizeName(captainNameInput.getText().toString())).apply();
    }

    private String sanitizeName(String raw) {
        String value = raw == null ? "" : raw.trim();
        if (value.isEmpty()) value = "Captain";
        if (value.length() > 16) value = value.substring(0, 16);
        return value;
    }

    private String generateRoomCode() {
        StringBuilder code = new StringBuilder(6);
        for (int i = 0; i < 6; i++) code.append(ROOM_CHARS.charAt(random.nextInt(ROOM_CHARS.length())));
        return code.toString();
    }

    private void refreshCoinsLabel() {
        if (coinsLabel != null) coinsLabel.setText("COINS  " + prefs.getInt("coins", 0));
    }

    private Button menuButton(String text, int color) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextColor(Color.WHITE);
        button.setTextSize(17);
        button.setAllCaps(false);
        button.setGravity(Gravity.CENTER);
        button.setPadding(dp(10), 0, dp(10), 0);
        button.setBackground(roundRect(color, dp(13)));
        return button;
    }

    private LinearLayout.LayoutParams menuButtonParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(58)
        );
        p.setMargins(0, dp(6), 0, dp(6));
        return p;
    }

    private LinearLayout.LayoutParams fieldParams() {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, dp(54)
        );
        p.setMargins(0, dp(14), 0, dp(8));
        return p;
    }

    private LinearLayout.LayoutParams spaced(int top) {
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT
        );
        p.topMargin = top;
        return p;
    }

    private TextView label(String text, float size, int color, boolean bold) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(color);
        view.setTextSize(size);
        if (bold) view.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        return view;
    }

    private GradientDrawable roundRect(int color, float radius) {
        GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(color);
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private GradientDrawable makeGradient(int start, int end, float radius) {
        GradientDrawable drawable = new GradientDrawable(
                GradientDrawable.Orientation.TL_BR,
                new int[]{start, end}
        );
        drawable.setCornerRadius(radius);
        return drawable;
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private void hideKeyboard() {
        View current = getCurrentFocus();
        if (current == null) return;
        InputMethodManager imm = (InputMethodManager) getSystemService(INPUT_METHOD_SERVICE);
        if (imm != null) imm.hideSoftInputFromWindow(current.getWindowToken(), 0);
        current.clearFocus();
    }

    @Override
    @Deprecated
    public void onBackPressed() {
        if (gameRunning) {
            showMainMenu();
            return;
        }
        super.onBackPressed();
    }
}
