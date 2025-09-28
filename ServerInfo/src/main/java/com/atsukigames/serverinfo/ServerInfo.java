package com.atsukigames.serverinfo;

import net.fabricmc.api.DedicatedServerModInitializer;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.event.player.UseItemCallback;
import net.fabricmc.fabric.api.event.player.AttackBlockCallback;
import net.fabricmc.fabric.api.event.player.AttackEntityCallback;
import net.fabricmc.fabric.api.event.player.UseBlockCallback;
import net.fabricmc.fabric.api.event.player.UseEntityCallback;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;

import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;

import net.minecraft.util.ActionResult;
import net.minecraft.util.math.*;
import net.minecraft.text.*;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;

import net.minecraft.network.packet.s2c.play.TitleS2CPacket;
import net.minecraft.network.packet.s2c.play.SubtitleS2CPacket;
import net.minecraft.network.packet.s2c.play.ClearTitleS2CPacket;
import net.minecraft.network.packet.s2c.play.TitleFadeS2CPacket;
import net.minecraft.network.packet.s2c.play.GameMessageS2CPacket;

import net.minecraft.network.packet.CustomPayload;
import net.minecraft.network.codec.PacketCodec;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import com.google.gson.*;

public class ServerInfo implements DedicatedServerModInitializer {

    public static final String MOD_ID = "serverinfo";
    public static BlockPos LOCK_POS = new BlockPos(0, 0, 0);

    // 入力抑制
    private static final long CONFIRM_COOLDOWN_MS = 500L;   // 0.5s
    private static final long CONNECT_LOCK_MS     = 1200L;  // 1.2s（短縮）

    private static final Map<UUID, PlayerMenu> MENUS = new ConcurrentHashMap<>();
    private static List<MenuEntry> ENTRIES = new ArrayList<>();
public static long WORLD_TIME = 6000L;
public static boolean WEATHER_CLEAR = true, WEATHER_RAIN = false, WEATHER_THUNDER = false;
public static double YAW_SPAN = 45.0;
public static String SUBTITLE_TEXT = "左右を向いて選択・左クリックで決定";
// tick counter
    private static long SERVER_TICKS = 0L;

    // actionbar playlist
    private static List<ActionBarEntry> ACTIONBARS = new ArrayList<>();

    // Bungee/Velocity 互換 S2C（bungeecord:main）: 生バイト
    public static record BungeeConnectS2CPayload(byte[] data) implements CustomPayload {
        public static final CustomPayload.Id<BungeeConnectS2CPayload> ID =
                new CustomPayload.Id<>(net.minecraft.util.Identifier.of("bungeecord", "main"));
        public static final PacketCodec<net.minecraft.network.PacketByteBuf, BungeeConnectS2CPayload> CODEC =
                PacketCodec.ofStatic(
                        (buf, p) -> buf.writeBytes(p.data),
                        b -> { byte[] arr = new byte[b.readableBytes()]; b.readBytes(arr); return new BungeeConnectS2CPayload(arr); }
                );
        @Override public Id<? extends CustomPayload> getId() { return ID; }
    }

