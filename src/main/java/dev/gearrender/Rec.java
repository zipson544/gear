package dev.gearrender;

/** Zapis gracza: nick + sprzet + dane sledzenia. */
public class Rec {
    public String name = "";
    public String head = "", chest = "", legs = "", feet = "", main = "", off = "";
    public long lastSeen;
    public boolean autoTp = true;
    public int attempts, successes;
    public long lastAttempt;
    public transient double dist;
    public transient long vanishedAt;

    public Rec() {}

    public Rec(String name) { this.name = name; }

    public String[] slots() { return new String[]{head, chest, legs, feet, main, off}; }

    public void copyGear(Rec o) {
        head = o.head; chest = o.chest; legs = o.legs; feet = o.feet;
        main = o.main; off = o.off; lastSeen = o.lastSeen;
    }

    public Rec copy() {
        Rec r = new Rec(name);
        r.copyGear(this);
        r.autoTp = autoTp; r.attempts = attempts; r.successes = successes;
        r.lastAttempt = lastAttempt; r.dist = dist; r.vanishedAt = vanishedAt;
        return r;
    }
}
