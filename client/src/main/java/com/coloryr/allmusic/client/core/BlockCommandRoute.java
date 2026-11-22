package com.coloryr.allmusic.client.core;

public final class BlockCommandRoute {
    private BlockCommandRoute() {
    }

    public static boolean matches(String command) {
        String[] parts = command.trim().split("\\s+", 3);
        return parts.length >= 2 && parts[0].equals("music") && parts[1].equals("block");
    }
}