    @Override
    public void onInitializeServer() {
        PayloadTypeRegistry.playS2C().register(BungeeConnectS2CPayload.ID, BungeeConnectS2CPayload.CODEC);

        ServerLifecycleEvents.SERVER_STARTING.register(server -> {
            ServerConfigYaml.loadAndApply(server); ENTRIES = ServerConfigYaml.ENTRIES;
            loadActionBars(server);
        });

        ServerTickEvents.START_SERVER_TICK.register(server -> {
            SERVER_TICKS++;
            for (ServerWorld world : server.getWorlds()) {
                world.setTimeOfDay(WORLD_TIME); world.setWeather(1000000, 0, WEATHER_RAIN, WEATHER_THUNDER);
            }
        });

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            setupPlayer(server, player);
        });
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            ServerPlayerEntity player = handler.getPlayer();
            MENUS.remove(player.getUuid());
            player.networkHandler.sendPacket(new ClearTitleS2CPacket(true));
        });

        ServerTickEvents.END_SERVER_TICK.register(server -> {
            for (ServerPlayerEntity p : server.getPlayerManager().getPlayerList()) {
                lockAndInvis(p);
                updateSelectionByYaw(p);
                tickActionBar(p);
            }
        });

        // クリック網羅（素手OK）
        AttackBlockCallback.EVENT.register((player, world, hand, pos, dir) -> {
            if (player instanceof ServerPlayerEntity spe && trySelect(spe)) return ActionResult.SUCCESS;
            return ActionResult.PASS;
        });
        AttackEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (player instanceof ServerPlayerEntity spe && trySelect(spe)) return ActionResult.SUCCESS;
            return ActionResult.PASS;
        });
        UseBlockCallback.EVENT.register((player, world, hand, hit) -> {
            if (player instanceof ServerPlayerEntity spe && trySelect(spe)) return ActionResult.SUCCESS;
            return ActionResult.PASS;
        });
        UseEntityCallback.EVENT.register((player, world, hand, entity, hit) -> {
            if (player instanceof ServerPlayerEntity spe && trySelect(spe)) return ActionResult.SUCCESS;
            return ActionResult.PASS;
        });
        UseItemCallback.EVENT.register((player, world, hand) -> {
            if (player instanceof ServerPlayerEntity spe && trySelect(spe)) return ActionResult.SUCCESS;
            return ActionResult.PASS;
        });
    }

    private void setupPlayer(MinecraftServer server, ServerPlayerEntity player) {
        Vec3d target = new Vec3d(LOCK_POS.getX() + 0.5, LOCK_POS.getY(), LOCK_POS.getZ() + 0.5);
        player.requestTeleport(target.x, target.y, target.z);
        player.setVelocity(Vec3d.ZERO);
        player.fallDistance = 0f;

        player.addStatusEffect(new StatusEffectInstance(StatusEffects.INVISIBILITY, Integer.MAX_VALUE, 0, true, false));
        player.setInvulnerable(true);

        PlayerMenu menu = MENUS.computeIfAbsent(player.getUuid(), k -> new PlayerMenu());
        menu.setEntries(ENTRIES);
        menu.baseYaw = player.getYaw();
        menu.selectedIndex = Math.min(0, Math.max(0, menu.entries.size() - 1));
        menu.lastConfirmAtMs = 0L;
        menu.connectingUntilMs = 0L;
        menu.abIndex = 0;
        menu.abNextTick = SERVER_TICKS;

        player.networkHandler.sendPacket(new ClearTitleS2CPacket(true));
        player.networkHandler.sendPacket(new TitleFadeS2CPacket(5, 120000, 5));
        sendMenuTitle(player, menu, true);
    }

    private void lockAndInvis(ServerPlayerEntity player) {
        Vec3d pos = player.getPos();
        Vec3d target = new Vec3d(LOCK_POS.getX() + 0.5, LOCK_POS.getY(), LOCK_POS.getZ() + 0.5);
        if (pos.squaredDistanceTo(target) > 0.0001) {
            player.requestTeleport(target.x, target.y, target.z);
            player.setVelocity(Vec3d.ZERO);
            player.fallDistance = 0f;
        }
        if (!player.hasStatusEffect(StatusEffects.INVISIBILITY)) {
            player.addStatusEffect(new StatusEffectInstance(StatusEffects.INVISIBILITY, Integer.MAX_VALUE, 0, true, false));
        }
        if (!player.isInvulnerable()) {
            player.setInvulnerable(true);
        }
    }

    private void updateSelectionByYaw(ServerPlayerEntity player) {
        PlayerMenu menu = MENUS.get(player.getUuid());
        if (menu == null || menu.entries.isEmpty()) return;

        float delta = MathHelper.wrapDegrees(player.getYaw() - menu.baseYaw);
        double halfSpan = YAW_SPAN;
        double clamped = MathHelper.clamp(delta, (float)-halfSpan, (float)halfSpan);
        double t = (clamped + halfSpan) / (2.0 * halfSpan);
        int idx = (int)Math.round(t * (menu.entries.size() - 1));
        idx = MathHelper.clamp(idx, 0, menu.entries.size() - 1);

        if (idx != menu.hoverIndex) {
            menu.hoverIndex = idx;
            sendMenuTitle(player, menu, false);
        }
    }

    // Mixin からも呼べるよう public static
    public static boolean trySelect(ServerPlayerEntity player) {
        if (player == null || player.networkHandler == null || !player.networkHandler.isConnectionOpen()) return false;

        PlayerMenu menu = MENUS.get(player.getUuid());
        if (menu == null || menu.entries.isEmpty()) return false;

        long now = System.currentTimeMillis();

        // 送信前ロックで重複遮断（最優先）
        if (now < menu.connectingUntilMs || (now - menu.lastConfirmAtMs) < CONFIRM_COOLDOWN_MS) {
            return false;
        }

        int idx = menu.getCurrentIndex();
        if (idx < 0 || idx >= menu.entries.size()) return false;

        MenuEntry entry = menu.entries.get(idx);

        // 先にロック
        menu.lastConfirmAtMs = now;
        menu.connectingUntilMs = now + CONNECT_LOCK_MS;

        // ログ
        System.out.println("[ServerInfo] Player " + player.getName().getString() + " clicked to connect to: " + entry.serverId);

        try {
            // subchannel=Connect, value=<serverId>
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(baos)) {
                out.writeUTF("Connect");
                out.writeUTF(entry.serverId);
            }
            byte[] raw = baos.toByteArray();

            // 送信（再送なし）
            ServerPlayNetworking.send(player, new BungeeConnectS2CPayload(raw));
            System.out.println("[ServerInfo] Message sent via bungeecord:main");
            return true;
        } catch (Exception e) {
            System.err.println("[ServerInfo] Failed to send BungeeCord message: " + e.getMessage());
            e.printStackTrace();
            // 障害時はロックを早めに解く（次回試行可）
            menu.connectingUntilMs = now;
            return false;
        }
    }

    private void sendMenuTitle(ServerPlayerEntity player, PlayerMenu menu, boolean withSubtitle) {
        MutableText line = Text.empty();
        for (int i = 0; i < menu.entries.size(); i++) {
            if (i > 0) line.append(Text.literal(" "));
            MenuEntry e = menu.entries.get(i);
            String label = "[" + e.display + "]";
            boolean selected = (i == menu.getCurrentIndex());
            MutableText part = Text.literal(label);
            if (selected) {
                part = part.styled(s -> s.withUnderline(true).withBold(true));
            } else {
                part = part.styled(s -> s.withColor(0xAAAAAA));
            }
            line.append(part);
        }
        player.networkHandler.sendPacket(new TitleS2CPacket(line));
        if (withSubtitle) {
            player.networkHandler.sendPacket(new SubtitleS2CPacket(Text.literal(SUBTITLE_TEXT)));
        }
    }

    // --- ActionBar ---

    private static void loadActionBars(MinecraftServer server) {
        ACTIONBARS.clear();
        Path path1 = server.getRunDirectory().resolve("serverinfo.actionbar.json");
Path path2 = server.getRunDirectory().resolve("config/serverinfo.actionbar.json");
Path path = Files.exists(path1) ? path1 : path2;
        try {
            if (Files.exists(path)) {
                String rawText = Files.readString(path, StandardCharsets.UTF_8);
rawText = stripBom(rawText);
if (looksLikeDsl(rawText)) {
    loadActionBarsFromDsl(rawText);
    return;
}
String json = rawText;JsonObject root;
try {
    root = JsonParser.parseString(json).getAsJsonObject();
} catch (Exception ex) {
    // JSONでなければDSLとして試行
    if (looksLikeDsl(json)) {
        loadActionBarsFromDsl(json);
        return;
    } else {
        throw ex;
    }
}
                JsonArray arr = root.getAsJsonArray("actionbar");
                if (arr != null) {
                    for (JsonElement el : arr) {
                        JsonObject obj = el.getAsJsonObject();
                        JsonArray comps = obj.getAsJsonArray("components");
                        List<Text> parts = new ArrayList<>();
                        if (comps != null) {
                            for (JsonElement ce : comps) {
                                JsonObject c = ce.getAsJsonObject();
                                String t = c.get("text").getAsString();
                                MutableText m = Text.literal(t);
                                if (c.has("color")) m = m.styled(s -> s.withColor(parseColor(c.get("color").getAsString())));
                                if (c.has("bold")) m = m.styled(s -> s.withBold(c.get("bold").getAsBoolean()));
                                if (c.has("italic")) m = m.styled(s -> s.withItalic(c.get("italic").getAsBoolean()));
                                if (c.has("underlined")) m = m.styled(s -> s.withUnderline(c.get("underlined").getAsBoolean()));
                                if (c.has("strikethrough")) m = m.styled(s -> s.withStrikethrough(c.get("strikethrough").getAsBoolean()));
                                if (c.has("obfuscated")) m = m.styled(s -> s.withObfuscated(c.get("obfuscated").getAsBoolean()));
                                parts.add(m);
                            }
                        }
                        String timeStr = obj.get("time").getAsString();
                        long dur = parseDurationToTicks(timeStr);
                        ACTIONBARS.add(new ActionBarEntry(parts, dur));
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (ACTIONBARS.isEmpty()) {
            List<Text> d1 = List.of(
                    Text.literal("WEB").styled(s->s.withColor(0x55FF55).withBold(true)),
                    Text.literal(" >> ").styled(s->s.withColor(0x555555).withBold(true)),
                    Text.literal("https://hikaripage.f5.si").styled(s->s.withColor(0x55FF55).withBold(true))
            );
            List<Text> d2 = List.of(
                    Text.literal("DISCORD").styled(s->s.withColor(0x5555FF).withBold(true)),
                    Text.literal(" >> ").styled(s->s.withColor(0x555555).withBold(true)),
                    Text.literal("https://discord.gg/YpxtcJCNJG").styled(s->s.withColor(0x55FF55).withBold(true))
            );
            ACTIONBARS.add(new ActionBarEntry(d1, 60)); // 3s
            ACTIONBARS.add(new ActionBarEntry(d2, 60)); // 3s
        }
    }

    private static long parseDurationToTicks(String s) {
        try {
            s = s.trim().toLowerCase();
            long mul = 1;
            if (s.endsWith("t")) { mul = 1; s = s.substring(0, s.length()-1); }
            else if (s.endsWith("s")) { mul = 20; s = s.substring(0, s.length()-1); }
            else if (s.endsWith("m")) { mul = 20*60; s = s.substring(0, s.length()-1); }
            else if (s.endsWith("h")) { mul = 20*60*60; s = s.substring(0, s.length()-1); }
            else if (s.endsWith("d")) { mul = 20*60*60*24; s = s.substring(0, s.length()-1); }
            long v = Long.parseLong(s);
            return Math.max(1, v * mul);
        } catch (Exception e) { return 60; }
    }

    private static net.minecraft.text.TextColor parseColor(String s) {
        try {
            return net.minecraft.text.TextColor.parse(s).result().orElse(net.minecraft.text.TextColor.fromRgb(0xFFFFFF));
        } catch (Exception ex) {
            try {
                if (s.startsWith("#")) return net.minecraft.text.TextColor.fromRgb(Integer.parseInt(s.substring(1), 16));
            } catch (Exception ignore) {}
            return net.minecraft.text.TextColor.fromRgb(0xFFFFFF);
        }
    }

    private static void tickActionBar(ServerPlayerEntity p) {
        PlayerMenu menu = MENUS.get(p.getUuid());
        if (menu == null || ACTIONBARS.isEmpty()) return;
        if (SERVER_TICKS < menu.abNextTick) return;

        ActionBarEntry e = ACTIONBARS.get(menu.abIndex % ACTIONBARS.size());
        MutableText line = Text.empty();
        for (Text part : e.parts) line.append(part);

        // overlay=true でアクションバー
        p.networkHandler.sendPacket(new GameMessageS2CPacket(line, true));

        menu.abNextTick = SERVER_TICKS + e.durationTicks;
        menu.abIndex = (menu.abIndex + 1) % ACTIONBARS.size();
    }

    public static class MenuEntry {
        public final String serverId;
        public final String display;
        public MenuEntry(String serverId, String display) {
            this.serverId = serverId; this.display = display;
        }
    }

    public static class PlayerMenu {
        public final List<MenuEntry> entries = new ArrayList<>();
        public int hoverIndex = -1;
        public int selectedIndex = 0;
        public float baseYaw = 0f;
        public long lastConfirmAtMs = 0L;
        public long connectingUntilMs = 0L;
        public int abIndex = 0;
        public long abNextTick = 0L;

        public void setEntries(List<MenuEntry> list) {
            entries.clear();
            entries.addAll(list);
            hoverIndex = Math.min(hoverIndex, entries.size() - 1);
            selectedIndex = Math.min(selectedIndex, entries.size() - 1);
        }
        public void setSelectedIndex(int idx) {
            this.selectedIndex = Math.max(0, Math.min(idx, entries.size() - 1));
        }
        public int getCurrentIndex() {
            return (hoverIndex >= 0) ? hoverIndex : selectedIndex;
        }
    }

    private static class ActionBarEntry {
        final List<Text> parts;
        final long durationTicks;
        ActionBarEntry(List<Text> parts, long durationTicks) {
            this.parts = parts; this.durationTicks = durationTicks;
        }
    }

    // --- actionbar DSL helpers ---
    private static String stripBom(String s) {
        if (s != null && !s.isEmpty() && s.charAt(0) == '\uFEFF') return s.substring(1);
        return s;
    }
    private static boolean looksLikeDsl(String raw) {
        if (raw == null) return false;
        String s = stripBom(raw).stripLeading().toLowerCase(java.util.Locale.ROOT);
        return s.startsWith("actionbar:");
    }
    private static void loadActionBarsFromDsl(String raw) {
        ACTIONBARS.clear();
        String body = raw.substring(raw.indexOf(':') + 1);
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("(?s)\\[(.*?)\\]").matcher(body);
        while (m.find()) {
            String block = m.group(1);
            java.util.List<net.minecraft.text.Text> parts = new java.util.ArrayList<>();
            String timeStr = "3s";
            java.util.regex.Matcher cm = java.util.regex.Pattern.compile("(?s)\\{(.*?)\\}").matcher(block);
            while (cm.find()) {
                String obj = cm.group(1);
                String text = findProp(obj, "text");
                String color = findProp(obj, "color");
                String boldS = findProp(obj, "bold");
                String italicS = findProp(obj, "italic");
                String underS = findProp(obj, "underlined");
                String strikeS = findProp(obj, "strikethrough");
                String obfusS  = findProp(obj, "obfuscated");
                if (text == null) continue;
                net.minecraft.text.MutableText mt = net.minecraft.text.Text.literal(text);
                if (color != null) mt = mt.styled(s -> s.withColor(parseColor(color)));
                if (boldS != null) mt = mt.styled(s -> s.withBold(isTrue(boldS)));
                if (italicS != null) mt = mt.styled(s -> s.withItalic(isTrue(italicS)));
                if (underS != null) mt = mt.styled(s -> s.withUnderline(isTrue(underS)));
                if (strikeS != null) mt = mt.styled(s -> s.withStrikethrough(isTrue(strikeS)));
                if (obfusS  != null) mt = mt.styled(s -> s.withObfuscated(isTrue(obfusS)));
                parts.add(mt);
            }
            java.util.regex.Matcher tm = java.util.regex.Pattern.compile("\"time\"\\s*:\\s*\"([^\"]+)\"").matcher(block);
            if (tm.find()) timeStr = tm.group(1);
            long dur = parseDurationToTicks(timeStr);
            ACTIONBARS.add(new ActionBarEntry(parts, dur));
        }
        if (ACTIONBARS.isEmpty()) {
            java.util.List<net.minecraft.text.Text> d1 = java.util.List.of(
                net.minecraft.text.Text.literal("WEB").styled(s->s.withColor(0x55FF55).withBold(true)),
                net.minecraft.text.Text.literal(" >> ").styled(s->s.withColor(0x555555).withBold(true)),
                net.minecraft.text.Text.literal("https://hikaripage.f5.si").styled(s->s.withColor(0x55FF55).withBold(true))
            );
            ACTIONBARS.add(new ActionBarEntry(d1, 60));
        }
    }
    private static String findProp(String obj, String key) {
        java.util.regex.Matcher mm = java.util.regex.Pattern
            .compile("\"" + java.util.regex.Pattern.quote(key) + "\"\\s*:\\s*\"([^\"]*)\"")
            .matcher(obj);
        if (mm.find()) return mm.group(1);
        return null;
    }
    private static boolean isTrue(String s) {
        return "true".equalsIgnoreCase(s) || "1".equals(s) || "yes".equalsIgnoreCase(s);
    }
}
