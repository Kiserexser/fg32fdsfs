package com.example.speed;

import net.fabricmc.api.ModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class SpeedMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("speedmod");
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private boolean lastShift = false;

    @Override
    public void onInitialize() {
        LOGGER.info("SpeedMod loaded (rounded corners without shaders). Press Right Shift.");

        new Thread(() -> {
            while (true) {
                try { Thread.sleep(10); } catch (InterruptedException ignored) {}

                mc.execute(() -> {
                    try {
                        if (mc.getWindow() == null || mc.player == null) return;
                        long window = mc.getWindow().getHandle();

                        boolean shiftPressed = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
                        if (shiftPressed && !lastShift) {
                            if (mc.currentScreen instanceof RoundedGuiScreen) {
                                mc.setScreen(null);
                            } else {
                                mc.setScreen(new RoundedGuiScreen());
                            }
                        }
                        lastShift = shiftPressed;

                    } catch (Exception e) {
                        LOGGER.error("Main loop error", e);
                    }
                });
            }
        }).start();
    }

    // ==================== ЭКРАН С ЗАКРУГЛЁННЫМИ УГЛАМИ ====================
    public static class RoundedGuiScreen extends Screen {
        private static final int PANEL_WIDTH = 400;
        private static final int PANEL_HEIGHT = 400;
        private static final int RADIUS = 20;
        private static final int BG_COLOR = 0xCC1A1A1A;

        public RoundedGuiScreen() {
            super(Text.literal("Rounded GUI"));
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            // Затемнение фона
            context.fill(0, 0, this.width, this.height, 0x88000000);

            int x = (this.width - PANEL_WIDTH) / 2;
            int y = (this.height - PANEL_HEIGHT) / 2;

            // Рисуем панель с закруглёнными углами
            drawRoundedRect(context, x, y, PANEL_WIDTH, PANEL_HEIGHT, RADIUS, BG_COLOR);

            // Текст
            context.drawText(mc.textRenderer, "§lCustom GUI (Rounded)", x + 15, y + 15, 0xFFFFFF, false);
            context.drawText(mc.textRenderer, "§7Smooth corners without shaders", x + 15, y + 35, 0xAAAAAA, false);
        }

        // === Рисование закруглённого прямоугольника ===
        private void drawRoundedRect(DrawContext context, int x, int y, int w, int h, int r, int color) {
            // Основной прямоугольник
            context.fill(x + r, y, x + w - r, y + h, color);
            context.fill(x, y + r, x + w, y + h - r, color);

            // Четыре угла (четверти круга)
            drawQuarterCircle(context, x + r, y + r, r, color, 0);
            drawQuarterCircle(context, x + w - r, y + r, r, color, 1);
            drawQuarterCircle(context, x + r, y + h - r, r, color, 2);
            drawQuarterCircle(context, x + w - r, y + h - r, r, color, 3);
        }

        private void drawQuarterCircle(DrawContext context, int cx, int cy, int r, int color, int corner) {
            for (int dx = -r; dx <= r; dx++) {
                for (int dy = -r; dy <= r; dy++) {
                    if (dx * dx + dy * dy <= r * r) {
                        int px = cx + dx;
                        int py = cy + dy;
                        // Ограничиваем четверть
                        switch (corner) {
                            case 0: if (dx > 0 || dy > 0) continue; break; // левый верхний
                            case 1: if (dx < 0 || dy > 0) continue; break; // правый верхний
                            case 2: if (dx > 0 || dy < 0) continue; break; // левый нижний
                            case 3: if (dx < 0 || dy < 0) continue; break; // правый нижний
                        }
                        context.fill(px, py, px + 1, py + 1, color);
                    }
                }
            }
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
}
