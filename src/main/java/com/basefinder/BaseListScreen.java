package com.basefinder;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.math.BlockPos;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class BaseListScreen extends Screen {
    private static final int ROW_H = 22;
    private static final int DEL_W = 22;

    private List<Base> view = new ArrayList<>();
    private int scroll = 0;
    private int refreshTimer = 0;
    private int listLeft, listRight, listTop, listBottom;

    public BaseListScreen() {
        super(Text.literal("BaseHawk"));
    }

    private void layout() {
        int panelW = Math.min(width - 20, 460);
        listLeft = (width - panelW) / 2;
        listRight = listLeft + panelW;
        listTop = 132;
        listBottom = height - 22;
    }

    private void refresh() {
        view = new ArrayList<>(BaseScanner.bases());
        if (client != null && client.player != null) {
            double px = client.player.getX(), py = client.player.getY(), pz = client.player.getZ();
            view.sort(Comparator.comparingDouble(b -> b.distSq(px, py, pz)));
        }
    }

    private void rebuild() {
        clearChildren();
        init();
    }

    private void settingsChanged() {
        BaseFinderConfig.save();
        BaseScanner.recompute();
        refresh();
    }

    private void addBtn(String label, int x, int y, int w, ButtonWidget.PressAction action) {
        addDrawableChild(ButtonWidget.builder(Text.literal(label), action).dimensions(x, y, w, 20).build());
    }

    @Override
    protected void init() {
        layout();
        refresh();

        int bw = 100, gap = 4;
        int x0 = width / 2 - (bw * 3 + gap * 2) / 2;
        int x1 = x0 + bw + gap;
        int x2 = x0 + (bw + gap) * 2;
        int y1 = 34, y2 = 58, y3 = 82;

        // ряд 1
        addBtn("ESP: " + (BaseFinderConfig.espEnabled ? "ON" : "OFF"), x0, y1, bw, b -> {
            BaseFinderConfig.espEnabled = !BaseFinderConfig.espEnabled;
            BaseFinderConfig.save();
            rebuild();
        });
        addBtn("Підписи: " + (BaseFinderConfig.labelsEnabled ? "ON" : "OFF"), x1, y1, bw, b -> {
            BaseFinderConfig.labelsEnabled = !BaseFinderConfig.labelsEnabled;
            BaseFinderConfig.save();
            rebuild();
        });
        addBtn("Скинути мітку", x2, y1, bw, b -> {
            BaseFinderClient.target = null;
        });

        // ряд 2
        addBtn("Поріг −", x0, y2, bw, b -> {
            BaseFinderConfig.minScore = Math.max(1, BaseFinderConfig.minScore - 1);
            settingsChanged();
        });
        addBtn("Поріг +", x1, y2, bw, b -> {
            BaseFinderConfig.minScore = Math.min(60, BaseFinderConfig.minScore + 1);
            settingsChanged();
        });
        addBtn("Очистити все", x2, y2, bw, b -> {
            BaseScanner.clearAll();
            refresh();
        });

        // ряд 3
        addBtn("Радіус −", x0, y3, bw, b -> {
            BaseFinderConfig.linkRadius = Math.max(8, BaseFinderConfig.linkRadius - 4);
            settingsChanged();
        });
        addBtn("Радіус +", x1, y3, bw, b -> {
            BaseFinderConfig.linkRadius = Math.min(64, BaseFinderConfig.linkRadius + 4);
            settingsChanged();
        });
        addBtn("Закрити", x2, y3, bw, b -> close());

        // ряд 4: режим достовірності
        addBtn("Лише підтверджені: " + (BaseFinderConfig.requireConfirmed ? "ON" : "OFF"),
                x0, 106, (bw * 3 + gap) / 2, b -> {
                    BaseFinderConfig.requireConfirmed = !BaseFinderConfig.requireConfirmed;
                    BaseFinderConfig.save();
                    BaseScanner.recompute();
                    rebuild();
                });
        addBtn("Ловити все: " + (BaseFinderConfig.catchAll ? "ON" : "OFF"),
                x0 + (bw * 3 + gap) / 2 + gap, 106, (bw * 3 + gap) / 2, b -> {
                    BaseFinderConfig.catchAll = !BaseFinderConfig.catchAll;
                    BaseFinderConfig.save();
                    BaseScanner.recompute();
                    rebuild();
                });
    }

    @Override
    public void tick() {
        if (++refreshTimer % 20 == 0) refresh();
    }

    @Override
    public void renderBackground(DrawContext ctx, int mouseX, int mouseY, float delta) {
        ctx.fill(0, 0, width, height, 0xB0000000);
    }

    @Override
    public void render(DrawContext ctx, int mouseX, int mouseY, float delta) {
        super.render(ctx, mouseX, mouseY, delta);
        layout();

        ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("BaseHawk"), width / 2, 8, 0xFFFFFFFF);
        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("Поріг: " + BaseFinderConfig.minScore + " очок  ·  радіус: "
                        + BaseFinderConfig.linkRadius + " бл.  ·  знайдено: " + view.size()
                        + "  ·  відхилено: " + BaseScanner.rejectedCount()),
                width / 2, 20, 0xFFAAAAAA);

        ctx.fill(listLeft, listTop, listRight, listBottom, 0x66000000);

        int visible = Math.max(1, (listBottom - listTop) / ROW_H);
        int maxScroll = Math.max(0, view.size() - visible);
        scroll = Math.max(0, Math.min(scroll, maxScroll));

        if (view.isEmpty()) {
            ctx.drawCenteredTextWithShadow(textRenderer,
                    Text.literal("Баз поки не знайдено — походи світом, мод скануватиме завантажені чанки."),
                    width / 2, listTop + 10, 0xFFAAAAAA);
        }

        double px = client != null && client.player != null ? client.player.getX() : 0;
        double py = client != null && client.player != null ? client.player.getY() : 0;
        double pz = client != null && client.player != null ? client.player.getZ() : 0;
        BlockPos target = BaseFinderClient.target;

        for (int i = 0; i < visible && scroll + i < view.size(); i++) {
            Base b = view.get(scroll + i);
            int y = listTop + i * ROW_H;
            boolean hover = mouseX >= listLeft && mouseX < listRight && mouseY >= y && mouseY < y + ROW_H;
            boolean delHover = hover && mouseX >= listRight - DEL_W;
            boolean isTarget = target != null && target.getX() == b.cx() && target.getY() == b.cy() && target.getZ() == b.cz();

            ctx.fill(listLeft + 1, y + 1, listRight - 1, y + ROW_H - 1, hover ? 0x55FFFFFF : 0x22FFFFFF);

            // кнопка видалення бази
            ctx.fill(listRight - DEL_W + 2, y + 3, listRight - 4, y + ROW_H - 3, delHover ? 0xCCFF4444 : 0x88AA2222);
            ctx.drawCenteredTextWithShadow(textRenderer, Text.literal("×"),
                    listRight - DEL_W / 2 - 1, y + 7, 0xFFFFFFFF);

            int dist = (int) Math.sqrt(b.distSq(px, py, pz));
            String l1 = "#" + (scroll + i + 1) + "  " + b.cx() + " " + b.cy() + " " + b.cz()
                    + "  (" + dist + " м)  очки " + b.score()
                    + (b.confirmed() < b.total() ? "  підтв. " + b.confirmed() + "/" + b.total() : "");
            String l2 = textRenderer.trimToWidth(b.summary(), listRight - listLeft - 12 - DEL_W);
            ctx.drawTextWithShadow(textRenderer, l1, listLeft + 6, y + 3, isTarget ? 0xFF55FFFF : 0xFFFFFFFF);
            ctx.drawTextWithShadow(textRenderer, l2, listLeft + 6, y + 12, 0xFFAAAAAA);
        }

        ctx.drawCenteredTextWithShadow(textRenderer,
                Text.literal("ЛКМ — мітка  ·  ПКМ — копіювати  ·  × — видалити базу  ·  колесо — прокрутка"),
                width / 2, height - 14, 0xFF888888);
    }

    @Override
    public boolean mouseScrolled(double mouseX, double mouseY, double horizontalAmount, double verticalAmount) {
        scroll -= (int) Math.signum(verticalAmount);
        return true;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        if (super.mouseClicked(mouseX, mouseY, button)) return true;
        layout();
        if (mouseX >= listLeft && mouseX < listRight && mouseY >= listTop && mouseY < listBottom) {
            int row = (int) ((mouseY - listTop) / ROW_H);
            int idx = scroll + row;
            if (idx >= 0 && idx < view.size()) {
                Base b = view.get(idx);
                if (button == 0 && mouseX >= listRight - DEL_W) {
                    BaseScanner.deleteBase(b);
                    refresh();
                } else if (button == 0) {
                    BaseFinderClient.target = new BlockPos(b.cx(), b.cy(), b.cz());
                } else if (button == 1 && client != null) {
                    client.keyboard.setClipboard(b.cx() + " " + b.cy() + " " + b.cz());
                }
                return true;
            }
        }
        return false;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (BaseFinderClient.openKey.matchesKey(keyCode, scanCode)) {
            close();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
