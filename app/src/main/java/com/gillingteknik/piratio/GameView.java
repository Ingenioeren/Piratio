package com.gillingteknik.piratio;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Random;

public class GameView extends View {
    private static final float WORLD_W = 5200f;
    private static final float WORLD_H = 3600f;
    private static final int BOT_COUNT = 18;
    private static final int BASE_LOOT_COUNT = 120;
    private static final int MAX_LEVEL = 5;
    private static final int[] LEVEL_THRESHOLDS = {0, 0, 60, 160, 320, 560};
    private static final String[] SHIP_TITLES = {"", "Sloop", "Brig", "Frigate", "Galleon", "Man-o'-War"};
    private static final String[] BOT_NAMES = {
            "Black Bart", "Red Anne", "Barnacle Ben", "Mad Morgan", "Saltbeard", "Iron Jack",
            "Cutlass Kate", "Deadeye Dan", "Old Flint", "Sea Rat", "Captain Crow", "Bonny",
            "Dread Drake", "Gunpowder Gus", "Navy Hawk", "Blue Roger", "Scurvy Sam", "Kraken Joe",
            "Storm Mary", "Shark Finn", "One-Eye", "Gold Tooth", "Powder Pete", "Cannon Jane"
    };

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Random random = new Random();
    private final ArrayList<Ship> ships = new ArrayList<>();
    private final ArrayList<Loot> loot = new ArrayList<>();
    private final ArrayList<Cannonball> cannonballs = new ArrayList<>();

    private Ship player;
    private long lastFrameNanos;
    private float worldTime;
    private float deathMessageTimer;

    private int joystickPointer = -1;
    private int firePointer = -1;
    private boolean joystickActive;
    private boolean fireHeld;
    private float joystickCx;
    private float joystickCy;
    private float joystickX;
    private float joystickY;

    public GameView(Context context) {
        super(context);
        setBackgroundColor(Color.rgb(23, 126, 164));
        setFocusable(true);
        setKeepScreenOn(true);
        initialiseWorld();
    }

