package com.atsukigames.serverinfo;

import net.minecraft.server.MinecraftServer;
import net.minecraft.util.math.BlockPos;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;

public class ServerConfigYaml {
    public static List<ServerInfo.MenuEntry> ENTRIES = new ArrayList<>();
    public static void loadAndApply(MinecraftServer server) {
        try {
            // 既定はサーバールートの config.yml を参照
            Path path = server.getRunDirectory().resolve("config.yml");
            if (!Files.exists(path)) return;

            LoaderOptions opt = new LoaderOptions();
            opt.setAllowDuplicateKeys(false);
            opt.setMaxAliasesForCollections(0);
            Yaml yaml = new Yaml(opt);

            String raw = Files.readString(path, StandardCharsets.UTF_8);
            Object rootObj = yaml.load(raw);
            if (!(rootObj instanceof Map)) return;
            Map<?,?> root = (Map<?,?>) rootObj;

            // menu -> entries
            ENTRIES.clear();
            Object menu = root.get("menu");
            if (menu instanceof List<?> list) {
                for (Object o : list) {
                    if (o instanceof Map<?,?> m) {
                        String sid = String.valueOf(m.get("server"));
                        String disp = String.valueOf(m.get("display"));
                        if (sid != null && disp != null && !sid.isEmpty() && !disp.isEmpty()) {
                            ENTRIES.add(new ServerInfo.MenuEntry(sid, disp));
                        }
                    }
                }
            }

            // lock -> {x,y,z}
            Object lock = root.get("lock");
            if (lock instanceof Map<?,?> l) {
                int x = toInt(l.get("x"), 0);
                int y = toInt(l.get("y"), 0);
                int z = toInt(l.get("z"), 0);
                ServerInfo.LOCK_POS = new BlockPos(x, y, z);
            }

            // world -> {time, weather}
            Object world = root.get("world");
            if (world instanceof Map<?,?> w) {
                long tm = toLong(w.get("time"), 6000L);
                String weather = String.valueOf(w.get("weather"));
                ServerInfo.WORLD_TIME = tm;
                if (weather != null) {
                    String s = weather.toLowerCase(Locale.ROOT);
                    if (s.equals("clear"))      { ServerInfo.WEATHER_CLEAR=true;  ServerInfo.WEATHER_RAIN=false; ServerInfo.WEATHER_THUNDER=false; }
                    else if (s.equals("rain"))  { ServerInfo.WEATHER_CLEAR=false; ServerInfo.WEATHER_RAIN=true;  ServerInfo.WEATHER_THUNDER=false; }
                    else if (s.equals("thunder")){ServerInfo.WEATHER_CLEAR=false; ServerInfo.WEATHER_RAIN=true;  ServerInfo.WEATHER_THUNDER=true; }
                }
            }

            // selector -> {yaw_span, subtitle}
            Object selector = root.get("selector");
            if (selector instanceof Map<?,?> s) {
                double span = toDouble(s.get("yaw_span"), 45.0);
                String sub = String.valueOf(s.get("subtitle"));
                ServerInfo.YAW_SPAN = span;
                if (sub != null && !sub.equals("null")) {
                    ServerInfo.SUBTITLE_TEXT = sub;
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private static int toInt(Object o, int def)    { try { return (o==null)?def:Integer.parseInt(String.valueOf(o)); } catch(Exception e){ return def; } }
    private static long toLong(Object o, long def) { try { return (o==null)?def:Long.parseLong(String.valueOf(o)); } catch(Exception e){ return def; } }
    private static double toDouble(Object o,double def){ try { return (o==null)?def:Double.parseDouble(String.valueOf(o)); } catch(Exception e){ return def; } }
}
