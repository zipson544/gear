package dev.gearrender;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

/** Historia (wszyscy zniknieci) i Sledzeni (lista celow na stale). */
public final class Store {
    public static final Map<String, Rec> history = new LinkedHashMap<>();
    public static final Map<String, Rec> tracked = new LinkedHashMap<>();
    public static boolean dirty;
    private static final Gson G = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
    private static final Type LIST = new TypeToken<List<Rec>>() {}.getType();

    public static String key(String n) { return n.toLowerCase(Locale.ROOT); }

    public static void load() {
        read("history.json", history);
        read("tracked.json", tracked);
    }

    private static void read(String file, Map<String, Rec> into) {
        try {
            Path f = Config.dir().resolve(file);
            if (!Files.exists(f)) return;
            List<Rec> list = G.fromJson(Files.readString(f), LIST);
            if (list == null) return;
            for (Rec r : list) if (r != null && r.name != null && !r.name.isEmpty()) into.put(key(r.name), r);
        } catch (Exception e) {
            GearRenderClient.LOG.error("store load " + file, e);
        }
    }

    public static void save() {
        try {
            Files.createDirectories(Config.dir());
            Files.writeString(Config.dir().resolve("history.json"), G.toJson(new ArrayList<>(history.values()), LIST));
            Files.writeString(Config.dir().resolve("tracked.json"), G.toJson(new ArrayList<>(tracked.values()), LIST));
            dirty = false;
        } catch (Exception e) {
            GearRenderClient.LOG.error("store save", e);
        }
    }

    public static void addHistory(Rec r) {
        String k = key(r.name);
        Rec h = history.get(k);
        if (h == null) {
            history.put(k, r.copy());
            while (history.size() > 500) {
                Iterator<String> it = history.keySet().iterator();
                it.next();
                it.remove();
            }
        } else {
            h.copyGear(r);
        }
        dirty = true;
    }

    public static void track(Rec r) {
        Rec c = r.copy();
        c.autoTp = true;
        tracked.putIfAbsent(key(r.name), c);
        save();
    }

    public static void untrack(String name) {
        tracked.remove(key(name));
        save();
    }
}
