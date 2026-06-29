package com.example.speed;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.api.ModInitializer;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.GlShader;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import org.lwjgl.glfw.GLFW;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

public class SpeedMod implements ModInitializer {
    public static final Logger LOGGER = LoggerFactory.getLogger("speedmod");
    private static final MinecraftClient mc = MinecraftClient.getInstance();

    private boolean lastShift = false;

    @Override
    public void onInitialize() {
        LOGGER.info("SpeedMod loaded (Rounded GUI with shader). Press Right Shift.");

        new Thread(() -> {
            while (true) {
                try { Thread.sleep(10); } catch (InterruptedException ignored) {}

                mc.execute(() -> {
                    try {
                        if (mc.getWindow() == null || mc.player == null) return;
                        long window = mc.getWindow().getHandle();

                        boolean shiftPressed = GLFW.glfwGetKey(window, GLFW.GLFW_KEY_RIGHT_SHIFT) == GLFW.GLFW_PRESS;
                        if (shiftPressed && !lastShift) {
                            if (mc.currentScreen instanceof RoundedShaderScreen) {
                                mc.setScreen(null);
                            } else {
                                mc.setScreen(new RoundedShaderScreen());
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

    // ==================== ЭКРАН С ШЕЙДЕРОМ ====================
    public static class RoundedShaderScreen extends Screen {
        private static final int PANEL_WIDTH = 400;
        private static final int PANEL_HEIGHT = 400;
        private static final int RADIUS = 20;
        private static final int BG_COLOR = 0xCC1A1A1A; // полупрозрачный чёрный

        private GlShader roundedShader;

        public RoundedShaderScreen() {
            super(Text.literal("Rounded GUI"));
        }

        @Override
        protected void init() {
            super.init();
            // Компилируем шейдер при инициализации
            try {
                roundedShader = new GlShader(null, getFragmentShaderSource(), VertexFormats.POSITION);
                LOGGER.info("Rounded shader compiled successfully");
            } catch (Exception e) {
                LOGGER.error("Failed to compile shader, falling back to software rendering", e);
                roundedShader = null;
            }
        }

        @Override
        public void render(DrawContext context, int mouseX, int mouseY, float delta) {
            // Затемняем фон (показываем игру под ним)
            context.fill(0, 0, this.width, this.height, 0x88000000);

            int x = (this.width - PANEL_WIDTH) / 2;
            int y = (this.height - PANEL_HEIGHT) / 2;

            if (roundedShader != null && roundedShader.isValid()) {
                // ---- Рендеринг через шейдер ----
                RenderSystem.setShader(() -> roundedShader);
                RenderSystem.enableBlend();
                RenderSystem.defaultBlendFunc();

                // Передаём параметры шейдера
                float w = PANEL_WIDTH;
                float h = PANEL_HEIGHT;
                float r = RADIUS;
                float colorAlpha = (BG_COLOR >> 24 & 0xFF) / 255f;
                float colorR = ((BG_COLOR >> 16) & 0xFF) / 255f;
                float colorG = ((BG_COLOR >> 8) & 0xFF) / 255f;
                float colorB = (BG_COLOR & 0xFF) / 255f;

                roundedShader.addUniform("uResolution", (float) this.width, (float) this.height);
                roundedShader.addUniform("uRectPos", (float) x, (float) y);
                roundedShader.addUniform("uRectSize", w, h);
                roundedShader.addUniform("uRadius", r);
                roundedShader.addUniform("uColor", colorR, colorG, colorB, colorAlpha);

                // Рисуем прямоугольник (4 вершины)
                Tessellator tessellator = RenderSystem.renderThreadTessellator();
                BufferBuilder buffer = tessellator.begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION);
                buffer.vertex(x, y, 0).next();
                buffer.vertex(x + w, y, 0).next();
                buffer.vertex(x + w, y + h, 0).next();
                buffer.vertex(x, y + h, 0).next();
                BufferBuilder.BuiltBuffer built = buffer.end();
                BufferRenderer.drawWithGlobalProgram(built);

                RenderSystem.disableBlend();
            } else {
                // ---- Fallback: пиксельная отрисовка (если шейдер не скомпилировался) ----
                drawRoundedRectSoftware(context, x, y, PANEL_WIDTH, PANEL_HEIGHT, RADIUS, BG_COLOR);
            }

            // Текст (поверх)
            context.drawText(mc.textRenderer, "§lCustom GUI (Shader)", x + 15, y + 15, 0xFFFFFF, false);
        }

        // === Фрагментный шейдер (GLSL) ===
        private String getFragmentShaderSource() {
            return "#version 330 core\n" +
                    "in vec2 fragCoord;\n" +
                    "uniform vec2 uResolution;\n" +
                    "uniform vec2 uRectPos;\n" +
                    "uniform vec2 uRectSize;\n" +
                    "uniform float uRadius;\n" +
                    "uniform vec4 uColor;\n" +
                    "out vec4 FragColor;\n" +
                    "float roundedRectSDF(vec2 p, vec2 halfSize, float r) {\n" +
                    "    vec2 q = abs(p) - halfSize + r;\n" +
                    "    return length(max(q, 0.0)) + min(max(q.x, q.y), 0.0) - r;\n" +
                    "}\n" +
                    "void main() {\n" +
                    "    vec2 center = uRectPos + uRectSize * 0.5;\n" +
                    "    vec2 p = fragCoord - center;\n" +
                    "    vec2 halfSize = uRectSize * 0.5;\n" +
                    "    float d = roundedRectSDF(p, halfSize, uRadius);\n" +
                    "    float alpha = 1.0 - smoothstep(-1.0, 0.0, d);\n" +
                    "    FragColor = vec4(uColor.rgb, uColor.a * alpha);\n" +
                    "}\n";
        }

        // === Fallback (без шейдера) ===
        private void drawRoundedRectSoftware(DrawContext context, int x, int y, int w, int h, int r, int color) {
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
                        switch (corner) {
                            case 0: if (dx > 0 || dy > 0) continue; break;
                            case 1: if (dx < 0 || dy > 0) continue; break;
                            case 2: if (dx > 0 || dy < 0) continue; break;
                            case 3: if (dx < 0 || dy < 0) continue; break;
                        }
                        context.fill(px, py, px + 1, py + 1, color);
                    }
                }
            }
        }

        @Override
        public void removed() {
            super.removed();
            if (roundedShader != null) {
                roundedShader.close(); // освобождаем ресурсы шейдера
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
