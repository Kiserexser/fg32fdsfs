package com.example.speed;

import net.fabricmc.api.ModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientTickEvents;
import net.fabricmc.fabric.api.client.keybinding.v1.KeyBindingHelper;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.entity.LivingEntity;
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

    private static boolean enabled = false;
    private static final Random random = new Random();
    private long lastAttackTime = 0;
    private long lastJumpTime = 0;

    // === НАСТРОЙКИ ===
    private static final double SEARCH_RANGE = 4.5;          // дальность поиска
    private static final double ATTACK_RANGE = 3.5;          // дальность атаки
    private static final double MIN_DELAY = 0.620;           // 620 мс
    private static final double MAX_DELAY = 0.630;           // 630 мс (центр ~0.625)
    private static final float SMOOTH_SPEED = 0.18f;         // было 0.15, увеличили на 20%
    private static final boolean SPRINT_RESET = true;

    // === Смещение ===
    private static final boolean ENABLE_SHIFT = true;
    private static final float SHIFT_DEGREES = 0.5f;
    private static final long SHIFT_DURATION_MS = 3000;
    private static final long RETURN_DURATION_MS = 2000;

    // === Джиттер ===
    private static final float JITTER_RANGE = 0.15f;

    private float targetYaw = 0;
    private float targetPitch = 0;
    private long shiftCycleStart = System.currentTimeMillis();
    private boolean isShiftPhase = true;
    private LivingEntity lockedTarget = null;

    private KeyBinding toggleKey;

    @Override
    public void onInitialize() {
        LOGGER.info("SpeedMod KillAura with auto-crits loaded. Press R to toggle.");

        toggleKey = KeyBindingHelper.registerKeyBinding(new KeyBinding(
                "key.speedmod.toggle",
                InputUtil.Type.KEYSYM,
                GLFW.GLFW_KEY_R,
                "category.speedmod"
        ));

        ClientTickEvents.END_CLIENT_TICK.register(client -> {
            try {
                // Переключение
                if (toggleKey.wasPressed()) {
                    enabled = !enabled;
                    if (!enabled) lockedTarget = null;
                    LOGGER.info("KillAura: " + (enabled ? "ON" : "OFF"));
                }

                if (!enabled) return;
                if (client == null || client.player == null || client.world == null) return;

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
                    double dist = client.player.distanceTo(lockedTarget);
                    if (dist <= SEARCH_RANGE) {
                        target = lockedTarget;
                    }
                }

                if (target == null) {
                    lockedTarget = getTarget(client);
                    target = lockedTarget;
                }

                if (target == null) return;

                double dist = client.player.distanceTo(target);
                if (dist > SEARCH_RANGE) {
                    lockedTarget = null;
                    return;
                }

                // ---- АВТО-КРИТ (прыжок перед атакой) ----
                // Если цель в радиусе атаки, игрок на земле и прошло достаточно времени с последнего прыжка
                if (dist <= ATTACK_RANGE && client.player.isOnGround() && (now - lastJumpTime) > 200) {
                    client.player.jump();
                    lastJumpTime = now;
                    // Сброс спринта перед прыжком (для легитности)
                    if (SPRINT_RESET && client.player.isSprinting()) {
                        client.player.setSprinting(false);
                    }
                }

                // ---- Ротация ----
                Vec3d eyePos = client.player.getEyePos();
                Vec3d targetPos = target.getPos().add(0, target.getHeight() * 0.5, 0);

                double dx = targetPos.x - eyePos.x;
                double dy = targetPos.y - eyePos.y;
                double dz = targetPos.z - eyePos.z;
                double distance = Math.sqrt(dx * dx + dz * dz);

                float yaw = (float) MathHelper.atan2(dz, dx) * (180F / (float) Math.PI) - 90F;
                float pitch = (float) -MathHelper.atan2(dy, distance) * (180F / (float) Math.PI);

                // Джиттер
                float jitterYaw = (random.nextFloat() - 0.5f) * JITTER_RANGE * 2;
                float jitterPitch = (random.nextFloat() - 0.5f) * JITTER_RANGE * 2;

                float shift = 0f;
                if (ENABLE_SHIFT && isShiftPhase) {
                    shift = SHIFT_DEGREES;
                }

                targetYaw = yaw + jitterYaw;
                targetPitch = pitch + jitterPitch + shift;

                // Плавная ротация (с новой скоростью)
                float currentYaw = client.player.getYaw();
                float currentPitch = client.player.getPitch();
                float newYaw = lerpAngle(currentYaw, targetYaw, SMOOTH_SPEED);
                float newPitch = lerpAngle(currentPitch, targetPitch, SMOOTH_SPEED);

                client.player.setYaw(newYaw);
                client.player.setPitch(newPitch);

                // ---- АТАКА ----
                // Проверяем, что игрок в воздухе (крит возможен) и дистанция <= ATTACK_RANGE
                boolean canCrit = !client.player.isOnGround() && !client.player.isTouchingWater() && !client.player.isClimbing();
                long now2 = System.currentTimeMillis();
                double delay = MIN_DELAY + (MAX_DELAY - MIN_DELAY) * random.nextDouble();

                if (now2 - lastAttackTime >= (long)(delay * 1000) && target.isAlive() && dist <= ATTACK_RANGE) {
                    // Если крит не возможен, но мы уже прыгнули, может быть ещё в воздухе – атакуем
                    // В любом случае атакуем только если прошла задержка
                    // Сброс спринта перед атакой (дополнительно)
                    if (SPRINT_RESET && client.player.isSprinting()) {
                        client.player.setSprinting(false);
                    }
                    client.interactionManager.attackEntity(client.player, target);
                    client.player.swingHand(client.player.getActiveHand());
                    lastAttackTime = now2;
                }

            } catch (Exception e) {
                LOGGER.error("KillAura error", e);
            }
        });
    }

    private LivingEntity getTarget(MinecraftClient client) {
        try {
            Box box = client.player.getBoundingBox().expand(SEARCH_RANGE);
            List<LivingEntity> entities = client.world.getEntitiesByClass(LivingEntity.class, box,
                    e -> e != client.player && e.isAlive() && !e.isDead());
            entities.sort(Comparator.comparingDouble(e -> client.player.distanceTo(e)));
            return entities.isEmpty() ? null : entities.get(0);
        } catch (Exception e) {
            return null;
        }
    }

    private float lerpAngle(float from, float to, float speed) {
        float diff = to - from;
        diff = (diff % 360 + 540) % 360 - 180;
        float step = diff * speed;
        if (Math.abs(step) > Math.abs(diff)) step = diff;
        return from + step;
    }
}
