package dev.gearrender;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import org.lwjgl.glfw.GLFW;

import java.text.SimpleDateFormat;
import java.util.*;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;

public class GearRenderScreen extends Screen {
    // kolory (RGB)
    private static final int PANEL = 0x141620, CARD = 0x1E212C, ROW = 0x282C3B, ACC = 0x35D0BA;
    private static final int TXT = 0xE5E7EB, MUT = 0x8B93A5;
    private static final int GREEN = 0x4ADE80, RED = 0xF87171, YEL = 0xFACC15, BLUE = 0x60A5FA, GRAY = 0x9CA3AF;
    private static final int RH = 38;

    private enum Tab {
        MAIN("Główna"), HISTORY("Historia"), TRACKED("Śledzeni"), SETTINGS("Ustawienia");
        final String label;
        Tab(String l) { label = l; }
    }

    private interface Row { void draw(DrawContext c, int x, int y, int w, int mx, int my); }
    private record Hit(int x1, int y1, int x2, int y2, int cy0, int cy1, Runnable r) {}
    private record St(int color, String text, int rank) {}

    private static Tab tab = Tab.MAIN;
    private final int[] scroll = new int[6]; // 0 nearby, 1 vanished, 2 history, 3 tracked, 4 settings, 5 watch
    private final List<Hit> hits = new ArrayList<>();
    private TextFieldWidget field;
    private int px, py, pw, ph, cy0, cy1;
    private int clipY0 = Integer.MIN_VALUE, clipY1 = Integer.MAX_VALUE;
    private ItemStack hover;

    public GearRenderScreen() { super(Text.literal("Gear Render")); }

    @Override
    protected void init() {
        pw = Math.min(width - 12, 560);
        ph = Math.min(height - 12, 330);
        px = (width - pw) / 2;
        py = (height - ph) / 2;
        cy0 = py + 32;
        cy1 = py + ph - 30;
        field = new TextFieldWidget(textRenderer, px + pw - 164, cy0 + 4, 150, 14, Text.empty());
        field.setMaxLength(32);
        addSelectableChild(field);
        updateField();
    }

    private void updateField() {
        boolean on = tab == Tab.HISTORY || tab == Tab.TRACKED;
        field.setVisible(on);
        if (!on) field.setFocused(false);
        field.setPlaceholder(Text.literal(tab == Tab.HISTORY ? "Szukaj nicku..." : "Nick + Enter..."));
    }

    @Override
    public boolean shouldPause() { return false; }

    @Override
    public void renderBackground(DrawContext c, int mx, int my, float d) { }

    // ---------- helpers ----------
    private static int a(int rgb) { return 0xFF000000 | rgb; }

    private void rr(DrawContext c, int x, int y, int w, int h, int argb) {
        c.fill(x + 2, y, x + w - 2, y + h, argb);
        c.fill(x + 1, y + 1, x + w - 1, y + h - 1, argb);
        c.fill(x, y + 2, x + w, y + h - 2, argb);
    }

    private int bw(String s) { return textRenderer.getWidth(s) + 8; }

    private int btn(DrawContext c, int rightX, int y, String label, int rgb, int mx, int my, Runnable r) {
        int w = bw(label);
        int x = rightX - w;
        boolean hov = mx >= x && mx < x + w && my >= y && my < y + 12 && my >= clipY0 && my < clipY1;
        rr(c, x, y, w, 12, hov ? a(rgb) : ((0x88 << 24) | rgb));
        c.drawText(textRenderer, label, x + 4, y + 2, hov ? 0xFF101010 : 0xFFFFFFFF, false);
        hits.add(new Hit(x, y, x + w, y + 12, clipY0, clipY1, r));
        return x - 3;
    }

    private void text(DrawContext c, String s, int x, int y, int rgb) {
        c.drawText(textRenderer, s, x, y, a(rgb), false);
    }

