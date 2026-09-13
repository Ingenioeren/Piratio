package com.gillingteknik.piratio;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.view.MotionEvent;
import android.view.View;

import java.util.ArrayList;
import java.util.Collections;
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
    private static final String[] SKIN_NAMES = {"Classic", "Crimson", "Ghost", "Royal"};
    private static final int[] SKIN_COSTS = {0, 250, 600, 900};

    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path path = new Path();
    private final Random random = new Random();
    private final ArrayList<Ship> ships = new ArrayList<>();
    private final ArrayList<Loot> loot = new ArrayList<>();
    private final ArrayList<Cannonball> cannonballs = new ArrayList<>();
    private final ArrayList<Rock> rocks = new ArrayList<>();
    private final ArrayList<WindZone> windZones = new ArrayList<>();
    private final SharedPreferences prefs;

    private Ship player;
    private long lastFrameNanos;
    private float worldTime;
    private float deathMessageTimer;
    private float adOverlayTimer;
    private float storeMessageTimer;
    private String storeMessage = "";

    private int coins;
    private int purchasedSkins;
    private int selectedSkin;
    private int deathCount;
    private int nextAdDeath = 2;
    private boolean shopOpen;

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
        prefs = context.getSharedPreferences("piratio_profile", Context.MODE_PRIVATE);
        coins = prefs.getInt("coins", 0);
        purchasedSkins = prefs.getInt("skins", 1);
        selectedSkin = clampInt(prefs.getInt("selected_skin", 0), 0, SKIN_NAMES.length - 1);
        if ((purchasedSkins & (1 << selectedSkin)) == 0) selectedSkin = 0;

        setBackgroundColor(Color.rgb(23, 126, 164));
        setFocusable(true);
        setKeepScreenOn(true);
        initialiseWorld();
    }

    private void initialiseWorld() {
        generateEnvironment();

        player = new Ship("YOU", true, null);
        safeRandomPosition(player);
        player.angle = -0.25f;
        configureForLevel(player, 1, true);
        ships.add(player);

        for (int i = 0; i < BOT_COUNT; i++) {
            Role role;
            if (i % 7 == 0) role = Role.NAVY;
            else if (i % 3 == 0) role = Role.MERCHANT;
            else role = Role.PIRATE;

            Ship bot = new Ship(BOT_NAMES[i % BOT_NAMES.length], false, role);
            safeRandomPosition(bot);
            bot.angle = random.nextFloat() * (float) (Math.PI * 2.0);
            bot.desiredAngle = bot.angle;
            int startingLevel = 1 + (random.nextInt(100) < 22 ? 1 : 0);
            if (role == Role.NAVY && random.nextBoolean()) startingLevel++;
            startingLevel = Math.min(startingLevel, 3);
            bot.xp = xpForLevel(startingLevel) + random.nextInt(35);
            configureForLevel(bot, startingLevel, true);
            bot.aiTimer = random.nextFloat();
            bot.preferredSide = random.nextBoolean() ? 1f : -1f;
            ships.add(bot);
        }

        for (int i = 0; i < BASE_LOOT_COUNT; i++) spawnRandomLoot();
    }

    private void generateEnvironment() {
        rocks.clear();
        windZones.clear();

        float[][] clusters = {
                {950f, 800f}, {2150f, 2450f}, {3650f, 900f}, {4450f, 2700f}
        };
        for (float[] c : clusters) {
            int count = 4 + random.nextInt(3);
            for (int i = 0; i < count; i++) {
                float a = random.nextFloat() * (float) Math.PI * 2f;
                float d = 80f + random.nextFloat() * 280f;
                Rock rock = new Rock();
                rock.x = c[0] + (float) Math.cos(a) * d;
                rock.y = c[1] + (float) Math.sin(a) * d;
                rock.radius = 48f + random.nextFloat() * 65f;
                rocks.add(rock);
            }
        }

        addWind(1200f, 2750f, 360f, -0.45f, 1.42f);
        addWind(2750f, 850f, 420f, 0.28f, 1.38f);
        addWind(3920f, 2050f, 360f, 1.02f, 1.45f);
        addWind(1900f, 1550f, 300f, 2.55f, 1.34f);
        addWind(4700f, 700f, 280f, 2.85f, 1.40f);
    }

    private void addWind(float x, float y, float radius, float angle, float strength) {
        WindZone zone = new WindZone();
        zone.x = x;
        zone.y = y;
        zone.radius = radius;
        zone.angle = angle;
        zone.strength = strength;
        windZones.add(zone);
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
        if (storeMessageTimer > 0f) storeMessageTimer -= dt;

        if (adOverlayTimer > 0f) {
            adOverlayTimer -= dt;
            return;
        }
        if (shopOpen) return;

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

        if (fireHeld && ship.fireCooldown <= 0f) fireBroadside(ship);
    }

    private void updateBot(Ship ship, float dt) {
        ship.aiTimer -= dt;
        if (ship.aiTimer <= 0f) {
            ship.aiTimer = 0.55f + random.nextFloat() * 0.75f;

            if (ship.role == Role.MERCHANT) {
                Ship threat = nearestHostile(ship, 520f);
                ship.target = threat;
                if (threat != null) {
                    ship.desiredAngle = (float) Math.atan2(ship.y - threat.y, ship.x - threat.x);
                } else {
                    ship.desiredAngle = normalizeAngle(ship.desiredAngle + (random.nextFloat() - 0.5f) * 0.85f);
                }
            } else {
                ship.target = findBotTarget(ship);
                if (ship.target != null) {
                    float bearing = (float) Math.atan2(ship.target.y - ship.y, ship.target.x - ship.x);
                    float d = distance(ship.x, ship.y, ship.target.x, ship.target.y);
                    if (d < 520f) {
                        ship.desiredAngle = normalizeAngle(bearing + ship.preferredSide * (float) Math.PI / 2f);
                    } else {
                        ship.desiredAngle = normalizeAngle(bearing + ship.preferredSide * 0.32f);
                    }
                } else {
                    ship.desiredAngle = normalizeAngle(ship.desiredAngle + (random.nextFloat() - 0.5f) * 1.0f);
                }
            }

            avoidNearbyRock(ship);
        }

        ship.angle = rotateTowards(ship.angle, ship.desiredAngle, ship.turnRate * dt * 0.82f);
        float targetSpeed = ship.baseSpeed();
        if (ship.role == Role.MERCHANT) targetSpeed *= 1.08f;
        ship.speed += (targetSpeed - ship.speed) * Math.min(1f, dt * 2.2f);

        if (ship.target != null && ship.target.alive && ship.fireCooldown <= 0f) {
            float distance = distance(ship.x, ship.y, ship.target.x, ship.target.y);
            float bearing = (float) Math.atan2(ship.target.y - ship.y, ship.target.x - ship.x);
            float delta = Math.abs(normalizeAngle(bearing - ship.angle));
            float broadsideError = Math.abs(delta - (float) Math.PI / 2f);
            if (distance < 590f && broadsideError < 0.48f && random.nextFloat() < 0.12f) {
                fireBroadside(ship);
                ship.fireCooldown += 0.25f + random.nextFloat() * 0.45f;
                if (random.nextFloat() < 0.18f) ship.preferredSide *= -1f;
            }
        }
    }

    private void avoidNearbyRock(Ship ship) {
        Rock nearest = null;
        float best = 360f * 360f;
        for (Rock rock : rocks) {
            float d = distanceSq(ship.x, ship.y, rock.x, rock.y);
            float range = rock.radius + 190f;
            if (d < range * range && d < best) {
                best = d;
                nearest = rock;
            }
        }
        if (nearest != null) {
            float away = (float) Math.atan2(ship.y - nearest.y, ship.x - nearest.x);
            ship.desiredAngle = normalizeAngle(away + ship.preferredSide * 0.35f);
        }
    }

    private Ship findBotTarget(Ship ship) {
        Ship best = null;
        float bestScore = Float.MAX_VALUE;

        for (Ship other : ships) {
            if (other == ship || !other.alive) continue;

            float dSq = distanceSq(ship.x, ship.y, other.x, other.y);
            if (dSq > 900f * 900f) continue;

            float score = dSq;
            if (other.isPlayer) score *= 1.35f;

            if (ship.role == Role.PIRATE) {
                if (other.role == Role.MERCHANT) score *= 0.58f;
                if (other.role == Role.PIRATE) score *= 1.18f;
                if (other.role == Role.NAVY) score *= 1.08f;
            } else if (ship.role == Role.NAVY) {
                if (other.role == Role.PIRATE) score *= 0.58f;
                if (other.role == Role.MERCHANT) score *= 1.65f;
                if (other.isPlayer) score *= 0.86f;
            }

            if (other.level > ship.level + 1) score *= 1.30f;
            if (other.level < ship.level - 1) score *= 0.90f;
            score *= 0.86f + random.nextFloat() * 0.30f;

            if (score < bestScore) {
                bestScore = score;
                best = other;
            }
        }
        return best;
    }

    private Ship nearestHostile(Ship ship, float range) {
        Ship best = null;
        float bestDistance = range * range;
        for (Ship other : ships) {
            if (other == ship || !other.alive) continue;
            if (ship.role == Role.MERCHANT && other.role == Role.MERCHANT) continue;
            float d = distanceSq(ship.x, ship.y, other.x, other.y);
            if (d < bestDistance) {
                bestDistance = d;
                best = other;
            }
        }
        return best;
    }

    private void moveShip(Ship ship, float dt) {
        float windMultiplier = windMultiplier(ship);
        ship.windBoost = windMultiplier > 1.10f;

        float proposedX = ship.x + (float) Math.cos(ship.angle) * ship.speed * windMultiplier * dt;
        float proposedY = ship.y + (float) Math.sin(ship.angle) * ship.speed * windMultiplier * dt;

        Rock collision = collidingRock(proposedX, proposedY, ship.radius() * 0.68f);
        if (collision != null) {
            float away = (float) Math.atan2(proposedY - collision.y, proposedX - collision.x);
            float minDistance = collision.radius + ship.radius() * 0.72f;
            ship.x = collision.x + (float) Math.cos(away) * minDistance;
            ship.y = collision.y + (float) Math.sin(away) * minDistance;
            ship.speed *= 0.36f;
            ship.angle = normalizeAngle(ship.angle + (ship.isPlayer ? 0f : ship.preferredSide * 0.42f));
            ship.desiredAngle = ship.angle;
        } else {
            ship.x = proposedX;
            ship.y = proposedY;
        }

        float margin = 48f + ship.radius();
        boolean bounced = false;
        if (ship.x < margin) { ship.x = margin; ship.angle = (float) Math.PI - ship.angle; bounced = true; }
        if (ship.x > WORLD_W - margin) { ship.x = WORLD_W - margin; ship.angle = (float) Math.PI - ship.angle; bounced = true; }
        if (ship.y < margin) { ship.y = margin; ship.angle = -ship.angle; bounced = true; }
        if (ship.y > WORLD_H - margin) { ship.y = WORLD_H - margin; ship.angle = -ship.angle; bounced = true; }
        if (bounced) ship.desiredAngle = normalizeAngle(ship.angle);
    }

    private float windMultiplier(Ship ship) {
        float multiplier = 1f;
        for (WindZone zone : windZones) {
            if (distanceSq(ship.x, ship.y, zone.x, zone.y) > zone.radius * zone.radius) continue;
            float headingError = Math.abs(normalizeAngle(ship.angle - zone.angle));
            if (headingError < 1.25f) {
                float alignment = 1f - headingError / 1.25f;
                multiplier = Math.max(multiplier, 1f + (zone.strength - 1f) * (0.35f + 0.65f * alignment));
            } else {
                multiplier = Math.max(multiplier, 1.05f);
            }
        }
        return multiplier;
    }

    private Rock collidingRock(float x, float y, float extraRadius) {
        for (Rock rock : rocks) {
            float r = rock.radius + extraRadius;
            if (distanceSq(x, y, rock.x, rock.y) < r * r) return rock;
        }
        return null;
    }

    private void fireBroadside(Ship ship) {
        if (!ship.alive || ship.fireCooldown > 0f || shopOpen || adOverlayTimer > 0f) return;

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
            if (!remove && collidingRock(ball.x, ball.y, 4f) != null) remove = true;

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
            piece.coinValue = 1 + random.nextInt(Math.max(2, victim.level + 1));
            piece.phase = random.nextFloat() * 6f;
            loot.add(piece);
        }

        if (killer != null && killer.alive) {
            killer.xp += 16 + victim.level * 18;
            killer.hp = Math.min(killer.maxHp, killer.hp + 8f + victim.level * 2f);
            if (killer.isPlayer) {
                addCoins(8 + victim.level * 4);
            }
            checkLevel(killer);
        }

        if (victim.isPlayer) {
            deathMessageTimer = 2.25f;
            deathCount++;
            if (deathCount >= nextAdDeath) {
                adOverlayTimer = 2.35f;
                nextAdDeath = deathCount + 2 + random.nextInt(2);
            }
        }
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
                if (collector.isPlayer) addCoins(piece.coinValue);
                checkLevel(collector);
                loot.remove(i);
            }
        }
    }

    private void addCoins(int amount) {
        coins = Math.max(0, coins + amount);
        prefs.edit().putInt("coins", coins).apply();
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
        safeRandomPosition(ship);
        ship.angle = random.nextFloat() * (float) Math.PI * 2f;
        ship.desiredAngle = ship.angle;
        ship.speed = 0f;
        ship.fireCooldown = 1f;
        ship.target = null;
        ship.windBoost = false;

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

    private void safeRandomPosition(Ship ship) {
        for (int tries = 0; tries < 40; tries++) {
            float x = 180f + random.nextFloat() * (WORLD_W - 360f);
            float y = 180f + random.nextFloat() * (WORLD_H - 360f);
            if (collidingRock(x, y, 100f) == null) {
                ship.x = x;
                ship.y = y;
                return;
            }
        }
        ship.x = WORLD_W * 0.5f;
        ship.y = WORLD_H * 0.5f;
    }

    private void spawnRandomLoot() {
        Loot piece = new Loot();
        for (int tries = 0; tries < 20; tries++) {
            piece.x = 55f + random.nextFloat() * (WORLD_W - 110f);
            piece.y = 55f + random.nextFloat() * (WORLD_H - 110f);
            if (collidingRock(piece.x, piece.y, 20f) == null) break;
        }
        piece.value = 6 + random.nextInt(10);
        piece.coinValue = random.nextFloat() < 0.26f ? 2 : 1;
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
        drawWindZones(canvas);
        drawRocks(canvas);
        drawLoot(canvas);
        drawCannonballs(canvas);

        ArrayList<Ship> renderShips = new ArrayList<>(ships);
        Collections.sort(renderShips, (a, b) -> Float.compare(a.y, b.y));
        for (Ship ship : renderShips) if (ship.alive) drawShip(canvas, ship);

        canvas.restore();
        drawHud(canvas);

        if (shopOpen) drawShop(canvas);
        if (adOverlayTimer > 0f) drawAdPlaceholder(canvas);
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

    private void drawWindZones(Canvas canvas) {
        for (WindZone zone : windZones) {
            paint.setColor(Color.argb(24, 225, 250, 255));
            canvas.drawCircle(zone.x, zone.y, zone.radius, paint);

            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(4f);
            paint.setColor(Color.argb(115, 225, 250, 255));
            canvas.drawCircle(zone.x, zone.y, zone.radius, paint);

            float ux = (float) Math.cos(zone.angle);
            float uy = (float) Math.sin(zone.angle);
            for (int i = -2; i <= 2; i++) {
                float px = zone.x - uy * i * 58f;
                float py = zone.y + ux * i * 58f;
                float x1 = px - ux * 105f;
                float y1 = py - uy * 105f;
                float x2 = px + ux * 105f;
                float y2 = py + uy * 105f;
                canvas.drawLine(x1, y1, x2, y2, paint);
                canvas.drawLine(x2, y2, x2 - ux * 28f - uy * 16f, y2 - uy * 28f + ux * 16f, paint);
                canvas.drawLine(x2, y2, x2 - ux * 28f + uy * 16f, y2 - uy * 28f - ux * 16f, paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }
    }

    private void drawRocks(Canvas canvas) {
        for (Rock rock : rocks) {
            paint.setColor(Color.argb(65, 0, 25, 35));
            canvas.drawOval(new RectF(rock.x - rock.radius * 1.15f, rock.y + rock.radius * 0.30f,
                    rock.x + rock.radius * 1.15f, rock.y + rock.radius * 0.72f), paint);
            paint.setColor(Color.rgb(91, 103, 104));
            canvas.drawCircle(rock.x, rock.y, rock.radius, paint);
            paint.setColor(Color.rgb(129, 139, 137));
            canvas.drawCircle(rock.x - rock.radius * 0.22f, rock.y - rock.radius * 0.22f, rock.radius * 0.64f, paint);
            paint.setColor(Color.rgb(167, 174, 165));
            canvas.drawCircle(rock.x - rock.radius * 0.36f, rock.y - rock.radius * 0.38f, rock.radius * 0.22f, paint);
        }
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

        if (ship.windBoost) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(5f);
            paint.setColor(Color.argb(150, 223, 250, 255));
            canvas.drawLine(-r * 1.70f, -r * 0.24f, -r * 1.02f, -r * 0.14f, paint);
            canvas.drawLine(-r * 1.82f, r * 0.20f, -r * 1.02f, r * 0.12f, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        int hullColor = hullColorFor(ship);
        int sailColor = sailColorFor(ship);

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
        paint.setColor(ship.isPlayer && selectedSkin == 2 ? Color.rgb(142, 157, 162) : Color.rgb(193, 139, 73));
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
            paint.setColor(sailColor);
            canvas.drawPath(path, paint);
        }

        paint.setColor(flagColorFor(ship));
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
            paint.setColor(selectedSkin == 3 ? Color.rgb(244, 214, 89) : Color.rgb(248, 213, 73));
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

    private int hullColorFor(Ship ship) {
        if (!ship.isPlayer) {
            if (ship.role == Role.NAVY) return Color.rgb(48, 63, 92);
            if (ship.role == Role.MERCHANT) return Color.rgb(116, 81, 42);
            return Color.rgb(82, 46, 31);
        }
        switch (selectedSkin) {
            case 1: return Color.rgb(126, 35, 35);
            case 2: return Color.rgb(66, 82, 87);
            case 3: return Color.rgb(45, 67, 104);
            default: return Color.rgb(110, 61, 30);
        }
    }

    private int sailColorFor(Ship ship) {
        if (!ship.isPlayer) {
            if (ship.role == Role.NAVY) return Color.rgb(226, 235, 244);
            return Color.rgb(218, 205, 172);
        }
        switch (selectedSkin) {
            case 1: return Color.rgb(70, 18, 25);
            case 2: return Color.rgb(210, 229, 229);
            case 3: return Color.rgb(245, 229, 174);
            default: return Color.rgb(245, 229, 191);
        }
    }

    private int flagColorFor(Ship ship) {
        if (!ship.isPlayer) return Color.rgb(32, 34, 39);
        switch (selectedSkin) {
            case 1: return Color.rgb(224, 44, 47);
            case 2: return Color.rgb(225, 238, 240);
            case 3: return Color.rgb(244, 202, 49);
            default: return Color.rgb(224, 44, 47);
        }
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

        float coinY = statTop + statH * 2f + base * 0.042f;
        paint.setTextSize(small * 1.08f);
        paint.setColor(Color.rgb(255, 222, 92));
        canvas.drawText("COINS  " + coins, pad, coinY, paint);
        if (player.windBoost && player.alive) {
            paint.setColor(Color.rgb(221, 250, 255));
            canvas.drawText("TAILWIND  •  SPEED BOOST", pad, coinY + small * 1.35f, paint);
        }

        drawLeaderboard(canvas, w, base, pad, small);
        drawShopButton(canvas, w, base, pad, small);
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

    private void drawLeaderboard(Canvas canvas, float w, float base, float pad, float small) {
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

    private RectF shopButtonRect(float w, float base, float pad) {
        float bw = base * 0.26f;
        float bh = base * 0.055f;
        return new RectF(w * 0.5f - bw * 0.5f, pad, w * 0.5f + bw * 0.5f, pad + bh);
    }

    private void drawShopButton(Canvas canvas, float w, float base, float pad, float small) {
        RectF r = shopButtonRect(w, base, pad);
        paint.setColor(Color.argb(185, 78, 47, 25));
        canvas.drawRoundRect(r, 14f, 14f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(base * 0.004f);
        paint.setColor(Color.rgb(245, 205, 91));
        canvas.drawRoundRect(r, 14f, 14f, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextSize(small * 1.05f);
        paint.setColor(Color.WHITE);
        canvas.drawText("SKINS", r.centerX(), r.centerY() + small * 0.35f, paint);
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
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

    private void drawShop(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float base = Math.min(w, h);
        paint.setColor(Color.argb(215, 4, 18, 25));
        canvas.drawRect(0, 0, w, h, paint);

        RectF panel = new RectF(w * 0.07f, h * 0.08f, w * 0.93f, h * 0.92f);
        paint.setColor(Color.rgb(18, 62, 76));
        canvas.drawRoundRect(panel, 26f, 26f, paint);

        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextSize(base * 0.055f);
        paint.setColor(Color.WHITE);
        canvas.drawText("CAPTAIN'S OUTFITTER", panel.left + base * 0.035f, panel.top + base * 0.07f, paint);
        paint.setTextSize(base * 0.027f);
        paint.setColor(Color.rgb(255, 222, 92));
        canvas.drawText("COINS  " + coins, panel.left + base * 0.035f, panel.top + base * 0.115f, paint);

        RectF close = shopCloseRect(panel, base);
        paint.setColor(Color.rgb(115, 45, 38));
        canvas.drawRoundRect(close, 12f, 12f, paint);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(base * 0.025f);
        paint.setColor(Color.WHITE);
        canvas.drawText("CLOSE", close.centerX(), close.centerY() + base * 0.009f, paint);

        float gap = base * 0.020f;
        float cardTop = panel.top + base * 0.15f;
        float cardBottom = panel.top + base * 0.51f;
        float usable = panel.width() - gap * 5f;
        float cardW = usable / SKIN_NAMES.length;

        for (int i = 0; i < SKIN_NAMES.length; i++) {
            RectF card = new RectF(panel.left + gap + i * (cardW + gap), cardTop,
                    panel.left + gap + i * (cardW + gap) + cardW, cardBottom);
            boolean owned = (purchasedSkins & (1 << i)) != 0;
            boolean selected = selectedSkin == i;

            paint.setColor(selected ? Color.rgb(72, 104, 96) : Color.rgb(27, 79, 93));
            canvas.drawRoundRect(card, 18f, 18f, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(selected ? 5f : 2f);
            paint.setColor(selected ? Color.rgb(255, 216, 82) : Color.argb(120, 220, 240, 244));
            canvas.drawRoundRect(card, 18f, 18f, paint);
            paint.setStyle(Paint.Style.FILL);

            drawShopShipPreview(canvas, card.centerX(), card.top + card.height() * 0.32f, base * 0.055f, i);

            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            paint.setTextSize(base * 0.026f);
            paint.setColor(Color.WHITE);
            canvas.drawText(SKIN_NAMES[i], card.centerX(), card.top + card.height() * 0.62f, paint);

            paint.setTextSize(base * 0.022f);
            if (selected) {
                paint.setColor(Color.rgb(255, 224, 88));
                canvas.drawText("EQUIPPED", card.centerX(), card.top + card.height() * 0.80f, paint);
            } else if (owned) {
                paint.setColor(Color.rgb(201, 239, 211));
                canvas.drawText("TAP TO EQUIP", card.centerX(), card.top + card.height() * 0.80f, paint);
            } else {
                paint.setColor(Color.rgb(255, 220, 109));
                canvas.drawText(SKIN_COSTS[i] + " COINS", card.centerX(), card.top + card.height() * 0.80f, paint);
            }
        }

        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTextSize(base * 0.026f);
        paint.setColor(Color.WHITE);
        canvas.drawText("COIN STORE  •  prototype purchase buttons", panel.left + gap, panel.top + base * 0.59f, paint);

        int[] packs = {1000, 3000, 8000};
        float packTop = panel.top + base * 0.625f;
        float packH = base * 0.11f;
        float packW = (panel.width() - gap * 4f) / 3f;
        for (int i = 0; i < packs.length; i++) {
            RectF r = new RectF(panel.left + gap + i * (packW + gap), packTop,
                    panel.left + gap + i * (packW + gap) + packW, packTop + packH);
            paint.setColor(Color.rgb(100, 70, 29));
            canvas.drawRoundRect(r, 14f, 14f, paint);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
            paint.setTextSize(base * 0.027f);
            paint.setColor(Color.rgb(255, 222, 92));
            canvas.drawText("+" + packs[i] + " COINS", r.centerX(), r.top + packH * 0.45f, paint);
            paint.setTextSize(base * 0.018f);
            paint.setColor(Color.WHITE);
            canvas.drawText("TEST BUY", r.centerX(), r.top + packH * 0.76f, paint);
        }

        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTextSize(base * 0.020f);
        paint.setColor(Color.argb(190, 230, 242, 244));
        canvas.drawText("Real-money checkout will replace TEST BUY when Play Billing is connected.",
                panel.centerX(), panel.bottom - base * 0.035f, paint);

        if (storeMessageTimer > 0f) {
            paint.setTextSize(base * 0.025f);
            paint.setColor(Color.rgb(255, 225, 104));
            canvas.drawText(storeMessage, panel.centerX(), panel.bottom - base * 0.075f, paint);
        }
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
    }

    private RectF shopCloseRect(RectF panel, float base) {
        float w = base * 0.16f;
        float h = base * 0.055f;
        return new RectF(panel.right - w - base * 0.025f, panel.top + base * 0.025f,
                panel.right - base * 0.025f, panel.top + base * 0.025f + h);
    }

    private void drawShopShipPreview(Canvas canvas, float x, float y, float r, int skin) {
        int oldSkin = selectedSkin;
        selectedSkin = skin;
        Ship fake = new Ship("", true, null);
        fake.level = 3;
        fake.x = x;
        fake.y = y;
        fake.angle = 0f;
        fake.hp = 1f;
        fake.maxHp = 1f;
        float rr = r;
        canvas.save();
        canvas.translate(x, y);
        path.reset();
        path.moveTo(rr * 1.1f, 0f);
        path.lineTo(rr * 0.4f, rr * 0.55f);
        path.lineTo(-rr * 0.95f, rr * 0.48f);
        path.lineTo(-rr * 1.05f, 0f);
        path.lineTo(-rr * 0.95f, -rr * 0.48f);
        path.lineTo(rr * 0.4f, -rr * 0.55f);
        path.close();
        paint.setColor(hullColorFor(fake));
        canvas.drawPath(path, paint);
        paint.setColor(sailColorFor(fake));
        canvas.drawRect(-rr * 0.18f, -rr * 0.40f, rr * 0.20f, rr * 0.40f, paint);
        canvas.restore();
        selectedSkin = oldSkin;
    }

    private void drawAdPlaceholder(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float base = Math.min(w, h);
        paint.setColor(Color.argb(235, 4, 13, 18));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextSize(base * 0.075f);
        paint.setColor(Color.WHITE);
        canvas.drawText("AD BREAK", w * 0.5f, h * 0.46f, paint);
        paint.setTextSize(base * 0.027f);
        paint.setColor(Color.rgb(245, 214, 125));
        canvas.drawText("Prototype interstitial • every 2–3 deaths", w * 0.5f, h * 0.54f, paint);
        paint.setTextSize(base * 0.020f);
        paint.setColor(Color.LTGRAY);
        canvas.drawText("This screen will be replaced by the ad SDK.", w * 0.5f, h * 0.60f, paint);
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        int action = event.getActionMasked();
        int index = event.getActionIndex();
        int pointerId = event.getPointerId(index);
        float x = event.getX(index);
        float y = event.getY(index);

        if (adOverlayTimer > 0f) return true;

        if (shopOpen) {
            if (action == MotionEvent.ACTION_DOWN) handleShopTap(x, y);
            return true;
        }

        if (action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) {
            float base = Math.min(getWidth(), getHeight());
            if (shopButtonRect(getWidth(), base, base * 0.026f).contains(x, y)) {
                shopOpen = true;
                joystickPointer = -1;
                firePointer = -1;
                joystickActive = false;
                fireHeld = false;
                return true;
            }

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

    private void handleShopTap(float x, float y) {
        float w = getWidth();
        float h = getHeight();
        float base = Math.min(w, h);
        RectF panel = new RectF(w * 0.07f, h * 0.08f, w * 0.93f, h * 0.92f);

        if (shopCloseRect(panel, base).contains(x, y)) {
            shopOpen = false;
            return;
        }

        float gap = base * 0.020f;
        float cardTop = panel.top + base * 0.15f;
        float cardBottom = panel.top + base * 0.51f;
        float usable = panel.width() - gap * 5f;
        float cardW = usable / SKIN_NAMES.length;

        for (int i = 0; i < SKIN_NAMES.length; i++) {
            RectF card = new RectF(panel.left + gap + i * (cardW + gap), cardTop,
                    panel.left + gap + i * (cardW + gap) + cardW, cardBottom);
            if (!card.contains(x, y)) continue;

            boolean owned = (purchasedSkins & (1 << i)) != 0;
            if (owned) {
                selectedSkin = i;
                prefs.edit().putInt("selected_skin", selectedSkin).apply();
                showStoreMessage(SKIN_NAMES[i] + " equipped");
            } else if (coins >= SKIN_COSTS[i]) {
                coins -= SKIN_COSTS[i];
                purchasedSkins |= (1 << i);
                selectedSkin = i;
                prefs.edit()
                        .putInt("coins", coins)
                        .putInt("skins", purchasedSkins)
                        .putInt("selected_skin", selectedSkin)
                        .apply();
                showStoreMessage(SKIN_NAMES[i] + " purchased");
            } else {
                showStoreMessage("Not enough coins");
            }
            return;
        }

        int[] packs = {1000, 3000, 8000};
        float packTop = panel.top + base * 0.625f;
        float packH = base * 0.11f;
        float packW = (panel.width() - gap * 4f) / 3f;
        for (int i = 0; i < packs.length; i++) {
            RectF r = new RectF(panel.left + gap + i * (packW + gap), packTop,
                    panel.left + gap + i * (packW + gap) + packW, packTop + packH);
            if (r.contains(x, y)) {
                addCoins(packs[i]);
                showStoreMessage("Prototype purchase: +" + packs[i] + " coins");
                return;
            }
        }
    }

    private void showStoreMessage(String message) {
        storeMessage = message;
        storeMessageTimer = 2.2f;
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
        float preferredSide = 1f;
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
        boolean windBoost;
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
        int coinValue;
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

    private static class Rock {
        float x;
        float y;
        float radius;
    }

    private static class WindZone {
        float x;
        float y;
        float radius;
        float angle;
        float strength;
    }
}
