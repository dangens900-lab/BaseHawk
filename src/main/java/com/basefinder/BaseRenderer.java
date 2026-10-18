package com.basefinder;

import com.mojang.blaze3d.systems.RenderSystem;
import net.fabricmc.fabric.api.client.rendering.v1.HudRenderCallback;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderContext;
import net.fabricmc.fabric.api.client.rendering.v1.WorldRenderEvents;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gl.ShaderProgramKeys;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.BufferRenderer;
import net.minecraft.client.render.BuiltBuffer;
import net.minecraft.client.render.RenderTickCounter;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormat;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import org.joml.Matrix4f;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public final class BaseRenderer {
    private BaseRenderer() {}

    private static final float[] RED = {1f, 0.25f, 0.25f};
    private static final float[] ORANGE = {1f, 0.6f, 0.1f};
    private static final float[] YELLOW = {1f, 0.9f, 0.2f};
    private static final float[] CYAN = {0.2f, 0.9f, 1f};

    public static void init() {
        WorldRenderEvents.AFTER_TRANSLUCENT.register(BaseRenderer::renderWorld);
        HudRenderCallback.EVENT.register(BaseRenderer::renderHud);
    }

    private static float[] color(int score) {
        if (score >= 30) return RED;
        if (score >= 14) return ORANGE;
        return YELLOW;
    }

    // ---------- світ: боксі крізь стіни ----------

    private static void renderWorld(WorldRenderContext ctx) {
        BlockPos target = BaseFinderClient.target;
        boolean esp = BaseFinderConfig.espEnabled;
        if (!esp && target == null) return;

        MatrixStack ms = ctx.matrixStack();
        if (ms == null) return;
        Vec3d cam = ctx.camera().getPos();
        Matrix4f mat = ms.peek().getPositionMatrix();

        BufferBuilder bb = Tessellator.getInstance().begin(VertexFormat.DrawMode.QUADS, VertexFormats.POSITION_COLOR);

        if (esp) {
            for (Base b : BaseScanner.bases()) {
                float[] c = color(b.score());
                // тонкий стовп, щоб базу було видно здалеку
                box(bb, mat, cam, b.cx() + 0.35, b.cy() - 128, b.cz() + 0.35,
                        b.cx() + 0.65, b.cy() + 384, b.cz() + 0.65, c[0], c[1], c[2], 0.30f);
                // кожен контейнер окремо
                for (Base.Entry e : b.entries()) {
                    box(bb, mat, cam, e.x() + 0.05, e.y() + 0.05, e.z() + 0.05,
                            e.x() + 0.95, e.y() + 0.95, e.z() + 0.95, c[0], c[1], c[2], 0.40f);
                }
            }
        }

        if (target != null) {
            float[] c = CYAN;
            box(bb, mat, cam, target.getX() + 0.25, target.getY() - 128, target.getZ() + 0.25,
                    target.getX() + 0.75, target.getY() + 384, target.getZ() + 0.75, c[0], c[1], c[2], 0.45f);
        }

        BuiltBuffer built = bb.endNullable();
        if (built == null) return;

        RenderSystem.enableBlend();
        RenderSystem.defaultBlendFunc();
        RenderSystem.disableDepthTest();
        RenderSystem.disableCull();
        RenderSystem.setShader(ShaderProgramKeys.POSITION_COLOR);
        BufferRenderer.drawWithGlobalProgram(built);
        RenderSystem.enableCull();
        RenderSystem.enableDepthTest();
        RenderSystem.disableBlend();
    }

    private static void box(BufferBuilder bb, Matrix4f m, Vec3d cam,
                            double x1, double y1, double z1, double x2, double y2, double z2,
                            float r, float g, float b, float a) {
        float ax = (float) (x1 - cam.x), ay = (float) (y1 - cam.y), az = (float) (z1 - cam.z);
        float bx = (float) (x2 - cam.x), by = (float) (y2 - cam.y), bz = (float) (z2 - cam.z);
        // низ
        v(bb, m, ax, ay, az, r, g, b, a); v(bb, m, bx, ay, az, r, g, b, a); v(bb, m, bx, ay, bz, r, g, b, a); v(bb, m, ax, ay, bz, r, g, b, a);
        // верх
        v(bb, m, ax, by, az, r, g, b, a); v(bb, m, ax, by, bz, r, g, b, a); v(bb, m, bx, by, bz, r, g, b, a); v(bb, m, bx, by, az, r, g, b, a);
        // північ
        v(bb, m, ax, ay, az, r, g, b, a); v(bb, m, ax, by, az, r, g, b, a); v(bb, m, bx, by, az, r, g, b, a); v(bb, m, bx, ay, az, r, g, b, a);
        // південь
        v(bb, m, ax, ay, bz, r, g, b, a); v(bb, m, bx, ay, bz, r, g, b, a); v(bb, m, bx, by, bz, r, g, b, a); v(bb, m, ax, by, bz, r, g, b, a);
        // захід
        v(bb, m, ax, ay, az, r, g, b, a); v(bb, m, ax, ay, bz, r, g, b, a); v(bb, m, ax, by, bz, r, g, b, a); v(bb, m, ax, by, az, r, g, b, a);
        // схід
        v(bb, m, bx, ay, az, r, g, b, a); v(bb, m, bx, by, az, r, g, b, a); v(bb, m, bx, by, bz, r, g, b, a); v(bb, m, bx, ay, bz, r, g, b, a);
    }

    private static void v(BufferBuilder bb, Matrix4f m, float x, float y, float z, float r, float g, float b, float a) {
        bb.vertex(m, x, y, z).color(r, g, b, a);
    }

    // ---------- HUD ----------

    private static void renderHud(DrawContext ctx, RenderTickCounter tick) {
        MinecraftClient mc = MinecraftClient.getInstance();
        if (mc.player == null || mc.options.hudHidden) return;
        BlockPos target = BaseFinderClient.target;
        if (!BaseFinderConfig.espEnabled && target == null) return;

        double px = mc.player.getX(), py = mc.player.getY(), pz = mc.player.getZ();
        int x = 6, y = 6;

        if (BaseFinderConfig.espEnabled) {
            List<Base> sorted = new ArrayList<>(BaseScanner.bases());
            sorted.sort(Comparator.comparingDouble(b -> b.distSq(px, py, pz)));
            ctx.drawTextWithShadow(mc.textRenderer,
                    Text.literal("§eБаз знайдено: §f" + sorted.size() + " §7[B — список]"), x, y, 0xFFFFFFFF);
            y += 11;
            for (int i = 0; i < Math.min(3, sorted.size()); i++) {
                Base b = sorted.get(i);
                int d = (int) Math.sqrt(b.distSq(px, py, pz));
                ctx.drawTextWithShadow(mc.textRenderer,
                        Text.literal("§7• §f" + b.cx() + " " + b.cy() + " " + b.cz() + " §7— " + d + " м, очки " + b.score()),
                        x, y, 0xFFFFFFFF);
                y += 10;
            }
        }

        if (target != null) {
            int d = (int) Math.sqrt(target.getSquaredDistance(px, py, pz));
            ctx.drawTextWithShadow(mc.textRenderer,
                    Text.literal("§bМітка: §f" + target.getX() + " " + target.getY() + " " + target.getZ() + " §7— " + d + " м"),
                    x, y + 2, 0xFFFFFFFF);
        }
    }
}
