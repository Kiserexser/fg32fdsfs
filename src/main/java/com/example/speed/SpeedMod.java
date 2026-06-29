package com.example.speed;

import net.fabricmc.api.ModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.text.Text;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Random;

public class SpeedMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("speedmod");
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final Random random = new Random();

    private static boolean enabled = false;
    private static boolean lastKeyState = false;
    private static long lastHitTime = 0;
    private static int hitCounter = 0;
    private static boolean wasAttacking = false;

    private static final double SEARCH_RANGE = 5.0;
    private static final double ATTACK_RANGE = 3.5;
    private static final long COOLDOWN_MS = 535;

    @Override
    public void onInitialize() {
        LOGGER.info("KillAura (360° rotation, only crits) loaded. Press R to toggle.");

        new Thread(() -> {
            while (true) {
                try { Thread.sleep(10); } catch (InterruptedException ignored) {}

                mc.execute(() -> {
                    if (mc.getWindow() == null || mc.player == null || mc.world == null) return;
                    long window = mc.getWindow().getHandle();

                    boolean current = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_R) == GLFW.GLFW_PRESS;
                    if (current && !lastKeyState) {
                        enabled = !enabled;
                        if (enabled) {
                            mc.player.sendMessage(Text.literal("§aKillAura ON"), true);
                        } else {
                            mc.player.sendMessage(Text.literal("§cKillAura OFF"), true);
                        }
                        LOGGER.info("KillAura: " + (enabled ? "ON" : "OFF"));
                    }
                    lastKeyState = current;

                    if (!enabled) return;

                    PlayerEntity target = getTargetPlayer();
                    if (target == null) return;

                    double dist = mc.player.distanceTo(target);
                    if (dist > SEARCH_RANGE || dist < 1.0) return;

                    boolean canCrit = isCritPossible();

                    float[] idealAngles = getAnglesTo(target);
                    float idealYaw = idealAngles[0];
                    float idealPitch = idealAngles[1];

                    float currentYaw = mc.player.getYaw();
                    float currentPitch = mc.player.getPitch();
                    long now = System.currentTimeMillis();

                    boolean canAttack = canCrit && (now - lastHitTime >= COOLDOWN_MS) && dist <= ATTACK_RANGE;
                    boolean isNewHit = canAttack && !wasAttacking;
                    if (isNewHit) {
                        hitCounter++;
                        lastHitTime = now;
                    }
                    wasAttacking = canAttack;

                    float[] newAngles = FunTimeRotation.compute(
                            currentYaw, currentPitch,
                            idealYaw, idealPitch,
                            canAttack, now,
                            hitCounter, lastHitTime, isNewHit
                    );

                    mc.player.setYaw(newAngles[0]);
                    mc.player.setPitch(newAngles[1]);

                    if (canAttack && isNewHit) {
                        if (mc.player.isSprinting()) {
                            mc.player.setSprinting(false);
                        }
                        mc.interactionManager.attackEntity(mc.player, target);
                        mc.player.swingHand(mc.player.getActiveHand());
                    }
                });
            }
        }).start();
    }

    private static PlayerEntity getTargetPlayer() {
        if (mc.player == null || mc.world == null) return null;
        Box box = mc.player.getBoundingBox().expand(SEARCH_RANGE);
        // В Yarn 1.21.4 world.getPlayers() возвращает List<AbstractClientPlayerEntity>, но мы можем работать с PlayerEntity
        List<? extends PlayerEntity> players = mc.world.getPlayers();
        PlayerEntity closest = null;
        double closestDist = Double.MAX_VALUE;
        for (PlayerEntity player : players) {
            if (player == mc.player || player.isDead() || !player.isAlive()) continue;
            double d = mc.player.distanceTo(player);
            if (d < closestDist && d <= SEARCH_RANGE) {
                closestDist = d;
                closest = player;
            }
        }
        return closest;
    }

    private static float[] getAnglesTo(LivingEntity target) {
        Vec3d eye = mc.player.getEyePos();
        Vec3d targetPos = target.getPos().add(0, target.getHeight() * 0.5, 0);
        double dx = targetPos.x - eye.x;
        double dy = targetPos.y - eye.y;
        double dz = targetPos.z - eye.z;
        double hDist = Math.sqrt(dx * dx + dz * dz);
        float yaw = (float) (MathHelper.atan2(dz, dx) * 180f / Math.PI) - 90f;
        float pitch = (float) (-MathHelper.atan2(dy, hDist) * 180f / Math.PI);
        return new float[]{yaw, pitch};
    }

    private static boolean isCritPossible() {
        if (mc.player == null) return false;
        return !mc.player.isOnGround()
                && !mc.player.isTouchingWater()
                && !mc.player.isClimbing()
                && !mc.player.isRiding();
    }

    // ============== Вложенный класс FunTimeRotation ==============
    public static class FunTimeRotation {
        public static float[] compute(float currentYaw, float currentPitch,
                                      float targetYaw, float targetPitch,
                                      boolean canAttack, long nowMs,
                                      int hitCounter, long lastHitTime, boolean isNewHit) {
            float deltaYaw = wrapTo180(targetYaw - currentYaw);
            float deltaPitch = wrapTo180(targetPitch - currentPitch);
            float total = (float) Math.hypot(deltaYaw, deltaPitch);

            if (total < 0.001f) {
                return new float[]{currentYaw, currentPitch};
            }

            float maxStepYaw = (Math.abs(deltaYaw) / total) * 130f;
            float maxStepPitch = (Math.abs(deltaPitch) / total) * 130f;

            float stepYaw = clamp(deltaYaw, -maxStepYaw, maxStepYaw);
            float stepPitch = clamp(deltaPitch, -maxStepPitch, maxStepPitch);

            float nextYaw = currentYaw + stepYaw;
            float nextPitch = currentPitch + stepPitch;

            if (canAttack) {
                nextYaw = lerp(0.85f, currentYaw, nextYaw);
                nextPitch = lerp(0.85f, currentPitch, nextPitch);

                if (isNewHit && hitCounter % 86 == 0 && (nowMs - lastHitTime) < 250) {
                    nextPitch = -90f;
                }
            } else {
                long sinceLastHit = nowMs - lastHitTime;
                if (sinceLastHit >= 535) {
                    float shakeYaw = (18f + (float) Math.random() * 10f) * (float) Math.sin(nowMs / 60.0);
                    float shakePitch = (6f + (float) Math.random() * 10f) * (float) Math.cos(nowMs / 60.0);
                    nextYaw = clamp(currentYaw + shakeYaw, currentYaw - 45f, currentYaw + 45f);
                    nextPitch = clamp(currentPitch + shakePitch, currentPitch - 45f, currentPitch + 45f);
                } else {
                    nextYaw = currentYaw;
                    nextPitch = currentPitch;
                }
            }

            nextPitch = clamp(nextPitch, -89f, 90f);

            // GCD Snap
            float sens = (float) mc.options.getMouseSensitivity();
            nextYaw = GCDUtil.gcdSnap(nextYaw, sens);
            nextPitch = GCDUtil.gcdSnap(nextPitch, sens);

            return new float[]{nextYaw, nextPitch};
        }

        private static float wrapTo180(float v) {
            v %= 360;
            if (v >= 180) v -= 360;
            if (v < -180) v += 360;
            return v;
        }

        private static float clamp(float v, float min, float max) {
            return Math.min(max, Math.max(min, v));
        }

        private static float lerp(float t, float a, float b) {
            return a + t * (b - a);
        }
    }

    // ============== GCD Util ==============
    public static class GCDUtil {
        private static final float[] SENS_VALUES = {
                0.0f, 0.1f, 0.2f, 0.3f, 0.4f, 0.5f, 0.6f, 0.7f, 0.8f, 0.9f, 1.0f,
                1.1f, 1.2f, 1.3f, 1.4f, 1.5f, 1.6f, 1.7f, 1.8f, 1.9f, 2.0f
        };
        private static final float[] GCD_VALUES = {
                0.0f, 0.02f, 0.04f, 0.06f, 0.08f, 0.1f, 0.12f, 0.14f, 0.16f, 0.18f, 0.2f,
                0.22f, 0.24f, 0.26f, 0.28f, 0.3f, 0.32f, 0.34f, 0.36f, 0.38f, 0.4f
        };

        public static float gcdSnap(float angle, float sens) {
            float gcd = getGCD(sens);
            return Math.round(angle / gcd) * gcd;
        }

        private static float getGCD(float sens) {
            int index = (int) (sens * 10);
            if (index < 0) index = 0;
            if (index >= SENS_VALUES.length - 1) index = SENS_VALUES.length - 2;
            float t = (sens - SENS_VALUES[index]) / (SENS_VALUES[index + 1] - SENS_VALUES[index]);
            return GCD_VALUES[index] + t * (GCD_VALUES[index + 1] - GCD_VALUES[index]);
        }
    }
}