    private static String ago(long ms) {
        long s = Math.max(0, ms / 1000);
        if (s < 60) return s + " s";
        if (s < 3600) return s / 60 + " min";
        if (s < 86400) return s / 3600 + " h";
        return s / 86400 + " d";
    }

    private St status(String name) {
        String k = Store.key(name);
        long now = System.currentTimeMillis();
        Manager.RT r = Manager.rt.get(k);
        if (Manager.world.contains(k)) return new St(GREEN, "w pobliżu", 1);
        if (r != null && r.pendingUntil > now) return new St(BLUE, "tpa wysłane", 0);
        if (r != null && r.offline) return new St(RED, "offline", 3);
        if (Manager.vanished.containsKey(k)) return new St(YEL, "zniknął", 0);
        return new St(GRAY, "nieznany", 2);
    }

    private boolean isTracked(Rec r) { return Store.tracked.containsKey(Store.key(r.name)); }

    private void icon(DrawContext c, String id, int x, int y, int mx, int my) {
        if (id.isEmpty()) {
            c.fill(x + 5, y + 5, x + 11, y + 11, 0x22FFFFFF);
            return;
        }
        ItemStack s = Manager.stack(id);
        c.drawItem(s, x, y);
        if (mx >= x && mx < x + 16 && my >= y && my < y + 16 && my >= clipY0 && my < clipY1) hover = s;
    }

    /** Karta wiersza gracza: kropka statusu, nick, set + lapki, podtytul. */
    private void recFrame(DrawContext c, int x, int y, int w, Rec r, int mx, int my, String sub, int subColor) {
        rr(c, x, y, w, RH - 2, a(ROW));
        St st = status(r.name);
        c.fill(x + 6, y + 6, x + 11, y + 11, a(st.color));
        text(c, r.name, x + 16, y + 4, TXT);
        String[] s = r.slots();
        for (int i = 0; i < 4; i++) icon(c, s[i], x + 6 + i * 17, y + 17, mx, my);
        icon(c, s[4], x + 6 + 4 * 17 + 6, y + 17, mx, my);
        icon(c, s[5], x + 6 + 5 * 17 + 6, y + 17, mx, my);
        text(c, sub, x + 6 + 6 * 17 + 14, y + 21, subColor);
    }

    private void list(DrawContext c, int mx, int my, int id, int x, int y, int w, int h, List<Row> rows, int rh, String empty) {
        int total = rows.size() * rh;
        int max = Math.max(0, total - h);
        scroll[id] = Math.max(0, Math.min(scroll[id], max));
        if (rows.isEmpty()) {
            text(c, empty, x + 6, y + 6, MUT);
            return;
        }
        c.enableScissor(x, y, x + w, y + h);
        clipY0 = y;
        clipY1 = y + h;
        int rw = max > 0 ? w - 6 : w;
        for (int i = 0; i < rows.size(); i++) {
            int ry = y + i * rh - scroll[id];
            if (ry + rh < y || ry > y + h) continue;
            rows.get(i).draw(c, x, ry, rw, mx, my);
        }
        c.disableScissor();
        clipY0 = Integer.MIN_VALUE;
        clipY1 = Integer.MAX_VALUE;
        if (max > 0) {
            int bh = Math.max(14, h * h / total);
            int by = y + (int) ((long) (h - bh) * scroll[id] / max);
            c.fill(x + w - 3, by, x + w, by + bh, 0x99000000 | ACC);
        }
    }

