package com.atsukigames.serverinfo;

import net.minecraft.server.MinecraftServer;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

public class ConfigManager {

    private static final String REL_PATH = "config/config.yml";

    public static void ensureConfig(MinecraftServer server) {
        Path path = server.getRunDirectory().resolve(REL_PATH);
        try {
            if (!Files.exists(path)) {
                Files.createDirectories(path.getParent());
                List<String> lines = Arrays.asList(
                    "# [server = display]",
                    "s1 = s1",
                    "s2 = s2",
                    "c1 = c1"
                );
                Files.write(path, lines, StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    public static List<ServerInfo.MenuEntry> load(MinecraftServer server) {
        Path path = server.getRunDirectory().resolve(REL_PATH);
        List<ServerInfo.MenuEntry> list = new ArrayList<>();
        try {
            if (Files.exists(path)) {
                List<String> lines = Files.readAllLines(path, StandardCharsets.UTF_8);
                for (String raw : lines) {
                    String line = raw.trim();
                    if (line.isEmpty() || line.startsWith("#")) continue;
                    String[] kv = line.split("=", 2);
                    if (kv.length == 2) {
                        String key = kv[0].trim();
                        String val = kv[1].trim();
                        if (!key.isEmpty() && !val.isEmpty()) {
                            list.add(new ServerInfo.MenuEntry(key, val));
                        }
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        if (list.isEmpty()) {
            list.add(new ServerInfo.MenuEntry("s1", "s1"));
            list.add(new ServerInfo.MenuEntry("s2", "s2"));
            list.add(new ServerInfo.MenuEntry("c1", "c1"));
        }
        return list;
    }
}
