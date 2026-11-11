package dev.gearrender;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.registry.Registries;
import net.minecraft.util.Identifier;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

public final class Config {
    public static class Data {
        public boolean autoTp = false;
        public boolean autoTpVanished = true;
        public boolean verifyOnVanish = true;
        public boolean sound = true;
        public boolean notify = true;
        public int tpaCooldown = 10;
        public int offlineCooldown = 60;
        public int pendingCooldown = 25;
        public int range = 128;
        public int commandGapMs = 1500;
        // Wzorce czatu (regex, grupa 1 = nick). Dopasuj do swojego serwera.
        public String sentRegex = "(?i)wys.ano pro.b. o teleportacj. do gracza (\\w+)";
        public String alreadyRegex = "(?i)wys.a.e. ju. pro.b. o teleportacj. do gracza (\\w+)";
        public String notFoundRegex = "(?i)nie odnaleziono takiego gracza";
        public String acceptedRegex = "";
        public boolean watchInit = false;
        public Set<String> watch = new LinkedHashSet<>();
    }

    public static Data d = new Data();
    private static final Gson G = new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();

    public static Path dir() { return FabricLoader.getInstance().getConfigDir().resolve("gearrender"); }

    public static void load() {
        try {
            Files.createDirectories(dir());
            Path f = dir().resolve("config.json");
            if (Files.exists(f)) {
                Data x = G.fromJson(Files.readString(f), Data.class);
                if (x != null) d = x;
            }
            if (d.watch == null) d.watch = new LinkedHashSet<>();
            if (!d.watchInit) { resetWatch(); d.watchInit = true; }
            save();
        } catch (Exception e) {
            GearRenderClient.LOG.error("config load", e);
        }
    }

    public static void save() {
        try {
            Files.createDirectories(dir());
            Files.writeString(dir().resolve("config.json"), G.toJson(d));
        } catch (Exception e) {
            GearRenderClient.LOG.error("config save", e);
        }
    }

    /** Domyslnie: caly netherite (zbroja + narzedzia + miecz) i elytra. */
    public static void resetWatch() {
        String[] types = {"sword", "pickaxe", "axe", "shovel", "hoe", "helmet", "chestplate", "leggings", "boots"};
        TreeSet<String> out = new TreeSet<>();
        for (Identifier id : Registries.ITEM.getIds()) {
            if (!id.getNamespace().equals("minecraft")) continue;
            String p = id.getPath();
            if (p.equals("elytra")) out.add(id.toString());
            else for (String t : types) if (p.equals("netherite_" + t)) out.add(id.toString());
        }
        d.watch = new LinkedHashSet<>(out);
    }
}