    // ---------- render ----------
    @Override
    public void render(DrawContext c, int mx, int my, float delta) {
        hits.clear();
        hover = null;
        c.fill(0, 0, width, height, 0xAA000000);
        rr(c, px, py, pw, ph, 0xF0000000 | PANEL);

        // naglowek
        text(c, "Gear Render", px + 10, py + 9, ACC);
        int tx = px + 96;
        for (Tab t : Tab.values()) {
            int w = textRenderer.getWidth(t.label) + 16;
            boolean sel = t == tab;
            boolean hov = mx >= tx && mx < tx + w && my >= py + 4 && my < py + 23;
            if (sel) rr(c, tx, py + 5, w, 18, 0xFF2A3042);
            else if (hov) rr(c, tx, py + 5, w, 18, 0xFF212636);
            text(c, t.label, tx + 8, py + 10, sel ? TXT : MUT);
            if (sel) c.fill(tx + 6, py + 21, tx + w - 6, py + 23, a(ACC));
            hits.add(new Hit(tx, py + 4, tx + w, py + 23, clipY0, clipY1, () -> {
                tab = t;
                field.setText("");
                updateField();
            }));
            tx += w + 2;
        }
        btn(c, px + pw - 8, py + 8, "X", RED, mx, my, this::close);

        switch (tab) {
            case MAIN -> drawMain(c, mx, my);
            case HISTORY -> drawHistory(c, mx, my);
            case TRACKED -> drawTracked(c, mx, my);
            case SETTINGS -> drawSettings(c, mx, my);
        }

        // dolny pasek
        int by = py + ph - 24;
        rr(c, px + 6, by, pw - 12, 18, a(CARD));
        boolean on = Config.d.autoTp;
        String lab = "AutoTP: " + (on ? "ON" : "OFF");
        int w = bw(lab);
        btn(c, px + 10 + w, by + 3, lab, on ? GREEN : RED, mx, my, () -> {
            Config.d.autoTp = !Config.d.autoTp;
            Config.save();
        });
        String st = "W pobliżu " + Manager.nearby.size() + "   |   Zniknięci " + Manager.vanished.size()
                + "   |   Śledzeni " + Store.tracked.size() + "   |   Kolejka " + Manager.queueSize();
        text(c, st, px + 10 + w + 10, by + 5, MUT);

        if (tab == Tab.HISTORY || tab == Tab.TRACKED) field.render(c, mx, my, delta);
        if (hover != null) c.drawItemTooltip(textRenderer, hover, mx, my);
    }

    private void card(DrawContext c, int x, int y, int w, int h) { rr(c, x, y, w, h, a(CARD)); }

    // ---------- zakladka: glowna ----------
    private void drawMain(DrawContext c, int mx, int my) {
        int colW = (pw - 18) / 2;
        int lx = px + 6, rx = lx + colW + 6;
        int h = cy1 - cy0;
        card(c, lx, cy0, colW, h);
        card(c, rx, cy0, colW, h);

        List<Rec> near = new ArrayList<>(Manager.nearby.values());
        near.sort(Comparator.comparingDouble(r -> r.dist));
        List<Rec> van = new ArrayList<>(Manager.vanished.values());
        van.sort((p, q) -> Long.compare(q.vanishedAt, p.vanishedAt));

        text(c, "W pobliżu (" + near.size() + ")", lx + 8, cy0 + 6, ACC);
        text(c, "Zniknięci (" + van.size() + ")", rx + 8, cy0 + 6, YEL);

        List<Row> rn = new ArrayList<>();
        for (Rec r : near) {
            rn.add((cc, x, y, w, m1, m2) -> {
                recFrame(cc, x, y, w, r, m1, m2, String.format(Locale.ROOT, "%.0f m", r.dist) + (isTracked(r) ? "  (śledzony)" : ""), MUT);
                int bx = x + w - 4;
                bx = btn(cc, bx, y + 4, "TPA", BLUE, m1, m2, () -> Manager.tpa(r.name));
                bx = btn(cc, bx, y + 4, "STATS", YEL, m1, m2, () -> Manager.stats(r.name));
                if (!isTracked(r)) btn(cc, bx, y + 4, "+", GREEN, m1, m2, () -> Store.track(r));
            });
        }
        list(c, mx, my, 0, lx + 4, cy0 + 20, colW - 8, h - 24, rn, RH, "Nikt z listy poszukiwanych w pobliżu.");

        long now = System.currentTimeMillis();
        List<Row> rv = new ArrayList<>();
        for (Rec r : van) {
            rv.add((cc, x, y, w, m1, m2) -> {
                recFrame(cc, x, y, w, r, m1, m2, status(r.name).text + " " + ago(now - r.vanishedAt), MUT);
                int bx = x + w - 4;
                bx = btn(cc, bx, y + 4, "TPA", BLUE, m1, m2, () -> Manager.tpa(r.name));
                btn(cc, bx, y + 4, "STATS", YEL, m1, m2, () -> Manager.stats(r.name));
            });
        }
        list(c, mx, my, 1, rx + 4, cy0 + 20, colW - 8, h - 24, rv, RH, "Nikt nie zniknął.");
    }

