package dev.gearrender;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.AbstractClientPlayerEntity;
import net.minecraft.client.sound.PositionedSoundInstance;
import net.minecraft.entity.EquipmentSlot;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.registry.Registries;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Vec3d;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Cala logika: skan graczy, zniknieci, czat, kolejka komend, AutoTP. */
public final class Manager {
    public static class RT {
        public boolean offline;
        public long nextAllowed;
        public long pendingUntil;
    }

    private record Cmd(String cmd, String nick, boolean tpa) {}

    public static final Map<String, Rec> nearby = new LinkedHashMap<>();
    public static final Map<String, Rec> vanished = new LinkedHashMap<>();
    public static final Set<String> world = new HashSet<>();
    public static final Map<String, RT> rt = new HashMap<>();
    private static final ArrayDeque<Cmd> queue = new ArrayDeque<>();
    private static final Map<String, ItemStack> STACKS = new HashMap<>();

    private static Vec3d lastPos;
    private static Object lastWorld;
    private static int ticks;
    private static long lastSend;
    private static String lastTarget;
    private static long lastTargetAt;
    private static Pattern pSent, pAlready, pNot, pAcc;

    public static RT rt(String k) { return rt.computeIfAbsent(k, x -> new RT()); }
    public static int queueSize() { return queue.size(); }

    // ---------- util ----------
    public static String id(ItemStack s) {
        return s.isEmpty() ? "" : Registries.ITEM.getId(s.getItem()).toString();
    }

    public static ItemStack stack(String id) {
        return STACKS.computeIfAbsent(id, i -> {
            Identifier ident = Identifier.tryParse(i);
            Item it = ident == null ? Items.AIR : Registries.ITEM.get(ident);
            return new ItemStack(it);
        });
    }

    public static void compile() {
        pSent = comp(Config.d.sentRegex);
        pAlready = comp(Config.d.alreadyRegex);
        pNot = comp(Config.d.notFoundRegex);
        pAcc = comp(Config.d.acceptedRegex);
    }

    private static Pattern comp(String s) {
        try {
            return s == null || s.isBlank() ? null : Pattern.compile(s);
        } catch (Exception e) {
            GearRenderClient.LOG.error("zly regex: " + s, e);
            return null;
        }
    }

    public static boolean matches(Rec r) {
        for (String s : r.slots()) if (!s.isEmpty() && Config.d.watch.contains(s)) return true;
        return false;
    }

    public static void reset() {
        nearby.clear(); vanished.clear(); world.clear(); rt.clear(); queue.clear();
        lastPos = null; lastWorld = null;
    }

    // ---------- tick ----------
    public static void tick(MinecraftClient mc) {
        if (mc.player == null || mc.world == null || mc.getNetworkHandler() == null) return;
        ticks++;
        if (ticks % 10 == 0) scan(mc);
        if (ticks % 20 == 0) autoTp();
        if (ticks % 200 == 0 && Store.dirty) Store.save();
        pump(mc);
    }

    private static Rec snap(AbstractClientPlayerEntity p, String name) {
        Rec r = new Rec(name);
        r.head = id(p.getEquippedStack(EquipmentSlot.HEAD));
        r.chest = id(p.getEquippedStack(EquipmentSlot.CHEST));
        r.legs = id(p.getEquippedStack(EquipmentSlot.LEGS));
        r.feet = id(p.getEquippedStack(EquipmentSlot.FEET));
        r.main = id(p.getMainHandStack());
        r.off = id(p.getOffHandStack());
        r.lastSeen = System.currentTimeMillis();
        return r;
    }

    private static void scan(MinecraftClient mc) {
        long now = System.currentTimeMillis();
        Vec3d pos = mc.player.getPos();
        boolean jumped = lastWorld != mc.world || (lastPos != null && lastPos.squaredDistanceTo(pos) > 32 * 32);
        lastPos = pos;
        lastWorld = mc.world;

        Map<String, Rec> cur = new LinkedHashMap<>();
        world.clear();
        for (AbstractClientPlayerEntity p : mc.world.getPlayers()) {
            if (p == mc.player) continue;
            String name = p.getName().getString();
            String k = Store.key(name);
            world.add(k);
            double dist = p.distanceTo(mc.player);
            if (dist > Config.d.range) continue;
            Rec r = snap(p, name);
            r.dist = dist;
            if (matches(r) || Store.tracked.containsKey(k)) cur.put(k, r);
            Rec t = Store.tracked.get(k);
            if (t != null) { t.copyGear(r); Store.dirty = true; }
        }

        // przybyli
        for (Map.Entry<String, Rec> e : cur.entrySet()) {
            String k = e.getKey();
            boolean fresh = !nearby.containsKey(k);
            vanished.remove(k);
            RT r = rt.get(k);
            if (r != null) r.offline = false;
            if (fresh) {
                if (r != null && r.pendingUntil > now && e.getValue().dist < 64) {
                    Rec t = Store.tracked.get(k);
                    if (t != null) { t.successes++; Store.dirty = true; }
                    r.pendingUntil = 0;
                }
                if (!jumped && matches(e.getValue())) alert(mc, e.getValue());
            }
        }

        // odeszli
        if (!jumped) {
            for (Map.Entry<String, Rec> e : new ArrayList<>(nearby.entrySet())) {
                String k = e.getKey();
                if (!cur.containsKey(k) && !world.contains(k)) vanish(e.getValue(), now);
            }
        }

        nearby.clear();
        nearby.putAll(cur);
    }