    private void initialiseWorld() {
        player = new Ship("YOU", true, null);
        player.x = WORLD_W * 0.5f;
        player.y = WORLD_H * 0.5f;
        player.angle = -0.25f;
        configureForLevel(player, 1, true);
        ships.add(player);

        for (int i = 0; i < BOT_COUNT; i++) {
            Role role;
            if (i % 7 == 0) role = Role.NAVY;
            else if (i % 3 == 0) role = Role.MERCHANT;
            else role = Role.PIRATE;

            Ship bot = new Ship(BOT_NAMES[i % BOT_NAMES.length], false, role);
            randomPosition(bot);
            bot.angle = random.nextFloat() * (float) (Math.PI * 2.0);
            int startingLevel = 1 + (random.nextInt(100) < 22 ? 1 : 0);
            if (role == Role.NAVY && random.nextBoolean()) startingLevel++;
            startingLevel = Math.min(startingLevel, 3);
            bot.xp = xpForLevel(startingLevel) + random.nextInt(35);
            configureForLevel(bot, startingLevel, true);
            bot.aiTimer = random.nextFloat();
            ships.add(bot);
        }

        for (int i = 0; i < BASE_LOOT_COUNT; i++) {
            spawnRandomLoot();
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        long now = System.nanoTime();
        float dt = lastFrameNanos == 0L ? 1f / 60f : (now - lastFrameNanos) / 1_000_000_000f;
        lastFrameNanos = now;
        dt = Math.min(dt, 0.033f);

        update(dt);
        drawGame(canvas);
        postInvalidateOnAnimation();
    }

    private void update(float dt) {
        worldTime += dt;
        if (deathMessageTimer > 0f) deathMessageTimer -= dt;

        for (Ship ship : ships) {
            if (!ship.alive) {
                ship.respawnTimer -= dt;
                if (ship.respawnTimer <= 0f) respawnShip(ship);
                continue;
            }

            ship.fireCooldown = Math.max(0f, ship.fireCooldown - dt);

            if (ship.isPlayer) updatePlayer(ship, dt);
            else updateBot(ship, dt);

            moveShip(ship, dt);
        }

        updateCannonballs(dt);
        collectLoot();

        if (loot.size() < BASE_LOOT_COUNT) {
            int replenish = Math.min(3, BASE_LOOT_COUNT - loot.size());
            for (int i = 0; i < replenish; i++) spawnRandomLoot();
        }
    }

    private void updatePlayer(Ship ship, float dt) {
        if (joystickActive) {
            float dx = joystickX - joystickCx;
            float dy = joystickY - joystickCy;
            float distance = (float) Math.sqrt(dx * dx + dy * dy);
            float desired = (float) Math.atan2(dy, dx);
            ship.angle = rotateTowards(ship.angle, desired, ship.turnRate * dt);
            float throttle = clamp(distance / joystickRadius(), 0f, 1f);
            float targetSpeed = ship.baseSpeed() * (0.35f + 0.65f * throttle);
            ship.speed += (targetSpeed - ship.speed) * Math.min(1f, dt * 5f);
        } else {
            float targetSpeed = ship.baseSpeed() * 0.26f;
            ship.speed += (targetSpeed - ship.speed) * Math.min(1f, dt * 3f);
        }

        if (fireHeld && ship.fireCooldown <= 0f) {
            fireBroadside(ship);
        }
    }

    private void updateBot(Ship ship, float dt) {
        ship.aiTimer -= dt;
        if (ship.aiTimer <= 0f) {
            ship.aiTimer = 0.45f + random.nextFloat() * 0.55f;
            Ship target = findBotTarget(ship);
            ship.target = target;

            if (ship.role == Role.MERCHANT) {
                Ship threat = nearestShip(ship, 500f);
                if (threat != null) {
                    ship.desiredAngle = (float) Math.atan2(ship.y - threat.y, ship.x - threat.x);
                    ship.target = threat;
                } else {
                    ship.desiredAngle += (random.nextFloat() - 0.5f) * 0.8f;
                }
            } else if (target != null) {
                ship.desiredAngle = (float) Math.atan2(target.y - ship.y, target.x - ship.x);
            } else {
                ship.desiredAngle += (random.nextFloat() - 0.5f) * 1.1f;
            }
        }

        ship.angle = rotateTowards(ship.angle, ship.desiredAngle, ship.turnRate * dt * 0.78f);
        float targetSpeed = ship.baseSpeed();
        if (ship.role == Role.MERCHANT) targetSpeed *= 1.06f;
        ship.speed += (targetSpeed - ship.speed) * Math.min(1f, dt * 2.2f);

        if (ship.target != null && ship.target.alive && ship.fireCooldown <= 0f) {
            float distance = distance(ship.x, ship.y, ship.target.x, ship.target.y);
            if (distance < 610f && random.nextFloat() < 0.075f) {
                fireBroadside(ship);
                ship.fireCooldown += 0.35f + random.nextFloat() * 0.45f;
            }
        }
    }

    private Ship findBotTarget(Ship ship) {
        if (ship.role == Role.NAVY && player.alive) return player;

        Ship best = null;
        float bestDistance = Float.MAX_VALUE;
        for (Ship other : ships) {
            if (other == ship || !other.alive) continue;
            if (ship.role == Role.PIRATE && other.role == Role.PIRATE && !other.isPlayer && random.nextFloat() < 0.65f) {
                continue;
            }
            float d = distanceSq(ship.x, ship.y, other.x, other.y);
            if (d < bestDistance && d < 900f * 900f) {
                bestDistance = d;
                best = other;
            }
        }
        return best;
    }

    private Ship nearestShip(Ship ship, float range) {
        Ship best = null;
        float bestDistance = range * range;
        for (Ship other : ships) {
            if (other == ship || !other.alive) continue;
            float d = distanceSq(ship.x, ship.y, other.x, other.y);
            if (d < bestDistance) {
                bestDistance = d;
                best = other;
            }
        }
        return best;
    }

    private void moveShip(Ship ship, float dt) {
        ship.x += (float) Math.cos(ship.angle) * ship.speed * dt;
        ship.y += (float) Math.sin(ship.angle) * ship.speed * dt;

        float margin = 48f + ship.radius();
        boolean bounced = false;
        if (ship.x < margin) { ship.x = margin; ship.angle = (float) Math.PI - ship.angle; bounced = true; }
        if (ship.x > WORLD_W - margin) { ship.x = WORLD_W - margin; ship.angle = (float) Math.PI - ship.angle; bounced = true; }
        if (ship.y < margin) { ship.y = margin; ship.angle = -ship.angle; bounced = true; }
        if (ship.y > WORLD_H - margin) { ship.y = WORLD_H - margin; ship.angle = -ship.angle; bounced = true; }
        if (bounced) ship.desiredAngle = normalizeAngle(ship.angle);
    }

    private void fireBroadside(Ship ship) {
        if (!ship.alive || ship.fireCooldown > 0f) return;

        int gunsPerSide = 1 + (ship.level - 1) / 2;
        float muzzleSpeed = 480f + ship.level * 18f;
        float spread = 0.12f;

        for (int side = -1; side <= 1; side += 2) {
            float sideAngle = ship.angle + side * (float) Math.PI / 2f;
            for (int gun = 0; gun < gunsPerSide; gun++) {
                float offsetAlong = (gun - (gunsPerSide - 1) * 0.5f) * ship.radius() * 0.42f;
                float forwardX = (float) Math.cos(ship.angle) * offsetAlong;
                float forwardY = (float) Math.sin(ship.angle) * offsetAlong;
                float sideX = (float) Math.cos(sideAngle) * ship.radius() * 0.78f;
                float sideY = (float) Math.sin(sideAngle) * ship.radius() * 0.78f;
                float shotAngle = sideAngle + (gun - (gunsPerSide - 1) * 0.5f) * spread;

                Cannonball ball = new Cannonball();
                ball.x = ship.x + forwardX + sideX;
                ball.y = ship.y + forwardY + sideY;
                ball.vx = (float) Math.cos(shotAngle) * muzzleSpeed + (float) Math.cos(ship.angle) * ship.speed * 0.28f;
                ball.vy = (float) Math.sin(shotAngle) * muzzleSpeed + (float) Math.sin(ship.angle) * ship.speed * 0.28f;
                ball.owner = ship;
                ball.life = 1.28f;
                cannonballs.add(ball);
            }
        }

        ship.fireCooldown = Math.max(0.50f, 0.92f - ship.level * 0.055f);
    }

    private void updateCannonballs(float dt) {
        for (int i = cannonballs.size() - 1; i >= 0; i--) {
            Cannonball ball = cannonballs.get(i);
            ball.x += ball.vx * dt;
            ball.y += ball.vy * dt;
            ball.life -= dt;

            boolean remove = ball.life <= 0f || ball.x < 0 || ball.y < 0 || ball.x > WORLD_W || ball.y > WORLD_H;
            if (!remove) {
                for (Ship ship : ships) {
                    if (ship == ball.owner || !ship.alive) continue;
                    float hitRadius = ship.radius() * 0.78f;
                    if (distanceSq(ball.x, ball.y, ship.x, ship.y) <= hitRadius * hitRadius) {
                        ship.hp -= 15f + ball.owner.level * 2.2f;
                        remove = true;
                        if (ship.hp <= 0f) sinkShip(ship, ball.owner);
                        break;
                    }
                }
            }

            if (remove) cannonballs.remove(i);
        }
    }

    private void sinkShip(Ship victim, Ship killer) {
        if (!victim.alive) return;
        victim.alive = false;
        victim.speed = 0f;
        victim.respawnTimer = victim.isPlayer ? 2.25f : 2.5f + random.nextFloat() * 1.8f;

        int drops = 5 + victim.level * 3;
        for (int i = 0; i < drops; i++) {
            Loot piece = new Loot();
            float angle = random.nextFloat() * (float) Math.PI * 2f;
            float radius = 18f + random.nextFloat() * 85f;
            piece.x = clamp(victim.x + (float) Math.cos(angle) * radius, 30f, WORLD_W - 30f);
            piece.y = clamp(victim.y + (float) Math.sin(angle) * radius, 30f, WORLD_H - 30f);
            piece.value = 7 + victim.level * 2 + random.nextInt(9);
            piece.phase = random.nextFloat() * 6f;
            loot.add(piece);
        }

        if (killer != null && killer.alive) {
            killer.xp += 16 + victim.level * 18;
            killer.hp = Math.min(killer.maxHp, killer.hp + 8f + victim.level * 2f);
            checkLevel(killer);
        }

        if (victim.isPlayer) deathMessageTimer = 2.25f;
    }

    private void collectLoot() {
        for (int i = loot.size() - 1; i >= 0; i--) {
            Loot piece = loot.get(i);
            Ship collector = null;
            float best = Float.MAX_VALUE;

            for (Ship ship : ships) {
                if (!ship.alive) continue;
                float pickup = ship.radius() + 20f;
                float d = distanceSq(piece.x, piece.y, ship.x, ship.y);
                if (d < pickup * pickup && d < best) {
                    best = d;
                    collector = ship;
                }
            }

            if (collector != null) {
                collector.xp += piece.value;
                collector.hp = Math.min(collector.maxHp, collector.hp + 1.4f);
                checkLevel(collector);
                loot.remove(i);
            }
        }
    }

    private void checkLevel(Ship ship) {
        int newLevel = ship.level;
        while (newLevel < MAX_LEVEL && ship.xp >= LEVEL_THRESHOLDS[newLevel + 1]) newLevel++;
        if (newLevel != ship.level) configureForLevel(ship, newLevel, false);
    }

    private void configureForLevel(Ship ship, int level, boolean fullHeal) {
        float previousMax = ship.maxHp;
        ship.level = clampInt(level, 1, MAX_LEVEL);
        ship.maxHp = 82f + ship.level * 38f;
        ship.turnRate = 2.55f - ship.level * 0.18f;
        if (fullHeal || previousMax <= 0f) ship.hp = ship.maxHp;
        else ship.hp = Math.min(ship.maxHp, ship.hp + (ship.maxHp - previousMax) + 24f);
    }

    private void respawnShip(Ship ship) {
        ship.alive = true;
        randomPosition(ship);
        ship.angle = random.nextFloat() * (float) Math.PI * 2f;
        ship.desiredAngle = ship.angle;
        ship.speed = 0f;
        ship.fireCooldown = 1f;
        ship.target = null;

        if (ship.isPlayer) {
            ship.xp = 0;
            configureForLevel(ship, 1, true);
        } else {
            int level = ship.role == Role.NAVY && random.nextFloat() < 0.4f ? 2 : 1;
            ship.xp = xpForLevel(level) + random.nextInt(28);
            configureForLevel(ship, level, true);
        }
    }

    private int xpForLevel(int level) {
        return LEVEL_THRESHOLDS[clampInt(level, 1, MAX_LEVEL)];
    }

    private void randomPosition(Ship ship) {
        ship.x = 180f + random.nextFloat() * (WORLD_W - 360f);
        ship.y = 180f + random.nextFloat() * (WORLD_H - 360f);
    }

    private void spawnRandomLoot() {
        Loot piece = new Loot();
        piece.x = 55f + random.nextFloat() * (WORLD_W - 110f);
        piece.y = 55f + random.nextFloat() * (WORLD_H - 110f);
        piece.value = 6 + random.nextInt(10);
        piece.phase = random.nextFloat() * 6f;
        loot.add(piece);
    }

    private void drawGame(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(25, 132, 171));
        canvas.drawRect(0, 0, w, h, paint);

        float zoom = clamp(1.04f - (player.level - 1) * 0.055f, 0.78f, 1.04f);
        float cameraX = player.x;
        float cameraY = player.y;

        canvas.save();
        canvas.translate(w * 0.5f, h * 0.5f);
        canvas.scale(zoom, zoom);
        canvas.translate(-cameraX, -cameraY);

        drawWaterDetails(canvas, cameraX, cameraY, w / zoom, h / zoom);
        drawWorldBorder(canvas);
        drawLoot(canvas);
        drawCannonballs(canvas);

        ArrayList<Ship> renderShips = new ArrayList<>(ships);
        Collections.sort(renderShips, Comparator.comparingDouble(s -> s.y));
        for (Ship ship : renderShips) {
            if (ship.alive) drawShip(canvas, ship);
        }

        canvas.restore();
        drawHud(canvas);
    }