    // ---------- zakladka: historia ----------
    private void drawHistory(DrawContext c, int mx, int my) {
        int h = cy1 - cy0;
        card(c, px + 6, cy0, pw - 12, h);
        String q = field.getText().trim().toLowerCase(Locale.ROOT);
        List<Rec> all = new ArrayList<>();
        for (Rec r : Store.history.values()) if (q.isEmpty() || r.name.toLowerCase(Locale.ROOT).contains(q)) all.add(r);
        all.sort((p, o) -> Long.compare(o.lastSeen, p.lastSeen));
        text(c, "Historia zniknięć (" + all.size() + ")", px + 14, cy0 + 7, ACC);
        btn(c, px + pw - 14 - 150 - 4, cy0 + 5, "Wyczyść", RED, mx, my, () -> {
            Store.history.clear();
            Store.save();
        });

        SimpleDateFormat f = new SimpleDateFormat("dd.MM HH:mm");
        List<Row> rows = new ArrayList<>();
        for (Rec r : all) {
            rows.add((cc, x, y, w, m1, m2) -> {
                recFrame(cc, x, y, w, r, m1, m2, "ostatnio: " + f.format(new Date(r.lastSeen)) + "  •  " + status(r.name).text, MUT);
                int bx = x + w - 4;
                bx = btn(cc, bx, y + 4, "TPA", BLUE, m1, m2, () -> Manager.tpa(r.name));
                bx = btn(cc, bx, y + 4, "STATS", YEL, m1, m2, () -> Manager.stats(r.name));
                bx = btn(cc, bx, y + 4, "Usuń", RED, m1, m2, () -> {
                    Store.history.remove(Store.key(r.name));
                    Store.save();
                });
                if (!isTracked(r)) btn(cc, bx, y + 4, "+", GREEN, m1, m2, () -> Store.track(r));
            });
        }
        list(c, mx, my, 2, px + 10, cy0 + 24, pw - 20, h - 28, rows, RH, "Historia jest pusta.");
    }

    // ---------- zakladka: sledzeni ----------
    private void drawTracked(DrawContext c, int mx, int my) {
        int h = cy1 - cy0;
        card(c, px + 6, cy0, pw - 12, h);
        List<Rec> all = new ArrayList<>(Store.tracked.values());
        all.sort(Comparator.comparingInt((Rec r) -> status(r.name).rank).thenComparing(r -> r.name.toLowerCase(Locale.ROOT)));
        text(c, "Śledzeni (" + all.size() + ")", px + 14, cy0 + 7, ACC);
        btn(c, px + pw - 14 - 150 - 4, cy0 + 5, "Dodaj", GREEN, mx, my, this::addFromField);

        long now = System.currentTimeMillis();
        List<Row> rows = new ArrayList<>();
        for (Rec r : all) {
            rows.add((cc, x, y, w, m1, m2) -> {
                St st = status(r.name);
                String sub = st.text + "  •  próby " + r.attempts + "  •  udane " + r.successes
                        + (r.lastAttempt > 0 ? "  •  ost. " + ago(now - r.lastAttempt) : "");
                recFrame(cc, x, y, w, r, m1, m2, sub, st.color);
                int bx = x + w - 4;
                bx = btn(cc, bx, y + 4, "Usuń", RED, m1, m2, () -> Store.untrack(r.name));
                bx = btn(cc, bx, y + 4, "STATS", YEL, m1, m2, () -> Manager.stats(r.name));
                bx = btn(cc, bx, y + 4, "TPA", BLUE, m1, m2, () -> Manager.tpa(r.name));
                btn(cc, bx, y + 4, "AutoTP " + (r.autoTp ? "ON" : "OFF"), r.autoTp ? GREEN : GRAY, m1, m2, () -> {
                    r.autoTp = !r.autoTp;
                    Store.save();
                });
            });
        }
        list(c, mx, my, 3, px + 10, cy0 + 24, pw - 20, h - 28, rows, RH,
                "Brak. Dodaj gracza przyciskiem + (W pobliżu / Historia) albo wpisz nick.");
    }

