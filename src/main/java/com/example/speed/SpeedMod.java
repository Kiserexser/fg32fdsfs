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

    // Состояние клавиши
    private boolean lastShift = false;

    @Override
    public void onInitialize() {
        LOGGER.info("SpeedMod loaded. Press Right Shift to open GUI.");

        new Thread(() -> {
            while (true) {
                try { Thread.sleep(10); } catch (InterruptedException ignored) {}

                mc.execute(() -> {
                    try {
                        if (mc.getWindow() == null || mc.player == null) return;
                        long window = mc.getWindow().getHandle();

                        boolean shiftPressed = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
                        if (shiftPressed && !lastShift) {
                            if (mc.currentScreen instanceof GuiScreen) {
                                mc.setScreen(null);
                            } else {
                                mc.setScreen(new GuiScreen());
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

    // === Простой экран (чёрное окно 400x400) ===
    public static class GuiScreen extends Screen {
        private static final int WIDTH = 400;
        private static final int HEIGHT = 400;
        private static final int BG_COLOR = 0xFF000000; // полностью чёрный

        public GuiScreen() {
            super(Text.literal("GUI"));
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            // Рисуем просто чёрный прямоугольник по центру
            int x = (this.width - WIDTH) / 2;
            int y = (this.height - HEIGHT) / 2;
            context.fill(x, y, x + WIDTH, y + HEIGHT, BG_COLOR);

            // Можно добавить текст (опционально)
            context.drawText(mc.textRenderer, "§lGUI Panel", x + 10, y + 10, 0xFFFFFF, false);
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
