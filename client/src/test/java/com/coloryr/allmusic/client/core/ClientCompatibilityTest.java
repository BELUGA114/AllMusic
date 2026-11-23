package com.coloryr.allmusic.client.core;

import com.coloryr.allmusic.codec.CommandType;
import com.coloryr.allmusic.codec.HudPosObj;
import com.coloryr.allmusic.codec.MusicPack;
import com.coloryr.allmusic.codec.MusicPacketCodec;
import com.google.gson.Gson;
import com.google.gson.JsonObject;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.core.Logger;
import org.apache.logging.log4j.core.LoggerContext;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.Collections;

public class ClientCompatibilityTest {
    public static void main(String[] args) throws Exception {
        try {
            AllMusicCore.hudUpdate();
            AllMusicCore.onServerQuit();
            oldServerPackets();
            configuration(Paths.get(args[0]));
            logIsolation();
            HudResourcesTest.run();
            System.out.println("Client compatibility checks passed");
        } finally {
            AllMusicCore.service.shutdownNow();
        }
    }

    private static void oldServerPackets() {
        byte[] url = "https://example.invalid/song.mp3".getBytes(StandardCharsets.UTF_8);
        byte[] legacyPlay = ByteBuffer.allocate(5 + url.length).put((byte) 3).putInt(url.length).put(url).array();
        MusicPack.StringMusicPack play = (MusicPack.StringMusicPack) decode(legacyPlay);
        check(play.type == CommandType.PLAY && play.data.endsWith("song.mp3"), "读取旧 PLAY");
        ByteBuf encoded = MusicPacketCodec.pack(play);
        try {
            byte[] bytes = new byte[encoded.readableBytes()];
            encoded.readBytes(bytes);
            check(Arrays.equals(bytes, legacyPlay), "PLAY 二进制格式保持不变");
        } finally {
            encoded.release();
        }
        MusicPack.IntMusicPack position = (MusicPack.IntMusicPack) decode(
                ByteBuffer.allocate(5).put((byte) 7).putInt(30000).array());
        check(position.data == 30000, "读取旧 POS 毫秒进度");
        MusicPack.TimeMusicPack time = (MusicPack.TimeMusicPack) decode(
                ByteBuffer.allocate(17).put((byte) 9).putLong(180000).putLong(30000).array());
        check(time.time == 180000 && time.now == 30000, "读取旧 TIME");

        Gson gson = new Gson();
        JsonObject oldHud = gson.toJsonTree(HudPosObj.make()).getAsJsonObject();
        oldHud.getAsJsonObject("pic").remove("bg");
        byte[] json = gson.toJson(oldHud).getBytes(StandardCharsets.UTF_8);
        MusicPack.StringMusicPack hud = (MusicPack.StringMusicPack) decode(
                ByteBuffer.allocate(5 + json.length).put((byte) 8).putInt(json.length).put(json).array());
        HudPosObj parsed = gson.fromJson(hud.data, HudPosObj.class);
        check(parsed.pic.bg && parsed.pic.copy().bg, "旧 HUD 缺省背景保持开启");
        oldHud.getAsJsonObject("pic").addProperty("bg", false);
        parsed = gson.fromJson(oldHud, HudPosObj.class);
        check(!parsed.pic.bg && !parsed.pic.copy().bg, "尊重新服务端显式关闭背景");
    }

    private static MusicPack decode(byte[] bytes) {
        ByteBuf buffer = Unpooled.wrappedBuffer(bytes);
        try {
            return MusicPacketCodec.decode(buffer);
        } finally {
            buffer.release();
        }
    }

    private static void configuration(Path root) throws Exception {
        Path directory = Files.createTempDirectory(root, "client-config-");
        AllMusicCore.loadConfig(directory);
        check(AllMusicCore.getPicSize() == 240, "新配置使用 240 封面分辨率");
        check(AllMusicCore.setPicSize(512), "有效尺寸可以保存");
        AllMusicCore.loadConfig(directory);
        check(AllMusicCore.getPicSize() == 512, "重启后保留尺寸");
        check(!AllMusicCore.setPicSize(15) && !AllMusicCore.setPicSize(1025), "拒绝越界尺寸");
        check(CommandMusicClient.execute(new String[]{"info"}).lines.size() > 0, "本地状态查询");
        check(CommandMusicClient.execute(new String[]{"imagesize", "bad"}).lines.get(0).contains("有效"), "非法数字反馈");

        Path file = directory.resolve("allmusic_client.json");
        String legacy = "{\"picSize\":120,\"queueSize\":100}";
        Files.write(file, legacy.getBytes(StandardCharsets.UTF_8));
        AllMusicCore.loadConfig(directory);
        check(AllMusicCore.getPicSize() == 120 && AllMusicCore.config.logQuiet.size() == 2, "旧配置保留尺寸并补默认日志");
        check(legacy.equals(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)), "读取旧配置不强制重写");

        String broken = "{invalid";
        Files.write(file, broken.getBytes(StandardCharsets.UTF_8));
        AllMusicCore.loadConfig(directory);
        boolean rejected = false;
        try {
            AllMusicCore.setPicSize(256);
        } catch (IllegalStateException e) {
            rejected = true;
        }
        check(rejected && AllMusicCore.getPicSize() == 240, "保存失败不修改会话尺寸");
        check(broken.equals(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)), "损坏配置不会被默认值覆盖");
    }

    private static void logIsolation() {
        LoggerContext context = (LoggerContext) LogManager.getContext(false);
        Logger root = context.getRootLogger();
        Logger other = context.getLogger("compatibility.other-mod");
        Level originalRoot = root.getLevel();
        Level originalOther = other.getLevel();
        try {
            root.setLevel(Level.DEBUG);
            other.setLevel(Level.WARN);
            ClientLogLevels.apply(Collections.singletonList("org.apache.hc.client5"));
            check(root.getLevel() == Level.DEBUG, "不修改根 logger");
            check(other.getLevel() == Level.WARN, "保留其他模组运行时日志等级");
            check(context.getLogger("org.apache.hc.client5.http").getLevel() == Level.INFO, "开发环境 HTTP 日志降为 INFO");
            check(context.getLogger("com.coloryr.allmusic.libs.org.apache.hc.client5.http").getLevel() == Level.INFO,
                    "发布包重定位后的 HTTP 日志降为 INFO");
            ClientLogLevels.apply(Collections.emptyList());
            check(other.getLevel() == Level.WARN, "空配置不重新设置日志");
        } finally {
            root.setLevel(originalRoot);
            other.setLevel(originalOther);
        }
    }

    static void check(boolean success, String message) {
        if (!success) {
            throw new AssertionError(message);
        }
    }
}
