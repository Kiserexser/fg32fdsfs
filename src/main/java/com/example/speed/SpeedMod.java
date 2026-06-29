package com.example.speed;

import net.fabricmc.api.ModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.entity.LivingEntity;
import net.minecraft.network.packet.c2s.play.ClientCommandC2SPacket;
import net.minecraft.network.packet.c2s.play.PlayerMoveC2SPacket;
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
import java.util.concurrent.CopyOnWriteArrayList;

public class SpeedMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("speedmod");
    private static final MinecraftClient mc = MinecraftClient.getInstance();
    private static final Random random = new Random();

    // === Состояние клавиш (для работы в потоке) ===
    private boolean lastR = false;
    private boolean lastZ = false;
    private boolean lastShift = false;

    // === Базовый модуль ===
    public static abstract class Module {
        private final String name;
        private boolean enabled = false;

        public Module(String name) {
            this.name = name;
        }

        public String getName() { return name; }
        public boolean isEnabled() { return enabled; }
        public void toggle() {
            enabled = !enabled;
            if (enabled) onEnable();
            else onDisable();
        }
        public void setEnabled(boolean e) {
            enabled = e;
            if (e) onEnable();
            else onDisable();
        }
        public abstract void onEnable();
        public abstract void onDisable();
        public abstract void onTick();
    }

    // === Модуль NoFall ===
    public static class NoFallModule extends Module {
        private int fallTicks = 0;
        private long lastPacketTime = 0;

        public NoFallModule() {
            super("NoFall");
        }

        @Override
        public void onEnable() {
            fallTicks = 0;
            LOGGER.info("NoFall enabled");
        }

        @Override
        public void onDisable() {
            LOGGER.info("NoFall disabled");
        }

        @Override
        public void onTick() {
            if (mc.player == null || !isEnabled()) return;

            double currentY = mc.player.getY();
            double motionY = mc.player.getVelocity().y;

            if (motionY < -0.5 && !mc.player.isOnGround()) {
                fallTicks++;
                if (fallTicks % 3 == 0) {
                    double offsetY = 0.05 + (random.nextDouble() - 0.5) * 0.02;
                    double newY = currentY + offsetY;
                    mc.getNetworkHandler().sendPacket(
                            new PlayerMoveC2SPacket.PositionAndOnGround(
                                    mc.player.getX(), newY, mc.player.getZ(), false, true
                            )
                    );
                    mc.player.fallDistance = 0;
                }
            } else {
                fallTicks = 0;
            }

            if (mc.player.isOnGround() && fallTicks > 5) {
                if (System.currentTimeMillis() - lastPacketTime > 150) {
                    mc.getNetworkHandler().sendPacket(
                            new ClientCommandC2SPacket(mc.player, ClientCommandC2SPacket.Mode.START_FALL_FLYING)
                    );
                    lastPacketTime = System.currentTimeMillis();
                    fallTicks = 0;
                }
            }

            if (mc.player.fallDistance > 1.0f) {
                mc.player.fallDistance = 0;
            }
        }
    }

    // === Модуль KillAura ===
    public static class KillAuraModule extends Module {
        private long lastHitTime = 0;
        private LivingEntity lockedTarget = null;
        private float targetYaw = 0, targetPitch = 0;
        private long shiftCycleStart = System.currentTimeMillis();
        private boolean isShiftPhase = true;

        // Настройки
        private static final double SEARCH_RANGE = 5.0;
        private static final double ATTACK_RANGE = 3.0;
        private static final double FIXED_DELAY = 0.625;
        private static final float SMOOTH_SPEED = 0.20f;
        private static final float SHIFT_DEGREES = 0.5f;
        private static final long SHIFT_DURATION_MS = 3000;
        private static final long RETURN_DURATION_MS = 2000;
        private static final float JITTER_RANGE = 0.12f;

        public KillAuraModule() {
            super("KillAura");
        }

        @Override
        public void onEnable() {
            lockedTarget = null;
            LOGGER.info("KillAura enabled");
        }

        @Override
        public void onDisable() {
            lockedTarget = null;
            LOGGER.info("KillAura disabled");
        }

        @Override
        public void onTick() {
            if (mc.player == null || mc.world == null || !isEnabled()) return;
            long now = System.currentTimeMillis();

            // Сдвиг фазы смещения
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
                if (dist <= SEARCH_RANGE) target = lockedTarget;
            }
            if (target == null) {
                lockedTarget = getTarget();
                target = lockedTarget;
            }
            if (target == null) return;

            double dist = mc.player.distanceTo(target);
            if (dist > SEARCH_RANGE || dist < 0.5) {
                lockedTarget = null;
                return;
            }

            // Вычисление углов с джиттером и смещением
            Vec3d eyePos = mc.player.getEyePos();
            double heightOffset = (random.nextDouble() - 0.5) * 0.2;
            Vec3d targetPos = target.getPos().add(0, target.getHeight() * (0.5 + heightOffset), 0);

            double dx = targetPos.x - eyePos.x;
            double dy = targetPos.y - eyePos.y;
            double dz = targetPos.z - eyePos.z;
            double distance = Math.sqrt(dx * dx + dz * dz);

            float yaw = (float) MathHelper.atan2(dz, dx) * (180F / (float) Math.PI) - 90F;
            float pitch = (float) -MathHelper.atan2(dy, distance) * (180F / (float) Math.PI);

            float jitterYaw = (random.nextFloat() - 0.5f) * JITTER_RANGE * 2;
            float jitterPitch = (random.nextFloat() - 0.5f) * JITTER_RANGE * 2;
            float shift = isShiftPhase ? SHIFT_DEGREES : 0;

            targetYaw = yaw + jitterYaw;
            targetPitch = pitch + jitterPitch + shift;

            // Плавное наведение
            float currentYaw = mc.player.getYaw();
            float currentPitch = mc.player.getPitch();
            mc.player.setYaw(lerpAngle(currentYaw, targetYaw, SMOOTH_SPEED));
            mc.player.setPitch(lerpAngle(currentPitch, targetPitch, SMOOTH_SPEED));

            // Проверка видимости (raycast)
            HitResult hit = mc.player.raycast(ATTACK_RANGE, 1.0f, false);
            boolean canHit = hit instanceof EntityHitResult && ((EntityHitResult) hit).getEntity() == target;

            // Атака
            if (canHit && now - lastHitTime >= (long) (FIXED_DELAY * 1000) && target.isAlive() && dist <= ATTACK_RANGE) {
                if (mc.player.isSprinting()) mc.player.setSprinting(false);
                mc.interactionManager.attackEntity(mc.player, target);
                mc.player.swingHand(mc.player.getActiveHand());
                lastHitTime = now;
            }
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

    // === Список модулей ===
    private static final List<Module> modules = new CopyOnWriteArrayList<>();

    // === ClickGUI ===
    public static class ClickGuiScreen extends Screen {
        private static final int BG_COLOR = 0xCC1A1A1A;
        private static final int BUTTON_COLOR = 0xCC333333;
        private static final int BUTTON_HOVER = 0xCC555555;
        private static final int TEXT_COLOR = 0xFFFFFFFF;

        public ClickGuiScreen() {
            super(Text.literal("ClickGUI"));
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            context.fill(0, 0, this.width, this.height, 0x88000000);

            int totalHeight = modules.size() * 30 + 30;
            int x = (this.width - 200) / 2;
            int y = (this.height - totalHeight) / 2;
            int width = 200;

            context.fill(x, y, x + width, y + totalHeight, BG_COLOR);
            context.drawText(mc.textRenderer, "§lModules", x + 10, y + 10, 0xFFFFFF, false);

            int buttonY = y + 30;
            for (Module m : modules) {
                int buttonX = x + 10;
                int buttonWidth = width - 20;
                int buttonHeight = 24;

                boolean hovered = mouseX >= buttonX && mouseX <= buttonX + buttonWidth &&
                        mouseY >= buttonY && mouseY <= buttonY + buttonHeight;

                int color = hovered ? BUTTON_HOVER : BUTTON_COLOR;
                context.fill(buttonX, buttonY, buttonX + buttonWidth, buttonY + buttonHeight, color);

                String status = m.isEnabled() ? "§aON" : "§cOFF";
                context.drawText(mc.textRenderer, m.getName() + " " + status, buttonX + 5, buttonY + 6, TEXT_COLOR, false);

                buttonY += buttonHeight + 4;
            }
        }

        @Override
        public boolean mouseClicked(double mouseX, double mouseY, int button) {
            if (button == 0) {
                int x = (this.width - 200) / 2 + 10;
                int y = (this.height - (modules.size() * 30 + 30)) / 2 + 30;
                int w = 180;
                int h = 24;

                for (Module m : modules) {
                    if (mouseX >= x && mouseX <= x + w && mouseY >= y && mouseY <= y + h) {
                        m.toggle();
                        mc.player.sendMessage(Text.literal(m.getName() + " " + (m.isEnabled() ? "ON" : "OFF")), true);
                        return true;
                    }
                    y += h + 4;
                }
            }
            return super.mouseClicked(mouseX, mouseY, button);
        }

        @Override
        public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
            if (keyCode == GLFW.GLFW_KEY_ESCAPE || keyCode == GLFW.GLFW_KEY_RIGHT_SHIFT) {
                this.close();
                return true;
            }
            return super.keyPressed(keyCode, scanCode, modifiers);
        }

        @Override
        public void close() {
            super.close();
            mc.setScreen(null);
        }

        @Override
        public boolean shouldPause() {
            return false;
        }
    }

    // === Инициализация ===
    @Override
    public void onInitialize() {
        LOGGER.info("SpeedMod loaded. R - KillAura, Z - NoFall, Right Shift - GUI");

        // Регистрация модулей
        modules.add(new NoFallModule());
        modules.add(new KillAuraModule());

        // Поток для обработки клавиш и тиков
        new Thread(() -> {
            while (true) {
                try { Thread.sleep(10); } catch (InterruptedException ignored) {}

                mc.execute(() -> {
                    try {
                        if (mc.getWindow() == null || mc.player == null || mc.world == null) return;
                        long window = mc.getWindow().getHandle();

                        // Клавиша R (KillAura)
                        boolean rPressed = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_R) == GLFW.GLFW_PRESS;
                        if (rPressed && !lastR) {
                            Module ka = getModule("KillAura");
                            if (ka != null) {
                                ka.toggle();
                                mc.player.sendMessage(Text.literal("§6KillAura " + (ka.isEnabled() ? "§aON" : "§cOFF")), true);
                            }
                        }
                        lastR = rPressed;

                        // Клавиша Z (NoFall)
                        boolean zPressed = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_Z) == GLFW.GLFW_PRESS;
                        if (zPressed && !lastZ) {
                            Module nf = getModule("NoFall");
                            if (nf != null) {
                                nf.toggle();
                                mc.player.sendMessage(Text.literal("§6NoFall " + (nf.isEnabled() ? "§aON" : "§cOFF")), true);
                            }
                        }
                        lastZ = zPressed;

                        // Клавиша Right Shift (GUI)
                        boolean shiftPressed = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
                        if (shiftPressed && !lastShift) {
                            if (mc.currentScreen instanceof ClickGuiScreen) {
                                mc.setScreen(null);
                            } else {
                                mc.setScreen(new ClickGuiScreen());
                            }
                        }
                        lastShift = shiftPressed;

                        // Tick модулей
                        for (Module m : modules) {
                            m.onTick();
                        }

                    } catch (Exception e) {
                        LOGGER.error("Main loop error", e);
                    }
                });
            }
        }).start();
    }

    private static Module getModule(String name) {
        for (Module m : modules) {
            if (m.getName().equalsIgnoreCase(name)) return m;
        }
        return null;
    }
}