    private void addFromField() {
        String n = field.getText().trim();
        if (!n.matches("\\w{1,16}")) return;
        Rec src = Store.history.get(Store.key(n));
        Rec r = src != null ? src : new Rec(n);
        Store.track(r);
        field.setText("");
    }

    // ---------- zakladka: ustawienia ----------
    private Row toggle(String label, boolean val, Runnable flip) {
        return (c, x, y, w, mx, my) -> {
            rr(c, x, y, w, 18, a(ROW));
            text(c, label, x + 6, y + 5, TXT);
            btn(c, x + w - 4, y + 3, val ? "ON" : "OFF", val ? GREEN : RED, mx, my, () -> {
                flip.run();
                Config.save();
            });
        };
    }

    private Row num(String label, IntSupplier get, IntConsumer set, int step, int min, int max) {
        return (c, x, y, w, mx, my) -> {
            rr(c, x, y, w, 18, a(ROW));
            text(c, label, x + 6, y + 5, TXT);
            int bx = x + w - 4;
            bx = btn(c, bx, y + 3, "+", GREEN, mx, my, () -> {
                set.accept(Math.min(max, get.getAsInt() + step));
                Config.save();
            });
            String v = String.valueOf(get.getAsInt());
            int vw = Math.max(26, textRenderer.getWidth(v) + 6);
            text(c, v, bx - vw + (vw - textRenderer.getWidth(v)) / 2, y + 5, ACC);
            bx -= vw;
            btn(c, bx, y + 3, "-", RED, mx, my, () -> {
                set.accept(Math.max(min, get.getAsInt() - step));
                Config.save();
            });
        };
    }