    private static void alert(MinecraftClient mc, Rec r) {
        if (Config.d.notify) mc.inGameHud.setOverlayMessage(Text.literal("Gear Render: " + r.name + " w pobliżu"), false);
        if (Config.d.sound)
            mc.getSoundManager().play(PositionedSoundInstance.master(SoundEvents.BLOCK_NOTE_BLOCK_PLING.value(), 1.6f));
    }

    private static void vanish(Rec r, long now) {
        String k = Store.key(r.name);
        r.vanishedAt = now;
        vanished.put(k, r);
        Store.addHistory(r);
        RT t = rt(k);
        if (Config.d.verifyOnVanish && t.nextAllowed <= now) requestTpa(r.name);
    }

    // ---------- czat ----------
    public static void onMessage(Text t) {
        String s = t.getString();
        long now = System.currentTimeMillis();
        Matcher m;
        if (pAcc != null && lastTarget != null && pAcc.matcher(s).find()) {
            RT r = rt(Store.key(lastTarget));
            r.pendingUntil = 0;
            Rec tr = Store.tracked.get(Store.key(lastTarget));
            if (tr != null) { tr.successes++; Store.dirty = true; }
            return;
        }
        if (pAlready != null && (m = pAlready.matcher(s)).find() && m.groupCount() >= 1) {
            RT r = rt(Store.key(m.group(1)));
            r.offline = false;
            r.pendingUntil = now + 30000;
            r.nextAllowed = Math.max(r.nextAllowed, now + Config.d.pendingCooldown * 1000L);
            return;
        }
        if (pSent != null && (m = pSent.matcher(s)).find() && m.groupCount() >= 1) {
            RT r = rt(Store.key(m.group(1)));
            r.offline = false;
            r.pendingUntil = now + 30000;
            r.nextAllowed = Math.max(r.nextAllowed, now + Config.d.tpaCooldown * 1000L);
            return;
        }
        if (pNot != null && pNot.matcher(s).find()) {
            if (lastTarget != null && now - lastTargetAt < 8000) {
                String k = Store.key(lastTarget);
                RT r = rt(k);
                r.offline = true;
                r.pendingUntil = 0;
                r.nextAllowed = now + Config.d.offlineCooldown * 1000L;
                vanished.remove(k);
            }
        }
    }

    // ---------- komendy ----------
    private static void enqueue(String cmd, String nick, boolean tpa) {
        if (tpa) for (Cmd c : queue) if (c.tpa && c.nick.equalsIgnoreCase(nick)) return;
        if (queue.size() > 30) return;
        queue.add(new Cmd(cmd, nick, tpa));
    }

    public static void tpa(String name) { enqueue("tpa " + name, name, true); }
    public static void stats(String name) { enqueue("stats " + name, name, false); }

    private static void requestTpa(String name) {
        RT r = rt(Store.key(name));
        r.nextAllowed = System.currentTimeMillis() + (r.offline ? Config.d.offlineCooldown : Config.d.tpaCooldown) * 1000L;
        tpa(name);
    }

    private static void pump(MinecraftClient mc) {
        long now = System.currentTimeMillis();
        if (queue.isEmpty() || now - lastSend < Config.d.commandGapMs) return;
        Cmd c = queue.poll();
        mc.getNetworkHandler().sendChatCommand(c.cmd);
        lastSend = now;
        if (c.tpa) {
            lastTarget = c.nick;
            lastTargetAt = now;
            Rec t = Store.tracked.get(Store.key(c.nick));
            if (t != null) { t.attempts++; t.lastAttempt = now; Store.dirty = true; }
        }
    }

    // ---------- AutoTP ----------
    private static void autoTp() {
        if (!Config.d.autoTp) return;
        long now = System.currentTimeMillis();
        // jestes juz przy kims ze sledzonych -> pauza
        for (Rec t : Store.tracked.values()) {
            if (!t.autoTp) continue;
            Rec n = nearby.get(Store.key(t.name));
            if (n != null && n.dist < 48) return;
        }
        LinkedHashMap<String, String> cands = new LinkedHashMap<>();
        for (Rec t : Store.tracked.values()) if (t.autoTp) cands.put(Store.key(t.name), t.name);
        if (Config.d.autoTpVanished) for (Rec v : vanished.values()) cands.putIfAbsent(Store.key(v.name), v.name);
        for (Map.Entry<String, String> e : cands.entrySet()) {
            if (world.contains(e.getKey())) continue;
            RT r = rt(e.getKey());
            if (r.nextAllowed > now) continue;
            requestTpa(e.getValue());
            break;
        }
    }
}
