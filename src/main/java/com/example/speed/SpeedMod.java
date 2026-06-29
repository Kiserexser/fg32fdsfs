package com.example.speed;

import net.fabricmc.api.ModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.text.Text;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Comparator;
import java.util.List;
import java.util.Random;

public class SpeedMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("speedmod");
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final Random random = new Random();

    private static boolean enabled = false;
    private static boolean lastKeyState = false;
    private long lastAttackTime = 0;

    // === НАСТРОЙКИ (оптимальные для обхода) ===
    private static final double SEARCH_RANGE = 4.5;
    private static final double ATTACK_RANGE = 3.5;
    private static final double MIN_DELAY = 0.620;
    private static final double MAX_DELAY = 0.630;
    private static final float SMOOTH_SPEED = 0.20f;      // не слишком медленно
    private static final boolean SPRINT_RESET = true;

    // === Смещение ===
    private static final boolean ENABLE_SHIFT = true;
    private static final float SHIFT_DEGREES = 0.5f;
    private static final long SHIFT_DURATION_MS = 3000;
    private static final long RETURN_DURATION_MS = 2000;

    // === Джиттер (умеренный) ===
    private static final float JITTER_RANGE = 0.12f;

    private float targetYaw = 0;
    private float targetPitch = 0;
    private long shiftCycleStart = System.currentTimeMillis();
    private boolean isShiftPhase = true;
    private LivingEntity lockedTarget = null;

    @Override
    public void onInitialize() {
        LOGGER.info("KillAura (optimized for anti-cheat) loaded. Press R to toggle.");

        new Thread(() -> {
            while (true) {
                try { Thread.sleep(10); } catch (InterruptedException ignored) {}

                mc.execute(() -> {
                    try {
                        if (mc.getWindow() == null || mc.player == null || mc.world == null) return;
                        long window = mc.getWindow().getHandle();

                        boolean current = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_R) == GLFW.GLFW_PRESS;
                        if (current && !lastKeyState) {
                            enabled = !enabled;
                            if (!enabled) lockedTarget = null;
                            mc.player.sendMessage(Text.literal(enabled ? "§aKillAura ON" : "§cKillAura OFF"), true);
                            LOGGER.info("KillAura: " + (enabled ? "ON" : "OFF"));
                        }
                        lastKeyState = current;

                        if (!enabled) return;

                        // Обновление фазы смещения
                        long now = System.currentTimeMillis();
                        long elapsed = now - shiftCycleStart;
                        if (isShiftPhase && elapsed >= SHIFT_DURATION_MS) {
                            isShiftPhase = false;
                            shiftCycleStart = now;
                        } else if (!isShiftPhase && elapsed >= RETURN_DURATION_MS) {
                            isShiftPhase = true;
                            shiftCycleStart = now;
                        }

                        // Поиск цели
                        LivingEntity target = null;
                        if (lockedTarget != null && lockedTarget.isAlive() && !lockedTarget.isDead()) {
                            double dist = mc.player.distanceTo(lockedTarget);
                            if (dist <= SEARCH_RANGE) {
                                target = lockedTarget;
                            }
                        }

                        if (target == null) {
                            lockedTarget = getTarget();
                            target = lockedTarget;
                        }

                        if (target == null) return;

                        double dist = mc.player.distanceTo(target);
                        if (dist > SEARCH_RANGE) {
                            lockedTarget = null;
                            return;
                        }

                        // Проверка видимости (raycast)
                        HitResult hit = mc.player.raycast(ATTACK_RANGE, 1.0f, false);
                        boolean canHit = hit instanceof EntityHitResult && ((EntityHitResult) hit).getEntity() == target;

                        // ---- Ротация с естественным шумом ----
                        Vec3d eyePos = mc.player.getEyePos();
                        // Случайное смещение цели по вертикали (чтобы не бить всегда в центр)
                        double heightOffset = (random.nextDouble() - 0.5) * 0.2; // ±0.1 блока
                        Vec3d targetPos = target.getPos().add(0, target.getHeight() * (0.5 + heightOffset), 0);

                        double dx = targetPos.x - eyePos.x;
                        double dy = targetPos.y - eyePos.y;
                        double dz = targetPos.z - eyePos.z;
                        double distance = Math.sqrt(dx * dx + dz * dz);

                        float yaw = (float) MathHelper.atan2(dz, dx) * (180F / (float) Math.PI) - 90F;
                        float pitch = (float) -MathHelper.atan2(dy, distance) * (180F / (float) Math.PI);

                        // Джиттер (естественное дрожание)
                        float jitterYaw = (random.nextFloat() - 0.5f) * JITTER_RANGE * 2;
                        float jitterPitch = (random.nextFloat() - 0.5f) * JITTER_RANGE * 2;

                        float shift = 0f;
                        if (ENABLE_SHIFT && isShiftPhase) {
                            shift = SHIFT_DEGREES;
                        }

                        targetYaw = yaw + jitterYaw;
                        targetPitch = pitch + jitterPitch + shift;

                        float currentYaw = mc.player.getYaw();
                        float currentPitch = mc.player.getPitch();
                        float newYaw = lerpAngle(currentYaw, targetYaw, SMOOTH_SPEED);
                        float newPitch = lerpAngle(currentPitch, targetPitch, SMOOTH_SPEED);

                        mc.player.setYaw(newYaw);
                        mc.player.setPitch(newPitch);

                        // ---- Атака ----
                        long now2 = System.currentTimeMillis();
                        double delay = MIN_DELAY + (MAX_DELAY - MIN_DELAY) * random.nextDouble();

                        if (canHit && now2 - lastAttackTime >= (long)(delay * 1000) && target.isAlive() && dist <= ATTACK_RANGE) {
                            if (SPRINT_RESET && mc.player.isSprinting()) {
                                mc.player.setSprinting(false);
                            }
                            mc.interactionManager.attackEntity(mc.player, target);
                            mc.player.swingHand(mc.player.getActiveHand());
                            lastAttackTime = now2;
                        }

                    } catch (Exception e) {
                        LOGGER.error("KillAura error", e);
                    }
                });
            }
        }).start();
    }

    private LivingEntity getTarget() {
        if (mc.player == null || mc.world == null) return null;
        Box box = mc.player.getBoundingBox().expand(SEARCH_RANGE);
        List<LivingEntity> entities = mc.world.getEntitiesByClass(LivingEntity.class, box,
                e -> e != mc.player && e.isAlive() && !e.isDead());
        entities.sort(Comparator.comparingDouble(e -> mc.player.distanceTo(e)));
        return entities.isEmpty() ? null : entities.get(0);
    }

    private float lerpAngle(float from, float to, float speed) {
        float diff = to - from;
        diff = (diff % 360 + 540) % 360 - 180;
        float step = diff * speed;
        if (Math.abs(step) > Math.abs(diff)) step = diff;
        return from + step;
    }
}