    private void drawSettings(DrawContext c, int mx, int my) {
        int colW = (pw - 18) / 2;
        int lx = px + 6, rx = lx + colW + 6;
        int h = cy1 - cy0;
        card(c, lx, cy0, colW, h);
        card(c, rx, cy0, colW, h);
        text(c, "Ustawienia", lx + 8, cy0 + 6, ACC);

        Config.Data d = Config.d;
        List<Row> rows = new ArrayList<>();
        rows.add(toggle("AutoTP (globalny)", d.autoTp, () -> d.autoTp = !d.autoTp));
        rows.add(toggle("AutoTP dla Zniknięci", d.autoTpVanished, () -> d.autoTpVanished = !d.autoTpVanished));
        rows.add(toggle("Sprawdź /tpa po zniknięciu", d.verifyOnVanish, () -> d.verifyOnVanish = !d.verifyOnVanish));
        rows.add(toggle("Dźwięk alertu", d.sound, () -> d.sound = !d.sound));
        rows.add(toggle("Powiadomienie na ekranie", d.notify, () -> d.notify = !d.notify));
        rows.add(num("Cooldown /tpa (s)", () -> d.tpaCooldown, v -> d.tpaCooldown = v, 1, 3, 300));
        rows.add(num("Cooldown offline (s)", () -> d.offlineCooldown, v -> d.offlineCooldown = v, 5, 10, 600));
        rows.add(num("Cooldown po 'już wysłano' (s)", () -> d.pendingCooldown, v -> d.pendingCooldown = v, 5, 5, 300));
        rows.add(num("Zasięg skanowania (m)", () -> d.range, v -> d.range = v, 16, 16, 512));
        list(c, mx, my, 4, lx + 4, cy0 + 20, colW - 8, h - 36, rows, 20, "");
        text(c, "Wzorce czatu: config/gearrender/config.json", lx + 8, cy1 - 12, MUT);

        // lista poszukiwanych
        text(c, "Szukane przedmioty (" + d.watch.size() + ")", rx + 8, cy0 + 6, YEL);
        int bx = rx + colW - 6;
        bx = btn(c, bx, cy0 + 4, "Wyczyść", RED, mx, my, () -> {
            d.watch.clear();
            Config.save();
        });
        btn(c, bx, cy0 + 4, "Domyślne", GREEN, mx, my, () -> {
            Config.resetWatch();
            Config.save();
        });
        int rw = colW - 8;
        int cols = Math.max(1, (rw - 10) / 20);
        List<String> ids = new ArrayList<>(d.watch);
        List<Row> grid = new ArrayList<>();
        for (int i = 0; i < ids.size(); i += cols) {
            List<String> chunk = ids.subList(i, Math.min(ids.size(), i + cols));
            grid.add((cc, x, y, w, m1, m2) -> {
                for (int j = 0; j < chunk.size(); j++) {
                    String id = chunk.get(j);
                    int ix = x + 4 + j * 20;
                    boolean hov = m1 >= ix && m1 < ix + 20 && m2 >= y && m2 < y + 20 && m2 >= clipY0 && m2 < clipY1;
                    if (hov) rr(cc, ix, y, 20, 20, 0xFF3A2A2E);
                    ItemStack s = Manager.stack(id);
                    cc.drawItem(s, ix + 2, y + 2);
                    if (hov) hover = s;
                    hits.add(new Hit(ix, y, ix + 20, y + 20, clipY0, clipY1, () -> {
                        Config.d.watch.remove(id);
                        Config.save();
                    }));
                }
            });
        }
        list(c, mx, my, 5, rx + 4, cy0 + 22, rw, h - 38, grid, 20, "Lista pusta.");
        text(c, "Klik = usuń  •  Ctrl+A na przedmiocie w GUI = dodaj/usuń", rx + 8, cy1 - 12, MUT);
    }

    // ---------- input ----------
    @Override
    public boolean mouseClicked(double mx, double my, int button) {
        if (button == 0) {
            for (int i = hits.size() - 1; i >= 0; i--) {
                Hit h = hits.get(i);
                if (mx >= h.x1 && mx < h.x2 && my >= h.y1 && my < h.y2 && my >= h.cy0 && my < h.cy1) {
                    MinecraftClient.getInstance().getSoundManager()
                            .play(PositionedSoundInstance.master(SoundEvents.UI_BUTTON_CLICK.value(), 1.0f));
                    h.r.run();
                    return true;
                }
            }
        }
        return super.mouseClicked(mx, my, button);
    }

    @Override
    public boolean mouseScrolled(double mx, double my, double hAmount, double vAmount) {
        boolean left = mx < px + pw / 2.0;
        int id = switch (tab) {
            case MAIN -> left ? 0 : 1;
            case HISTORY -> 2;
            case TRACKED -> 3;
            case SETTINGS -> left ? 4 : 5;
        };
        scroll[id] -= (int) (vAmount * 20);
        return true;
    }

    @Override
    public boolean keyPressed(int keyCode, int scanCode, int modifiers) {
        if (tab == Tab.TRACKED && field.isFocused()
                && (keyCode == GLFW.GLFW_KEY_ENTER || keyCode == GLFW.GLFW_KEY_KP_ENTER)) {
            addFromField();
            return true;
        }
        return super.keyPressed(keyCode, scanCode, modifiers);
    }
}