    private void drawWaterDetails(Canvas canvas, float cameraX, float cameraY, float visibleW, float visibleH) {
        paint.setStrokeWidth(2f);
        paint.setStyle(Paint.Style.STROKE);
        paint.setColor(Color.argb(65, 214, 244, 255));

        float left = Math.max(0f, cameraX - visibleW * 0.62f);
        float right = Math.min(WORLD_W, cameraX + visibleW * 0.62f);
        float top = Math.max(0f, cameraY - visibleH * 0.62f);
        float bottom = Math.min(WORLD_H, cameraY + visibleH * 0.62f);

        int gx0 = (int) (left / 180f) - 1;
        int gx1 = (int) (right / 180f) + 1;
        int gy0 = (int) (top / 150f) - 1;
        int gy1 = (int) (bottom / 150f) + 1;

        for (int gx = gx0; gx <= gx1; gx++) {
            for (int gy = gy0; gy <= gy1; gy++) {
                float x = gx * 180f + 35f + ((gy & 1) == 0 ? 0f : 55f);
                float y = gy * 150f + 30f;
                float wobble = (float) Math.sin(worldTime * 1.4f + gx * 0.8f + gy) * 7f;
                RectF arc = new RectF(x - 22f + wobble, y - 8f, x + 22f + wobble, y + 14f);
                canvas.drawArc(arc, 205f, 130f, false, paint);
            }
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawWorldBorder(Canvas canvas) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(14f);
        paint.setColor(Color.argb(210, 215, 244, 245));
        canvas.drawRect(12f, 12f, WORLD_W - 12f, WORLD_H - 12f, paint);
        paint.setStrokeWidth(4f);
        paint.setColor(Color.argb(180, 7, 71, 94));
        canvas.drawRect(22f, 22f, WORLD_W - 22f, WORLD_H - 22f, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawLoot(Canvas canvas) {
        for (Loot piece : loot) {
            float bob = (float) Math.sin(worldTime * 2.2f + piece.phase) * 3f;
            float x = piece.x;
            float y = piece.y + bob;

            paint.setColor(Color.argb(70, 0, 0, 0));
            canvas.drawOval(new RectF(x - 14f, y + 9f, x + 14f, y + 17f), paint);

            paint.setColor(Color.rgb(120, 70, 27));
            canvas.drawRoundRect(new RectF(x - 13f, y - 9f, x + 13f, y + 10f), 4f, 4f, paint);
            paint.setColor(Color.rgb(243, 190, 44));
            canvas.drawRect(x - 13f, y - 3f, x + 13f, y + 2f, paint);
            canvas.drawRect(x - 2f, y - 9f, x + 3f, y + 10f, paint);
        }
    }

    private void drawCannonballs(Canvas canvas) {
        for (Cannonball ball : cannonballs) {
            paint.setColor(Color.argb(80, 0, 0, 0));
            canvas.drawCircle(ball.x + 5f, ball.y + 7f, 7f, paint);
            paint.setColor(Color.rgb(30, 34, 37));
            canvas.drawCircle(ball.x, ball.y, 7f, paint);
            paint.setColor(Color.argb(180, 236, 244, 244));
            canvas.drawCircle(ball.x - 2f, ball.y - 2f, 2f, paint);
        }
    }

    private void drawShip(Canvas canvas, Ship ship) {
        float r = ship.radius();
        canvas.save();
        canvas.translate(ship.x, ship.y);
        canvas.rotate((float) Math.toDegrees(ship.angle));

        paint.setColor(Color.argb(70, 0, 20, 28));
        canvas.drawOval(new RectF(-r * 1.05f, -r * 0.66f + 8f, r * 1.20f, r * 0.66f + 14f), paint);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(5f);
        paint.setColor(Color.argb(120, 219, 246, 250));
        canvas.drawLine(-r * 1.50f, -r * 0.24f, -r * 0.95f, -r * 0.13f, paint);
        canvas.drawLine(-r * 1.58f, r * 0.20f, -r * 0.98f, r * 0.11f, paint);
        paint.setStyle(Paint.Style.FILL);

        int hullColor;
        if (ship.isPlayer) hullColor = Color.rgb(110, 61, 30);
        else if (ship.role == Role.NAVY) hullColor = Color.rgb(48, 63, 92);
        else if (ship.role == Role.MERCHANT) hullColor = Color.rgb(116, 81, 42);
        else hullColor = Color.rgb(82, 46, 31);

        path.reset();
        path.moveTo(r * 1.18f, 0f);
        path.lineTo(r * 0.48f, r * 0.67f);
        path.lineTo(-r * 0.92f, r * 0.56f);
        path.lineTo(-r * 1.10f, 0f);
        path.lineTo(-r * 0.92f, -r * 0.56f);
        path.lineTo(r * 0.48f, -r * 0.67f);
        path.close();
        paint.setColor(hullColor);
        canvas.drawPath(path, paint);

        path.reset();
        path.moveTo(r * 0.78f, 0f);
        path.lineTo(r * 0.34f, r * 0.43f);
        path.lineTo(-r * 0.73f, r * 0.37f);
        path.lineTo(-r * 0.82f, 0f);
        path.lineTo(-r * 0.73f, -r * 0.37f);
        path.lineTo(r * 0.34f, -r * 0.43f);
        path.close();
        paint.setColor(Color.rgb(193, 139, 73));
        canvas.drawPath(path, paint);

        int cannons = Math.min(4, 1 + ship.level);
        paint.setColor(Color.rgb(35, 35, 36));
        for (int i = 0; i < cannons; i++) {
            float t = cannons == 1 ? 0f : (i / (float) (cannons - 1) - 0.5f);
            float cx = t * r * 1.1f - r * 0.10f;
            canvas.drawCircle(cx, -r * 0.56f, 4.2f + ship.level * 0.35f, paint);
            canvas.drawCircle(cx, r * 0.56f, 4.2f + ship.level * 0.35f, paint);
        }

        int mastCount = ship.level <= 2 ? 1 : ship.level <= 4 ? 2 : 3;
        for (int mast = 0; mast < mastCount; mast++) {
            float mx = mastCount == 1 ? 0f : -r * 0.38f + mast * r * 0.38f;
            paint.setColor(Color.rgb(76, 48, 25));
            canvas.drawRect(mx - 3f, -r * 0.58f, mx + 3f, r * 0.58f, paint);

            float sailHalf = r * (0.34f - mast * 0.025f);
            path.reset();
            path.moveTo(mx - r * 0.09f, -sailHalf);
            path.lineTo(mx + r * 0.22f, -sailHalf * 0.72f);
            path.lineTo(mx + r * 0.22f, sailHalf * 0.72f);
            path.lineTo(mx - r * 0.09f, sailHalf);
            path.close();
            if (ship.role == Role.NAVY) paint.setColor(Color.rgb(226, 235, 244));
            else if (ship.isPlayer) paint.setColor(Color.rgb(245, 229, 191));
            else paint.setColor(Color.rgb(218, 205, 172));
            canvas.drawPath(path, paint);
        }

        paint.setColor(ship.isPlayer ? Color.rgb(224, 44, 47) : Color.rgb(32, 34, 39));
        float flagX = -r * 0.55f;
        canvas.drawRect(flagX - 2f, -r * 0.22f, flagX + 2f, r * 0.12f, paint);
        path.reset();
        path.moveTo(flagX + 2f, -r * 0.22f);
        path.lineTo(flagX + r * 0.38f, -r * 0.11f);
        path.lineTo(flagX + 2f, 0f);
        path.close();
        canvas.drawPath(path, paint);

        if (ship.isPlayer) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(4f);
            paint.setColor(Color.rgb(248, 213, 73));
            canvas.drawPath(hullOutline(r), paint);
            paint.setStyle(Paint.Style.FILL);
        }

        canvas.restore();

        float barW = r * 1.8f;
        float barY = ship.y - r - 26f;
        paint.setColor(Color.argb(150, 8, 20, 27));
        canvas.drawRoundRect(new RectF(ship.x - barW / 2, barY, ship.x + barW / 2, barY + 8f), 4f, 4f, paint);
        float hpRatio = clamp(ship.hp / ship.maxHp, 0f, 1f);
        paint.setColor(hpRatio > 0.45f ? Color.rgb(71, 205, 95) : Color.rgb(233, 73, 62));
        canvas.drawRoundRect(new RectF(ship.x - barW / 2, barY, ship.x - barW / 2 + barW * hpRatio, barY + 8f), 4f, 4f, paint);

        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextSize(18f + ship.level * 1.2f);
        paint.setColor(Color.WHITE);
        canvas.drawText(ship.name, ship.x, barY - 5f, paint);
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
    }

    private Path hullOutline(float r) {
        Path outline = new Path();
        outline.moveTo(r * 1.18f, 0f);
        outline.lineTo(r * 0.48f, r * 0.67f);
        outline.lineTo(-r * 0.92f, r * 0.56f);
        outline.lineTo(-r * 1.10f, 0f);
        outline.lineTo(-r * 0.92f, -r * 0.56f);
        outline.lineTo(r * 0.48f, -r * 0.67f);
        outline.close();
        return outline;
    }

    private void drawHud(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float base = Math.min(w, h);
        float pad = base * 0.026f;
        float titleSize = base * 0.060f;
        float normal = base * 0.030f;
        float small = base * 0.022f;

        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextSize(titleSize);
        paint.setColor(Color.WHITE);
        paint.setShadowLayer(5f, 0f, 3f, Color.argb(120, 0, 0, 0));
        canvas.drawText("PIRAT.IO", pad, pad + titleSize * 0.78f, paint);
        paint.clearShadowLayer();

        paint.setTextSize(normal);
        paint.setColor(Color.rgb(247, 223, 146));
        canvas.drawText("Lv. " + player.level + "  " + SHIP_TITLES[player.level], pad, pad + titleSize + normal * 0.72f, paint);

        float statTop = pad + titleSize + normal * 1.15f;
        float statW = base * 0.42f;
        float statH = base * 0.025f;
        drawBar(canvas, pad, statTop, statW, statH, player.hp / player.maxHp,
                Color.rgb(59, 205, 91), "HULL " + Math.max(0, Math.round(player.hp)) + "/" + Math.round(player.maxHp), small);

        if (player.level < MAX_LEVEL) {
            int from = LEVEL_THRESHOLDS[player.level];
            int to = LEVEL_THRESHOLDS[player.level + 1];
            float progress = clamp((player.xp - from) / (float) (to - from), 0f, 1f);
            drawBar(canvas, pad, statTop + statH + base * 0.016f, statW, statH, progress,
                    Color.rgb(245, 185, 48), "INFAMY " + player.xp + "/" + to, small);
        } else {
            drawBar(canvas, pad, statTop + statH + base * 0.016f, statW, statH, 1f,
                    Color.rgb(245, 185, 48), "MAX SHIP  •  INFAMY " + player.xp, small);
        }

        drawLeaderboard(canvas, w, h, base, pad, small);
        drawControls(canvas, w, h, base);

        if (deathMessageTimer > 0f || !player.alive) {
            paint.setColor(Color.argb(145, 5, 17, 25));
            canvas.drawRect(0, h * 0.35f, w, h * 0.65f, paint);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            paint.setTextSize(base * 0.09f);
            paint.setColor(Color.WHITE);
            canvas.drawText("SUNK!", w * 0.5f, h * 0.48f, paint);
            paint.setTextSize(base * 0.034f);
            paint.setColor(Color.rgb(244, 215, 130));
            canvas.drawText("Respawning as a Sloop...", w * 0.5f, h * 0.56f, paint);
        }

        paint.setTypeface(android.graphics.Typeface.DEFAULT);
    }

    private void drawBar(Canvas canvas, float x, float y, float width, float height, float ratio, int fillColor, String label, float textSize) {
        paint.setColor(Color.argb(165, 8, 25, 34));
        canvas.drawRoundRect(new RectF(x, y, x + width, y + height), height * 0.5f, height * 0.5f, paint);
        paint.setColor(fillColor);
        float inner = Math.max(height, width * clamp(ratio, 0f, 1f));
        canvas.drawRoundRect(new RectF(x, y, x + Math.min(width, inner), y + height), height * 0.5f, height * 0.5f, paint);
        paint.setTextSize(textSize);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setColor(Color.WHITE);
        canvas.drawText(label, x + 7f, y + height - 3f, paint);
    }

    private void drawLeaderboard(Canvas canvas, float w, float h, float base, float pad, float small) {
        ArrayList<Ship> ranking = new ArrayList<>(ships);
        Collections.sort(ranking, (a, b) -> Integer.compare(b.xp, a.xp));

        float panelW = base * 0.42f;
        float rowH = base * 0.035f;
        float panelH = rowH * 6.4f;
        float left = w - pad - panelW;
        float top = pad;

        paint.setColor(Color.argb(150, 6, 29, 39));
        canvas.drawRoundRect(new RectF(left, top, w - pad, top + panelH), 18f, 18f, paint);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextSize(small * 1.05f);
        paint.setColor(Color.rgb(248, 220, 130));
        canvas.drawText("TOP PIRATES", left + 14f, top + rowH * 0.82f, paint);

        int count = Math.min(5, ranking.size());
        for (int i = 0; i < count; i++) {
            Ship ship = ranking.get(i);
            float y = top + rowH * (i + 1.65f);
            paint.setTextSize(small);
            paint.setColor(ship.isPlayer ? Color.rgb(255, 224, 88) : Color.WHITE);
            String line = String.format(Locale.US, "%d. %-12s %4d", i + 1, trimName(ship.name, 12), ship.xp);
            canvas.drawText(line, left + 14f, y, paint);
        }
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
    }

    private String trimName(String name, int max) {
        return name.length() <= max ? name : name.substring(0, max - 1) + "…";
    }

    private void drawControls(Canvas canvas, float w, float h, float base) {
        float jr = joystickRadius();
        float jcX = joystickActive ? joystickCx : jr * 1.45f;
        float jcY = joystickActive ? joystickCy : h - jr * 1.38f;
        float knobX = joystickActive ? joystickX : jcX;
        float knobY = joystickActive ? joystickY : jcY;

        float dx = knobX - jcX;
        float dy = knobY - jcY;
        float d = (float) Math.sqrt(dx * dx + dy * dy);
        if (d > jr) {
            knobX = jcX + dx / d * jr;
            knobY = jcY + dy / d * jr;
        }

        paint.setColor(Color.argb(70, 255, 255, 255));
        canvas.drawCircle(jcX, jcY, jr, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(base * 0.006f);
        paint.setColor(Color.argb(150, 255, 255, 255));
        canvas.drawCircle(jcX, jcY, jr, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.argb(170, 11, 52, 67));
        canvas.drawCircle(knobX, knobY, jr * 0.42f, paint);

        float fireR = jr * 0.88f;
        float fireX = w - fireR * 1.52f;
        float fireY = h - fireR * 1.48f;
        paint.setColor(Color.argb(fireHeld ? 220 : 165, 125, 42, 31));
        canvas.drawCircle(fireX, fireY, fireR, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(base * 0.007f);
        paint.setColor(Color.argb(210, 255, 228, 179));
        canvas.drawCircle(fireX, fireY, fireR, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextSize(base * 0.030f);
        paint.setColor(Color.WHITE);
        canvas.drawText("FIRE", fireX, fireY + base * 0.010f, paint);
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int index = event.getActionIndex();
        int pointerId = event.getPointerId(index);
        float x = event.getX(index);
        float y = event.getY(index);

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            if (x < getWidth() * 0.60f && joystickPointer == -1) {
                joystickPointer = pointerId;
                joystickActive = true;
                joystickCx = x;
                joystickCy = y;
                joystickX = x;
                joystickY = y;
            } else if (firePointer == -1) {
                firePointer = pointerId;
                fireHeld = true;
                if (player.alive && player.fireCooldown <= 0f) fireBroadside(player);
            }
            return true;
        }

        if (action == MotionEvent.ACTION_MOVE) {
            for (int i = 0; i < event.getPointerCount(); i++) {
                int id = event.getPointerId(i);
                if (id == joystickPointer) {
                    joystickX = event.getX(i);
                    joystickY = event.getY(i);
                }
            }
            return true;
        }

        if (action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_CANCEL) {
            if (pointerId == joystickPointer || action == MotionEvent.ACTION_CANCEL) {
                joystickPointer = -1;
                joystickActive = false;
            }
            if (pointerId == firePointer || action == MotionEvent.ACTION_CANCEL) {
                firePointer = -1;
                fireHeld = false;
            }
            return true;
        }

        return true;
    }

    private float joystickRadius() {
        return Math.max(62f, Math.min(getWidth(), getHeight()) * 0.105f);
    }

    private static float rotateTowards(float current, float target, float maxDelta) {
        float delta = normalizeAngle(target - current);
        delta = clamp(delta, -maxDelta, maxDelta);
        return normalizeAngle(current + delta);
    }

    private static float normalizeAngle(float angle) {
        while (angle > Math.PI) angle -= (float) (Math.PI * 2.0);
        while (angle < -Math.PI) angle += (float) (Math.PI * 2.0);
        return angle;
    }

    private static float distance(float x1, float y1, float x2, float y2) {
        return (float) Math.sqrt(distanceSq(x1, y1, x2, y2));
    }

    private static float distanceSq(float x1, float y1, float x2, float y2) {
        float dx = x2 - x1;
        float dy = y2 - y1;
        return dx * dx + dy * dy;
    }

    private static float clamp(float value, float min, float max) {
        return Math.max(min, Math.min(max, value));
    }

    private static int clampInt(int value, int min, int max) {
        return Math.max(min, Math.min(max, value));
    }

    private enum Role { MERCHANT, PIRATE, NAVY }

    private static class Ship {
        final String name;
        final boolean isPlayer;
        final Role role;
        float x;
        float y;
        float angle;
        float desiredAngle;
        float speed;
        float hp;
        float maxHp;
        float turnRate;
        float fireCooldown;
        float aiTimer;
        float respawnTimer;
        int xp;
        int level = 1;
        boolean alive = true;
        Ship target;

        Ship(String name, boolean isPlayer, Role role) {
            this.name = name;
            this.isPlayer = isPlayer;
            this.role = role;
        }

        float radius() {
            return 30f + level * 8.5f;
        }

        float baseSpeed() {
            float speed = 184f - level * 8f;
            if (role == Role.MERCHANT) speed += 12f;
            if (role == Role.NAVY) speed += 4f;
            return speed;
        }
    }

    private static class Loot {
        float x;
        float y;
        int value;
        float phase;
    }

    private static class Cannonball {
        float x;
        float y;
        float vx;
        float vy;
        float life;
        Ship owner;
    }
}
