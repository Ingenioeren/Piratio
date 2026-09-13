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
    private static final float WORLD_W = 9200f;
    private static final float WORLD_H = 6500f;
    private static final int BOT_COUNT = 100;
    private static final int BASE_LOOT_COUNT = 360;
    private static final int MAX_LEVEL = 10;
    private static final int[] LEVEL_THRESHOLDS = {0, 0, 60, 160, 320, 560, 860, 1240, 1700, 2260, 2940};
    private static final String[] BASE_TITLES = {"", "Sloop", "Brig", "Frigate", "Galleon", "Man-o'-War"};
    private static final String[] RAIDER_TITLES = {"", "", "", "", "", "", "Corsair", "Sea Wraith", "Phantom", "Tempest", "Blackwind"};
    private static final String[] GUNNER_TITLES = {"", "", "", "", "", "", "Gunship", "Bombard", "Dreadnought", "Leviathan", "Iron Fortress"};
    private static final String[] HUNTER_TITLES = {"", "", "", "", "", "", "Privateer", "Marauder", "Reaper", "Executioner", "Kraken Hunter"};
    private static final String[] BOT_NAMES = {
            "Black Bart", "Red Anne", "Barnacle Ben", "Mad Morgan", "Saltbeard", "Iron Jack",
            "Cutlass Kate", "Deadeye Dan", "Old Flint", "Sea Rat", "Captain Crow", "Bonny",
            "Dread Drake", "Gunpowder Gus", "Navy Hawk", "Blue Roger", "Scurvy Sam", "Kraken Joe",
            "Storm Mary", "Shark Finn", "One-Eye", "Gold Tooth", "Powder Pete", "Cannon Jane",
            "Wave Walker", "Scarlet Sam", "Anchor Abe", "Rogue Rose", "Tide Turner", "Skull Jim"
    };

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
    private int deathCount;
    private int nextAdDeath = 2;
    private int coins;
    private int selectedSkin;
    private boolean buildTreeOpen;

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
        selectedSkin = clampInt(prefs.getInt("selected_skin", 0), 0, 3);
        setBackgroundColor(Color.rgb(23, 126, 164));
        setFocusable(true);
        setKeepScreenOn(true);
        initialiseWorld();
    }

    private void initialiseWorld() {
        generateEnvironment();

        player = new Ship("YOU", true, Role.PIRATE);
        safeRandomPosition(player);
        player.angle = -0.25f;
        player.desiredAngle = player.angle;
        configureForLevel(player, 1, true);
        ships.add(player);

        for (int i = 0; i < BOT_COUNT; i++) {
            Role role;
            if (i % 11 == 0) role = Role.NAVY;
            else if (i % 4 == 0) role = Role.MERCHANT;
            else role = Role.PIRATE;

            String suffix = i < BOT_NAMES.length ? "" : " " + (1 + i / BOT_NAMES.length);
            Ship bot = new Ship(BOT_NAMES[i % BOT_NAMES.length] + suffix, false, role);
            safeRandomPosition(bot);
            bot.angle = random.nextFloat() * (float) (Math.PI * 2.0);
            bot.desiredAngle = bot.angle;
            bot.preferredSide = random.nextBoolean() ? 1f : -1f;

            int roll = random.nextInt(100);
            int startingLevel = roll < 4 ? 6 : roll < 12 ? 4 : roll < 30 ? 3 : roll < 58 ? 2 : 1;
            if (startingLevel >= 6) bot.branch = randomBranch();
            bot.xp = LEVEL_THRESHOLDS[startingLevel] + random.nextInt(startingLevel >= 5 ? 100 : 45);
            configureForLevel(bot, startingLevel, true);
            bot.aiTimer = random.nextFloat() * 1.5f;
            ships.add(bot);
        }

        for (int i = 0; i < BASE_LOOT_COUNT; i++) spawnRandomLoot();
    }

    private void generateEnvironment() {
        rocks.clear();
        windZones.clear();

        float[][] clusters = {
                {900f, 900f}, {2100f, 1600f}, {3400f, 800f}, {4700f, 1900f}, {6100f, 950f},
                {7800f, 1550f}, {1300f, 4300f}, {2800f, 5200f}, {4700f, 4300f}, {6500f, 5050f},
                {8100f, 3900f}, {5550f, 3150f}
        };
        for (float[] c : clusters) {
            int count = 5 + random.nextInt(5);
            for (int i = 0; i < count; i++) {
                float a = random.nextFloat() * (float) Math.PI * 2f;
                float d = 90f + random.nextFloat() * 360f;
                Rock rock = new Rock();
                rock.x = c[0] + (float) Math.cos(a) * d;
                rock.y = c[1] + (float) Math.sin(a) * d;
                rock.radius = 45f + random.nextFloat() * 78f;
                rocks.add(rock);
            }
        }

        addWind(1150f, 2800f, 430f, -0.35f, 1.42f);
        addWind(2600f, 900f, 460f, 0.30f, 1.40f);
        addWind(3950f, 2850f, 500f, 0.95f, 1.47f);
        addWind(5200f, 1250f, 410f, 2.55f, 1.38f);
        addWind(6850f, 2750f, 500f, 0.18f, 1.45f);
        addWind(7900f, 5100f, 460f, -2.55f, 1.43f);
        addWind(3500f, 5250f, 440f, -1.05f, 1.40f);
        addWind(5700f, 4700f, 390f, 2.95f, 1.36f);
    }

    private void addWind(float x, float y, float radius, float angle, float strength) {
        WindZone z = new WindZone();
        z.x = x;
        z.y = y;
        z.radius = radius;
        z.angle = angle;
        z.strength = strength;
        windZones.add(z);
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
        if (adOverlayTimer > 0f) {
            adOverlayTimer -= dt;
            return;
        }
        if (buildTreeOpen) return;

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
            int amount = Math.min(5, BASE_LOOT_COUNT - loot.size());
            for (int i = 0; i < amount; i++) spawnRandomLoot();
        }
    }

    private void updatePlayer(Ship ship, float dt) {
        if (joystickActive) {
            float dx = joystickX - joystickCx;
            float dy = joystickY - joystickCy;
            float d = (float) Math.sqrt(dx * dx + dy * dy);
            ship.angle = rotateTowards(ship.angle, (float) Math.atan2(dy, dx), ship.turnRate * dt);
            float throttle = clamp(d / joystickRadius(), 0f, 1f);
            float targetSpeed = ship.baseSpeed() * (0.35f + 0.65f * throttle);
            ship.speed += (targetSpeed - ship.speed) * Math.min(1f, dt * 5f);
        } else {
            float targetSpeed = ship.baseSpeed() * 0.28f;
            ship.speed += (targetSpeed - ship.speed) * Math.min(1f, dt * 3f);
        }
        if (fireHeld && ship.fireCooldown <= 0f) fireBroadside(ship);
    }

    private void updateBot(Ship ship, float dt) {
        ship.aiTimer -= dt;
        if (ship.aiTimer <= 0f) {
            ship.aiTimer = 0.75f + random.nextFloat() * 0.95f;
            if (ship.role == Role.MERCHANT) {
                Ship threat = nearestHostile(ship, 580f);
                ship.target = threat;
                if (threat != null) ship.desiredAngle = (float) Math.atan2(ship.y - threat.y, ship.x - threat.x);
                else ship.desiredAngle = normalizeAngle(ship.desiredAngle + (random.nextFloat() - 0.5f) * 0.9f);
            } else {
                ship.target = findTarget(ship);
                if (ship.target != null) {
                    float bearing = (float) Math.atan2(ship.target.y - ship.y, ship.target.x - ship.x);
                    float d = distance(ship.x, ship.y, ship.target.x, ship.target.y);
                    ship.desiredAngle = normalizeAngle(bearing + ship.preferredSide * (d < 600f ? 1.45f : 0.30f));
                } else {
                    ship.desiredAngle = normalizeAngle(ship.desiredAngle + (random.nextFloat() - 0.5f) * 1.1f);
                }
            }
            avoidRock(ship);
        }

        ship.angle = rotateTowards(ship.angle, ship.desiredAngle, ship.turnRate * dt * 0.82f);
        float targetSpeed = ship.baseSpeed() * (ship.role == Role.MERCHANT ? 1.06f : 1f);
        ship.speed += (targetSpeed - ship.speed) * Math.min(1f, dt * 2.1f);

        if (ship.target != null && ship.target.alive && ship.fireCooldown <= 0f) {
            float d = distance(ship.x, ship.y, ship.target.x, ship.target.y);
            float bearing = (float) Math.atan2(ship.target.y - ship.y, ship.target.x - ship.x);
            float delta = Math.abs(normalizeAngle(bearing - ship.angle));
            float broadsideError = Math.abs(delta - (float) Math.PI / 2f);
            if (d < ship.shotRange() && broadsideError < 0.52f && random.nextFloat() < 0.14f) {
                fireBroadside(ship);
                if (random.nextFloat() < 0.16f) ship.preferredSide *= -1f;
            }
        }
    }

    private Ship findTarget(Ship ship) {
        Ship best = null;
        float bestScore = Float.MAX_VALUE;
        float maxRangeSq = 1050f * 1050f;
        for (Ship other : ships) {
            if (other == ship || !other.alive) continue;
            float d = distanceSq(ship.x, ship.y, other.x, other.y);
            if (d > maxRangeSq) continue;

            float score = d;
            if (other.isPlayer) score *= 1.45f;
            if (ship.role == Role.PIRATE) {
                if (other.role == Role.MERCHANT) score *= 0.50f;
                else if (other.role == Role.PIRATE) score *= 1.15f;
                else score *= 1.08f;
            } else if (ship.role == Role.NAVY) {
                if (other.role == Role.PIRATE) score *= 0.52f;
                if (other.role == Role.MERCHANT) score *= 1.8f;
            }
            if (other.level > ship.level + 2) score *= 1.35f;
            score *= 0.84f + random.nextFloat() * 0.34f;
            if (score < bestScore) {
                bestScore = score;
                best = other;
            }
        }
        return best;
    }

    private Ship nearestHostile(Ship ship, float range) {
        Ship best = null;
        float bestD = range * range;
        for (Ship other : ships) {
            if (other == ship || !other.alive || other.role == Role.MERCHANT) continue;
            float d = distanceSq(ship.x, ship.y, other.x, other.y);
            if (d < bestD) {
                bestD = d;
                best = other;
            }
        }
        return best;
    }

    private void avoidRock(Ship ship) {
        for (Rock rock : rocks) {
            float range = rock.radius + 210f;
            if (distanceSq(ship.x, ship.y, rock.x, rock.y) < range * range) {
                float away = (float) Math.atan2(ship.y - rock.y, ship.x - rock.x);
                ship.desiredAngle = normalizeAngle(away + ship.preferredSide * 0.45f);
                return;
            }
        }
    }

    private void moveShip(Ship ship, float dt) {
        float boost = windMultiplier(ship);
        ship.windBoost = boost > 1.10f;
        float nx = ship.x + (float) Math.cos(ship.angle) * ship.speed * boost * dt;
        float ny = ship.y + (float) Math.sin(ship.angle) * ship.speed * boost * dt;

        Rock hit = collidingRock(nx, ny, ship.radius() * 0.70f);
        if (hit != null) {
            float away = (float) Math.atan2(ny - hit.y, nx - hit.x);
            float minD = hit.radius + ship.radius() * 0.74f;
            ship.x = hit.x + (float) Math.cos(away) * minD;
            ship.y = hit.y + (float) Math.sin(away) * minD;
            ship.speed *= 0.30f;
            if (!ship.isPlayer) ship.angle = normalizeAngle(ship.angle + ship.preferredSide * 0.5f);
            ship.desiredAngle = ship.angle;
        } else {
            ship.x = nx;
            ship.y = ny;
        }

        float margin = 55f + ship.radius();
        boolean bounced = false;
        if (ship.x < margin) { ship.x = margin; ship.angle = (float) Math.PI - ship.angle; bounced = true; }
        if (ship.x > WORLD_W - margin) { ship.x = WORLD_W - margin; ship.angle = (float) Math.PI - ship.angle; bounced = true; }
        if (ship.y < margin) { ship.y = margin; ship.angle = -ship.angle; bounced = true; }
        if (ship.y > WORLD_H - margin) { ship.y = WORLD_H - margin; ship.angle = -ship.angle; bounced = true; }
        if (bounced) ship.desiredAngle = normalizeAngle(ship.angle);
    }

    private float windMultiplier(Ship ship) {
        float result = 1f;
        for (WindZone z : windZones) {
            if (distanceSq(ship.x, ship.y, z.x, z.y) > z.radius * z.radius) continue;
            float error = Math.abs(normalizeAngle(ship.angle - z.angle));
            if (error < 1.25f) {
                float alignment = 1f - error / 1.25f;
                result = Math.max(result, 1f + (z.strength - 1f) * (0.30f + alignment * 0.70f));
            } else result = Math.max(result, 1.04f);
        }
        return result;
    }

    private Rock collidingRock(float x, float y, float extra) {
        for (Rock rock : rocks) {
            float r = rock.radius + extra;
            if (distanceSq(x, y, rock.x, rock.y) < r * r) return rock;
        }
        return null;
    }

    private void fireBroadside(Ship ship) {
        if (!ship.alive || ship.fireCooldown > 0f || buildTreeOpen || adOverlayTimer > 0f) return;
        int guns = ship.gunsPerSide();
        float muzzle = 490f + ship.level * 17f;
        float spread = guns <= 2 ? 0.10f : 0.075f;

        for (int side = -1; side <= 1; side += 2) {
            float sideAngle = ship.angle + side * (float) Math.PI / 2f;
            for (int gun = 0; gun < guns; gun++) {
                if (cannonballs.size() > 520) cannonballs.remove(0);
                float along = (gun - (guns - 1) * 0.5f) * ship.radius() * 0.34f;
                float shotAngle = sideAngle + (gun - (guns - 1) * 0.5f) * spread;
                Cannonball b = new Cannonball();
                b.x = ship.x + (float) Math.cos(ship.angle) * along + (float) Math.cos(sideAngle) * ship.radius() * 0.82f;
                b.y = ship.y + (float) Math.sin(ship.angle) * along + (float) Math.sin(sideAngle) * ship.radius() * 0.82f;
                b.vx = (float) Math.cos(shotAngle) * muzzle + (float) Math.cos(ship.angle) * ship.speed * 0.25f;
                b.vy = (float) Math.sin(shotAngle) * muzzle + (float) Math.sin(ship.angle) * ship.speed * 0.25f;
                b.life = ship.projectileLife();
                b.owner = ship;
                cannonballs.add(b);
            }
        }
        ship.fireCooldown = ship.reloadTime();
    }

    private void updateCannonballs(float dt) {
        for (int i = cannonballs.size() - 1; i >= 0; i--) {
            Cannonball b = cannonballs.get(i);
            b.x += b.vx * dt;
            b.y += b.vy * dt;
            b.life -= dt;
            boolean remove = b.life <= 0f || b.x < 0f || b.y < 0f || b.x > WORLD_W || b.y > WORLD_H;
            if (!remove && collidingRock(b.x, b.y, 4f) != null) remove = true;
            if (!remove) {
                for (Ship ship : ships) {
                    if (ship == b.owner || !ship.alive) continue;
                    float r = ship.radius() * 0.78f;
                    if (distanceSq(b.x, b.y, ship.x, ship.y) <= r * r) {
                        ship.hp -= b.owner.shotDamage();
                        remove = true;
                        if (ship.hp <= 0f) sinkShip(ship, b.owner);
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
        victim.respawnTimer = victim.isPlayer ? 2.4f : 3f + random.nextFloat() * 2.5f;

        int drops = 4 + Math.min(16, victim.level * 2);
        for (int i = 0; i < drops; i++) {
            Loot l = new Loot();
            float a = random.nextFloat() * (float) Math.PI * 2f;
            float d = 15f + random.nextFloat() * 95f;
            l.x = clamp(victim.x + (float) Math.cos(a) * d, 40f, WORLD_W - 40f);
            l.y = clamp(victim.y + (float) Math.sin(a) * d, 40f, WORLD_H - 40f);
            l.value = 6 + victim.level * 3 + random.nextInt(8);
            l.coinValue = 1 + random.nextInt(Math.max(2, victim.level));
            l.phase = random.nextFloat() * 6f;
            loot.add(l);
        }

        if (killer != null && killer.alive) {
            killer.xp += 12 + victim.level * 20;
            killer.hp = Math.min(killer.maxHp, killer.hp + 6f + victim.level * 2f);
            if (killer.isPlayer) addCoins(Math.round((7 + victim.level * 4) * killer.coinMultiplier()));
            checkLevel(killer);
        }

        if (victim.isPlayer) {
            deathMessageTimer = 2.4f;
            deathCount++;
            if (deathCount >= nextAdDeath) {
                adOverlayTimer = 2.3f;
                nextAdDeath = deathCount + 2 + random.nextInt(2);
            }
        }
    }

    private void collectLoot() {
        for (int i = loot.size() - 1; i >= 0; i--) {
            Loot l = loot.get(i);
            Ship collector = null;
            float best = Float.MAX_VALUE;
            for (Ship ship : ships) {
                if (!ship.alive) continue;
                float r = ship.radius() + 20f;
                float d = distanceSq(l.x, l.y, ship.x, ship.y);
                if (d < r * r && d < best) {
                    best = d;
                    collector = ship;
                }
            }
            if (collector != null) {
                collector.xp += l.value;
                collector.hp = Math.min(collector.maxHp, collector.hp + 1.4f);
                if (collector.isPlayer) addCoins(Math.max(1, Math.round(l.coinValue * collector.coinMultiplier())));
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
        if (ship.level >= MAX_LEVEL) return;
        int desired = ship.level;
        while (desired < MAX_LEVEL && ship.xp >= LEVEL_THRESHOLDS[desired + 1]) desired++;

        if (ship.branch == Branch.BASE && desired >= 6) {
            if (ship.isPlayer) {
                ship.xp = Math.max(ship.xp, LEVEL_THRESHOLDS[6]);
                buildTreeOpen = true;
                return;
            }
            ship.branch = randomBranch();
        }
        if (desired != ship.level) configureForLevel(ship, desired, false);
    }

    private void chooseBranch(Branch branch) {
        player.branch = branch;
        configureForLevel(player, Math.max(6, player.level), false);
        buildTreeOpen = false;
        prefs.edit().putString("last_branch", branch.name()).apply();
    }

    private Branch randomBranch() {
        int r = random.nextInt(3);
        return r == 0 ? Branch.RAIDER : r == 1 ? Branch.GUNNER : Branch.HUNTER;
    }

    private void configureForLevel(Ship ship, int level, boolean fullHeal) {
        float oldMax = ship.maxHp;
        ship.level = clampInt(level, 1, MAX_LEVEL);
        float baseHp = 80f + ship.level * 38f;
        float baseTurn = Math.max(1.05f, 2.55f - ship.level * 0.13f);
        if (ship.branch == Branch.RAIDER) {
            ship.maxHp = baseHp * 0.84f;
            ship.turnRate = baseTurn * 1.24f;
        } else if (ship.branch == Branch.GUNNER) {
            ship.maxHp = baseHp * 1.30f;
            ship.turnRate = baseTurn * 0.82f;
        } else if (ship.branch == Branch.HUNTER) {
            ship.maxHp = baseHp * 1.04f;
            ship.turnRate = baseTurn * 1.05f;
        } else {
            ship.maxHp = baseHp;
            ship.turnRate = baseTurn;
        }
        if (fullHeal || oldMax <= 0f) ship.hp = ship.maxHp;
        else ship.hp = Math.min(ship.maxHp, ship.hp + Math.max(18f, ship.maxHp - oldMax + 14f));
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
        ship.branch = Branch.BASE;

        if (ship.isPlayer) {
            ship.xp = 0;
            configureForLevel(ship, 1, true);
        } else {
            int level = random.nextFloat() < 0.12f ? 3 : random.nextFloat() < 0.35f ? 2 : 1;
            ship.xp = LEVEL_THRESHOLDS[level] + random.nextInt(30);
            configureForLevel(ship, level, true);
        }
    }

    private void safeRandomPosition(Ship ship) {
        for (int tries = 0; tries < 60; tries++) {
            float x = 180f + random.nextFloat() * (WORLD_W - 360f);
            float y = 180f + random.nextFloat() * (WORLD_H - 360f);
            if (collidingRock(x, y, 110f) == null) {
                ship.x = x;
                ship.y = y;
                return;
            }
        }
        ship.x = WORLD_W * 0.5f;
        ship.y = WORLD_H * 0.5f;
    }

    private void spawnRandomLoot() {
        for (int tries = 0; tries < 20; tries++) {
            Loot l = new Loot();
            l.x = 70f + random.nextFloat() * (WORLD_W - 140f);
            l.y = 70f + random.nextFloat() * (WORLD_H - 140f);
            if (collidingRock(l.x, l.y, 25f) != null) continue;
            l.value = 5 + random.nextInt(11);
            l.coinValue = 1 + random.nextInt(3);
            l.phase = random.nextFloat() * 6f;
            loot.add(l);
            return;
        }
    }

    private void drawGame(Canvas canvas) {
        int w = getWidth();
        int h = getHeight();
        if (w <= 0 || h <= 0) return;

        paint.setStyle(Paint.Style.FILL);
        paint.setColor(Color.rgb(24, 130, 170));
        canvas.drawRect(0, 0, w, h, paint);

        float zoom = clamp(1.06f - (player.level - 1) * 0.030f, 0.74f, 1.06f);
        canvas.save();
        canvas.translate(w * 0.5f, h * 0.5f);
        canvas.scale(zoom, zoom);
        canvas.translate(-player.x, -player.y);

        drawWater(canvas);
        drawWind(canvas);
        drawBorder(canvas);
        drawRocks(canvas);
        drawLoot(canvas);
        drawCannonballs(canvas);

        ArrayList<Ship> ordered = new ArrayList<>(ships);
        Collections.sort(ordered, (a, b) -> Float.compare(a.y, b.y));
        for (Ship ship : ordered) if (ship.alive) drawShip(canvas, ship);
        canvas.restore();

        drawHud(canvas);
        if (buildTreeOpen) drawBuildTree(canvas);
        if (adOverlayTimer > 0f) drawAdPlaceholder(canvas);
    }

    private void drawWater(Canvas canvas) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(2f);
        paint.setColor(Color.argb(52, 220, 247, 255));
        float left = Math.max(0f, player.x - 1000f);
        float right = Math.min(WORLD_W, player.x + 1000f);
        float top = Math.max(0f, player.y - 700f);
        float bottom = Math.min(WORLD_H, player.y + 700f);
        for (float x = (float) Math.floor(left / 180f) * 180f; x < right; x += 180f) {
            for (float y = (float) Math.floor(top / 150f) * 150f; y < bottom; y += 150f) {
                float wobble = (float) Math.sin(worldTime * 1.3f + x * 0.004f + y * 0.006f) * 8f;
                canvas.drawArc(new RectF(x - 22f + wobble, y - 7f, x + 22f + wobble, y + 14f), 205f, 130f, false, paint);
            }
        }
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawWind(Canvas canvas) {
        for (WindZone z : windZones) {
            paint.setColor(Color.argb(24, 232, 252, 255));
            canvas.drawCircle(z.x, z.y, z.radius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(4f);
            paint.setColor(Color.argb(105, 232, 252, 255));
            canvas.drawCircle(z.x, z.y, z.radius, paint);
            float ux = (float) Math.cos(z.angle);
            float uy = (float) Math.sin(z.angle);
            for (int i = -2; i <= 2; i++) {
                float px = z.x - uy * i * 65f;
                float py = z.y + ux * i * 65f;
                canvas.drawLine(px - ux * 100f, py - uy * 100f, px + ux * 100f, py + uy * 100f, paint);
            }
            paint.setStyle(Paint.Style.FILL);
        }
    }

    private void drawBorder(Canvas canvas) {
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(16f);
        paint.setColor(Color.argb(210, 214, 245, 247));
        canvas.drawRect(12f, 12f, WORLD_W - 12f, WORLD_H - 12f, paint);
        paint.setStyle(Paint.Style.FILL);
    }

    private void drawRocks(Canvas canvas) {
        for (Rock r : rocks) {
            paint.setColor(Color.argb(60, 0, 20, 28));
            canvas.drawOval(new RectF(r.x - r.radius * 1.15f, r.y + r.radius * 0.30f, r.x + r.radius * 1.15f, r.y + r.radius * 0.75f), paint);
            paint.setColor(Color.rgb(89, 101, 102));
            canvas.drawCircle(r.x, r.y, r.radius, paint);
            paint.setColor(Color.rgb(139, 147, 143));
            canvas.drawCircle(r.x - r.radius * 0.22f, r.y - r.radius * 0.25f, r.radius * 0.58f, paint);
        }
    }

    private void drawLoot(Canvas canvas) {
        for (Loot l : loot) {
            if (Math.abs(l.x - player.x) > 1250f || Math.abs(l.y - player.y) > 900f) continue;
            float bob = (float) Math.sin(worldTime * 2.2f + l.phase) * 3f;
            paint.setColor(Color.rgb(120, 70, 27));
            canvas.drawRoundRect(new RectF(l.x - 12f, l.y - 8f + bob, l.x + 12f, l.y + 9f + bob), 4f, 4f, paint);
            paint.setColor(Color.rgb(243, 190, 44));
            canvas.drawRect(l.x - 12f, l.y - 2f + bob, l.x + 12f, l.y + 2f + bob, paint);
        }
    }

    private void drawCannonballs(Canvas canvas) {
        for (Cannonball b : cannonballs) {
            paint.setColor(Color.rgb(29, 33, 36));
            canvas.drawCircle(b.x, b.y, 7f, paint);
        }
    }

    private void drawShip(Canvas canvas, Ship ship) {
        float r = ship.radius();
        canvas.save();
        canvas.translate(ship.x, ship.y);
        canvas.rotate((float) Math.toDegrees(ship.angle));

        paint.setColor(Color.argb(65, 0, 20, 28));
        canvas.drawOval(new RectF(-r * 1.05f, -r * 0.65f + 9f, r * 1.18f, r * 0.65f + 14f), paint);
        if (ship.windBoost) {
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(5f);
            paint.setColor(Color.argb(145, 225, 250, 255));
            canvas.drawLine(-r * 1.75f, -r * 0.22f, -r * 1.05f, -r * 0.12f, paint);
            canvas.drawLine(-r * 1.85f, r * 0.20f, -r * 1.05f, r * 0.10f, paint);
            paint.setStyle(Paint.Style.FILL);
        }

        path.reset();
        path.moveTo(r * 1.20f, 0f);
        path.lineTo(r * 0.46f, r * 0.68f);
        path.lineTo(-r * 0.92f, r * 0.56f);
        path.lineTo(-r * 1.10f, 0f);
        path.lineTo(-r * 0.92f, -r * 0.56f);
        path.lineTo(r * 0.46f, -r * 0.68f);
        path.close();
        paint.setColor(hullColor(ship));
        canvas.drawPath(path, paint);

        paint.setColor(Color.rgb(193, 139, 73));
        canvas.drawRoundRect(new RectF(-r * 0.78f, -r * 0.36f, r * 0.60f, r * 0.36f), 9f, 9f, paint);

        int visibleGuns = Math.min(6, ship.gunsPerSide());
        paint.setColor(Color.rgb(32, 34, 36));
        for (int i = 0; i < visibleGuns; i++) {
            float t = visibleGuns == 1 ? 0f : i / (float) (visibleGuns - 1) - 0.5f;
            float gx = t * r * 1.20f - r * 0.06f;
            canvas.drawCircle(gx, -r * 0.57f, 4.4f, paint);
            canvas.drawCircle(gx, r * 0.57f, 4.4f, paint);
        }

        int masts = ship.level <= 2 ? 1 : ship.level <= 5 ? 2 : ship.level <= 8 ? 3 : 4;
        for (int i = 0; i < masts; i++) {
            float mx = masts == 1 ? 0f : -r * 0.48f + i * (r * 0.96f / (masts - 1));
            paint.setColor(Color.rgb(73, 46, 24));
            canvas.drawRect(mx - 3f, -r * 0.56f, mx + 3f, r * 0.56f, paint);
            paint.setColor(sailColor(ship));
            canvas.drawRoundRect(new RectF(mx - r * 0.10f, -r * 0.38f, mx + r * 0.25f, r * 0.38f), 5f, 5f, paint);
        }
        canvas.restore();

        float barW = r * 1.8f;
        float barY = ship.y - r - 26f;
        paint.setColor(Color.argb(150, 7, 22, 28));
        canvas.drawRoundRect(new RectF(ship.x - barW / 2f, barY, ship.x + barW / 2f, barY + 8f), 4f, 4f, paint);
        paint.setColor(ship.hp / ship.maxHp > 0.42f ? Color.rgb(69, 202, 94) : Color.rgb(232, 71, 61));
        canvas.drawRoundRect(new RectF(ship.x - barW / 2f, barY, ship.x - barW / 2f + barW * clamp(ship.hp / ship.maxHp, 0f, 1f), barY + 8f), 4f, 4f, paint);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextSize(17f + Math.min(5, ship.level) * 1.1f);
        paint.setColor(Color.WHITE);
        canvas.drawText(ship.name, ship.x, barY - 5f, paint);
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
    }

    private int hullColor(Ship ship) {
        if (!ship.isPlayer) {
            if (ship.role == Role.NAVY) return Color.rgb(47, 62, 94);
            if (ship.role == Role.MERCHANT) return Color.rgb(119, 83, 43);
            if (ship.branch == Branch.RAIDER) return Color.rgb(84, 43, 48);
            if (ship.branch == Branch.GUNNER) return Color.rgb(58, 63, 67);
            if (ship.branch == Branch.HUNTER) return Color.rgb(62, 82, 65);
            return Color.rgb(82, 46, 31);
        }
        switch (selectedSkin) {
            case 1: return Color.rgb(126, 35, 35);
            case 2: return Color.rgb(66, 82, 87);
            case 3: return Color.rgb(45, 67, 104);
            default: return Color.rgb(110, 61, 30);
        }
    }

    private int sailColor(Ship ship) {
        if (!ship.isPlayer) return ship.role == Role.NAVY ? Color.rgb(229, 236, 244) : Color.rgb(220, 207, 175);
        switch (selectedSkin) {
            case 1: return Color.rgb(72, 20, 26);
            case 2: return Color.rgb(211, 230, 230);
            case 3: return Color.rgb(245, 229, 174);
            default: return Color.rgb(245, 229, 191);
        }
    }

    private void drawHud(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float base = Math.min(w, h);
        float pad = base * 0.026f;

        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextSize(base * 0.052f);
        paint.setColor(Color.WHITE);
        canvas.drawText("PIRAT.IO", pad, pad + base * 0.045f, paint);

        paint.setTextSize(base * 0.025f);
        paint.setColor(Color.rgb(247, 223, 146));
        canvas.drawText("Lv. " + player.level + "  " + classTitle(player), pad, pad + base * 0.083f, paint);
        canvas.drawText("COINS  " + coins + "   •   100 BOTS", pad, pad + base * 0.118f, paint);

        float barW = base * 0.40f;
        float barH = base * 0.023f;
        drawBar(canvas, pad, pad + base * 0.137f, barW, barH, player.hp / player.maxHp, Color.rgb(63, 202, 91), "HULL");
        float progress;
        if (player.level >= MAX_LEVEL) progress = 1f;
        else {
            int from = LEVEL_THRESHOLDS[player.level];
            int to = LEVEL_THRESHOLDS[player.level + 1];
            progress = clamp((player.xp - from) / (float) Math.max(1, to - from), 0f, 1f);
        }
        drawBar(canvas, pad, pad + base * 0.176f, barW, barH, progress, Color.rgb(242, 181, 45), player.level >= MAX_LEVEL ? "MAX" : "INFAMY");

        drawLeaderboard(canvas, w, base, pad);
        drawControls(canvas, w, h, base);

        if (!player.alive || deathMessageTimer > 0f) {
            paint.setColor(Color.argb(145, 5, 17, 25));
            canvas.drawRect(0, h * 0.37f, w, h * 0.63f, paint);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(base * 0.078f);
            paint.setColor(Color.WHITE);
            canvas.drawText("SUNK!", w * 0.5f, h * 0.48f, paint);
            paint.setTextSize(base * 0.027f);
            paint.setColor(Color.rgb(244, 215, 130));
            canvas.drawText("Respawning as a Sloop...", w * 0.5f, h * 0.55f, paint);
        }
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
    }

    private void drawBar(Canvas canvas, float x, float y, float width, float height, float ratio, int color, String label) {
        paint.setColor(Color.argb(165, 8, 25, 34));
        canvas.drawRoundRect(new RectF(x, y, x + width, y + height), height / 2f, height / 2f, paint);
        paint.setColor(color);
        canvas.drawRoundRect(new RectF(x, y, x + width * clamp(ratio, 0f, 1f), y + height), height / 2f, height / 2f, paint);
        paint.setTextSize(height * 0.75f);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setColor(Color.WHITE);
        canvas.drawText(label, x + 7f, y + height - 3f, paint);
    }

    private void drawLeaderboard(Canvas canvas, float w, float base, float pad) {
        ArrayList<Ship> ranking = new ArrayList<>(ships);
        Collections.sort(ranking, (a, b) -> Integer.compare(b.xp, a.xp));
        float panelW = base * 0.45f;
        float rowH = base * 0.031f;
        float left = w - pad - panelW;
        float top = pad;
        paint.setColor(Color.argb(150, 6, 29, 39));
        canvas.drawRoundRect(new RectF(left, top, w - pad, top + rowH * 8.7f), 16f, 16f, paint);
        paint.setTextAlign(Paint.Align.LEFT);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextSize(base * 0.020f);
        paint.setColor(Color.rgb(248, 220, 130));
        canvas.drawText("TOP PIRATES", left + 12f, top + rowH * 0.82f, paint);
        for (int i = 0; i < Math.min(7, ranking.size()); i++) {
            Ship s = ranking.get(i);
            paint.setColor(s.isPlayer ? Color.rgb(255, 224, 88) : Color.WHITE);
            canvas.drawText(String.format(Locale.US, "%d. %-11s %4d", i + 1, trim(s.name, 11), s.xp), left + 12f, top + rowH * (i + 1.72f), paint);
        }
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
    }

    private void drawControls(Canvas canvas, float w, float h, float base) {
        float jr = joystickRadius();
        float cx = joystickActive ? joystickCx : jr * 1.45f;
        float cy = joystickActive ? joystickCy : h - jr * 1.38f;
        float kx = joystickActive ? joystickX : cx;
        float ky = joystickActive ? joystickY : cy;
        float dx = kx - cx;
        float dy = ky - cy;
        float d = (float) Math.sqrt(dx * dx + dy * dy);
        if (d > jr) { kx = cx + dx / d * jr; ky = cy + dy / d * jr; }
        paint.setColor(Color.argb(70, 255, 255, 255));
        canvas.drawCircle(cx, cy, jr, paint);
        paint.setColor(Color.argb(175, 11, 52, 67));
        canvas.drawCircle(kx, ky, jr * 0.42f, paint);

        float fr = jr * 0.88f;
        float fx = w - fr * 1.52f;
        float fy = h - fr * 1.48f;
        paint.setColor(Color.argb(fireHeld ? 220 : 170, 125, 42, 31));
        canvas.drawCircle(fx, fy, fr, paint);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextSize(base * 0.028f);
        paint.setColor(Color.WHITE);
        canvas.drawText("FIRE", fx, fy + base * 0.010f, paint);
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
    }

    private void drawBuildTree(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float base = Math.min(w, h);
        paint.setColor(Color.argb(225, 3, 17, 24));
        canvas.drawRect(0f, 0f, w, h, paint);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextSize(base * 0.062f);
        paint.setColor(Color.WHITE);
        canvas.drawText("CHOOSE YOUR BUILD", w * 0.5f, h * 0.16f, paint);
        paint.setTextSize(base * 0.025f);
        paint.setColor(Color.rgb(245, 215, 126));
        canvas.drawText("Level 5 complete — your choice shapes levels 6–10", w * 0.5f, h * 0.22f, paint);

        drawBranchCard(canvas, branchRect(0), Branch.RAIDER, "RAIDER", "Corsair → Sea Wraith → Phantom → Tempest → Blackwind", "+Speed  +Turn  +Reload\n-Hull  -Broadside weight", Color.rgb(125, 48, 55));
        drawBranchCard(canvas, branchRect(1), Branch.GUNNER, "GUNNER", "Gunship → Bombard → Dreadnought → Leviathan → Iron Fortress", "+Hull  +Cannons  +Damage\n-Speed  -Turn", Color.rgb(70, 76, 82));
        drawBranchCard(canvas, branchRect(2), Branch.HUNTER, "HUNTER", "Privateer → Marauder → Reaper → Executioner → Kraken Hunter", "+Reload  +Range  +Coins\nBalanced hull & speed", Color.rgb(62, 94, 70));
        paint.setTypeface(android.graphics.Typeface.DEFAULT);
    }

    private RectF branchRect(int index) {
        float w = getWidth();
        float h = getHeight();
        float gap = w * 0.025f;
        float leftMargin = w * 0.06f;
        float usable = w - leftMargin * 2f - gap * 2f;
        float cardW = usable / 3f;
        float left = leftMargin + index * (cardW + gap);
        return new RectF(left, h * 0.29f, left + cardW, h * 0.82f);
    }

    private void drawBranchCard(Canvas canvas, RectF r, Branch branch, String title, String pathText, String stats, int color) {
        float base = Math.min(getWidth(), getHeight());
        paint.setColor(color);
        canvas.drawRoundRect(r, 22f, 22f, paint);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeWidth(3f);
        paint.setColor(Color.argb(180, 245, 235, 205));
        canvas.drawRoundRect(r, 22f, 22f, paint);
        paint.setStyle(Paint.Style.FILL);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextSize(base * 0.038f);
        paint.setColor(Color.WHITE);
        canvas.drawText(title, r.centerX(), r.top + base * 0.070f, paint);
        paint.setTextSize(base * 0.019f);
        paint.setColor(Color.rgb(250, 221, 132));
        drawCenteredLines(canvas, pathText, r.centerX(), r.top + base * 0.125f, base * 0.028f);
        paint.setTextSize(base * 0.022f);
        paint.setColor(Color.WHITE);
        drawCenteredLines(canvas, stats, r.centerX(), r.top + base * 0.245f, base * 0.034f);
        paint.setTextSize(base * 0.023f);
        paint.setColor(Color.rgb(255, 226, 112));
        canvas.drawText("TAP TO CHOOSE", r.centerX(), r.bottom - base * 0.055f, paint);
    }

    private void drawCenteredLines(Canvas canvas, String text, float x, float y, float lineHeight) {
        String[] lines = text.split("\\n");
        for (int i = 0; i < lines.length; i++) canvas.drawText(lines[i], x, y + i * lineHeight, paint);
    }

    private void drawAdPlaceholder(Canvas canvas) {
        float w = getWidth();
        float h = getHeight();
        float base = Math.min(w, h);
        paint.setColor(Color.argb(235, 4, 13, 18));
        canvas.drawRect(0, 0, w, h, paint);
        paint.setTextAlign(Paint.Align.CENTER);
        paint.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        paint.setTextSize(base * 0.072f);
        paint.setColor(Color.WHITE);
        canvas.drawText("AD BREAK", w * 0.5f, h * 0.47f, paint);
        paint.setTextSize(base * 0.025f);
        paint.setColor(Color.rgb(245, 214, 125));
        canvas.drawText("Prototype interstitial • every 2–3 deaths", w * 0.5f, h * 0.55f, paint);
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
        if (buildTreeOpen) {
            if (action == MotionEvent.ACTION_DOWN) {
                if (branchRect(0).contains(x, y)) chooseBranch(Branch.RAIDER);
                else if (branchRect(1).contains(x, y)) chooseBranch(Branch.GUNNER);
                else if (branchRect(2).contains(x, y)) chooseBranch(Branch.HUNTER);
            }
            return true;
        }

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
                if (event.getPointerId(i) == joystickPointer) {
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

    private String classTitle(Ship ship) {
        if (ship.level <= 5 || ship.branch == Branch.BASE) return BASE_TITLES[Math.min(5, ship.level)];
        if (ship.branch == Branch.RAIDER) return RAIDER_TITLES[ship.level];
        if (ship.branch == Branch.GUNNER) return GUNNER_TITLES[ship.level];
        return HUNTER_TITLES[ship.level];
    }

    private String trim(String name, int max) {
        return name.length() <= max ? name : name.substring(0, max - 1) + "…";
    }

    private float joystickRadius() {
        return Math.max(62f, Math.min(getWidth(), getHeight()) * 0.105f);
    }

    private static float rotateTowards(float current, float target, float maxDelta) {
        float delta = normalizeAngle(target - current);
        return normalizeAngle(current + clamp(delta, -maxDelta, maxDelta));
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

    private static float clamp(float v, float min, float max) {
        return Math.max(min, Math.min(max, v));
    }

    private static int clampInt(int v, int min, int max) {
        return Math.max(min, Math.min(max, v));
    }

    private enum Role { MERCHANT, PIRATE, NAVY }
    private enum Branch { BASE, RAIDER, GUNNER, HUNTER }

    private static class Ship {
        String name;
        final boolean isPlayer;
        final Role role;
        Branch branch = Branch.BASE;
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
            float base = 29f + level * 7.2f;
            if (branch == Branch.RAIDER) base *= 0.90f;
            if (branch == Branch.GUNNER) base *= 1.13f;
            return base;
        }

        float baseSpeed() {
            float v = 191f - level * 6.1f;
            if (role == Role.MERCHANT) v += 14f;
            if (role == Role.NAVY) v += 5f;
            if (branch == Branch.RAIDER) v *= 1.20f;
            else if (branch == Branch.GUNNER) v *= 0.82f;
            else if (branch == Branch.HUNTER) v *= 1.03f;
            return v;
        }

        int gunsPerSide() {
            int guns = 1 + (level - 1) / 2;
            if (branch == Branch.GUNNER) guns += 2;
            if (branch == Branch.RAIDER && level >= 8) guns = Math.max(2, guns - 1);
            return Math.min(7, guns);
        }

        float reloadTime() {
            float t = Math.max(0.45f, 0.93f - level * 0.045f);
            if (branch == Branch.RAIDER) t *= 0.78f;
            else if (branch == Branch.GUNNER) t *= 1.08f;
            else if (branch == Branch.HUNTER) t *= 0.86f;
            return t;
        }

        float shotDamage() {
            float d = 14f + level * 2.5f;
            if (branch == Branch.GUNNER) d *= 1.22f;
            else if (branch == Branch.RAIDER) d *= 0.92f;
            else if (branch == Branch.HUNTER) d *= 1.08f;
            return d;
        }

        float projectileLife() {
            return branch == Branch.HUNTER ? 1.52f : 1.30f;
        }

        float shotRange() {
            return branch == Branch.HUNTER ? 710f : 620f;
        }

        float coinMultiplier() {
            return branch == Branch.HUNTER ? 1.35f : 1f;
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
