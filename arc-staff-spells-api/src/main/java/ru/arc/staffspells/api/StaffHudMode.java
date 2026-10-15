package ru.arc.staffspells.api;

/** Player-selected presentation for the staff spell HUD. */
public enum StaffHudMode {
    HOLOGRAM("hologram"),
    BOSSBAR("bossbar"),
    OFF("off");

    public static final String META_KEY = "arc-staff-hud-mode";
    public static final StaffHudMode DEFAULT = HOLOGRAM;

    private final String id;

    StaffHudMode(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static StaffHudMode fromStored(String stored) {
        if (stored == null || stored.isBlank()) return DEFAULT;
        for (StaffHudMode mode : values()) {
            if (mode.id.equalsIgnoreCase(stored.trim())) return mode;
        }
        return DEFAULT;
    }
}
