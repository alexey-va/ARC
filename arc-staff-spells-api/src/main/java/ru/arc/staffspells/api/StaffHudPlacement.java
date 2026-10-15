package ru.arc.staffspells.api;

/** Vertical placement for the hologram staff spell HUD. */
public enum StaffHudPlacement {
    TOP("top"),
    BOTTOM("bottom");

    public static final String META_KEY = "arc-staff-hud-placement";
    public static final StaffHudPlacement DEFAULT = BOTTOM;

    private final String id;

    StaffHudPlacement(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static StaffHudPlacement fromStored(String stored) {
        if (stored == null || stored.isBlank()) return DEFAULT;
        for (StaffHudPlacement placement : values()) {
            if (placement.id.equalsIgnoreCase(stored.trim())) return placement;
        }
        return DEFAULT;
    }
}
