package com.coloryr.allmusic.client.core;

import com.coloryr.allmusic.client.core.objs.ConfigObj;
import com.coloryr.allmusic.codec.CommandType;
import com.coloryr.allmusic.codec.HudPosObj;
import com.coloryr.allmusic.codec.MusicPack;
import com.coloryr.allmusic.codec.MusicPacketCodec;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import io.netty.buffer.ByteBuf;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.Header;
import org.apache.hc.core5.http.message.BasicHeader;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.nio.IntBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * AllMusic核心
 */
public class AllMusicCore {
    public static final CommandType[] types = CommandType.values();
    public static final int MIN_PIC_SIZE = 16;
    public static final int MAX_PIC_SIZE = 1024;
    public static final List<String> DEFAULT_LOG_QUIET = Arrays.asList("org.apache.hc.client5", "org.apache.hc.core5");
    private static final Logger LOGGER = LogManager.getLogger(AllMusicCore.class);

    private static final Gson gson = new Gson();
    /**
     * 与游戏链接的桥
     */
    public static AllMusicBridge bridge;
    /**
     * 配置文件
     */
    public static ConfigObj config;
    public static CloseableHttpClient client;
    /**
     * 音频解码器与播放器
     */
    private static AllMusicPlayer player;
    /**
     * 界面显示内容
     */
    private static AllMusicHud hud;
    private static Path configFile;
    private static boolean configWritable;
    private static boolean waitingForJoinPosition;

    private static PlayerBlockStore blockStore;
    private static BlockedMusicController blockController;

    public static final ScheduledExecutorService service = Executors.newScheduledThreadPool(4);

    /**
     * 更新音频缓存
     */
    public static void tick() {
        if (player != null) {
            player.tick();
        }
        if (blockController != null) {
            blockController.tick();
        }
    }

    /**
     * 降低播放音量
     */
    public static void chat() {
        if (player != null) {
            player.setChat();
        }
    }

    /**
     * 是否正在播放音乐
     *
     * @return 是否在播放
     */
    public static boolean isPlay() {
        if (player == null) {
            return false;
        }
        return player.isPlay();
    }

    public static void init(Path file, AllMusicBridge bridge, IntBuffer source) {
        List<Header> headers = new ArrayList<>();
        headers.add(new BasicHeader("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/146.0.0.0 Safari/537.36 Edg/146.0.0.0"));
        client = HttpClients.custom().setDefaultHeaders(headers).build();

        loadConfig(file);

        AllMusicCore.bridge = bridge;
        player = new AllMusicPlayer(source);
        blockStore = new PlayerBlockStore(file);
        blockController = new BlockedMusicController(AllMusicCore::getBlockSettings,
                () -> System.nanoTime() / 1_000_000, new BlockedMusicController.Playback() {
            @Override
            public void play(String url, int position) {
                bridge.stopPlayMusic();
                player.setMusic(url, position);
            }

            @Override
            public void stop() {
                player.closePlayer();
            }

            @Override
            public void seek(int position) {
                player.setTime(position);
            }

            @Override
            public void message(String text) {
                bridge.sendFeedback(text);
            }
        });

        ClientLogLevels.apply(config.logQuiet);
    }

    static void loadConfig(Path directory) {
        configFile = directory.resolve("allmusic_client.json");
        configWritable = true;
        config = null;
        if (Files.exists(configFile)) {
            try (Reader reader = Files.newBufferedReader(configFile, StandardCharsets.UTF_8)) {
                config = gson.fromJson(reader, ConfigObj.class);
                if (config == null || config.picSize < MIN_PIC_SIZE || config.picSize > MAX_PIC_SIZE
                        || config.queueSize <= 0) {
                    throw new IllegalArgumentException("客户端配置无效");
                }
            } catch (IOException | RuntimeException e) {
                configWritable = false;
                config = null;
                LOGGER.error("无法读取客户端配置 {}，保留原文件", configFile, e);
            }
        }
        if (config == null) {
            config = new ConfigObj();
            config.picSize = 240;
            config.queueSize = 100;
        }
        if (config.logQuiet == null) {
            config.logQuiet = new ArrayList<>(DEFAULT_LOG_QUIET);
        }
        if (configWritable && !Files.exists(configFile)) {
            try {
                saveConfig();
            } catch (IOException e) {
                LOGGER.error("无法保存客户端默认配置 {}", configFile, e);
            }
        }
    }

    private static void saveConfig() throws IOException {
        if (!configWritable) {
            throw new IOException("客户端配置无法读取，请修复原文件后重启");
        }
        Files.createDirectories(configFile.getParent());
        Path temporary = Files.createTempFile(configFile.getParent(), "allmusic_client_", ".tmp");
        try {
            String data = new GsonBuilder().setPrettyPrinting().create().toJson(config);
            Files.write(temporary, data.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temporary, configFile, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, configFile, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    public static void stop() {
        if (blockStore != null) {
            blockStore.close();
        }
        service.close();

        if (player != null) {
            player.stop();
        }
        if (hud != null) {
            hud.stop();
        }
    }

    /**
     * 初始化核心
     *
     * @param file   配置文件
     * @param bridge 游戏桥
     */
    public static void init(Path file, AllMusicBridge bridge) {
        init(file, bridge, null);
    }

    /**
     * 贴图初始化
     */
    public static void renderInit() {
        hud = new AllMusicHud(config.picSize);
    }

    public static int getPicSize() {
        return config == null ? 0 : config.picSize;
    }

    public static boolean setPicSize(int size) {
        if (config == null || size < MIN_PIC_SIZE || size > MAX_PIC_SIZE) {
            return false;
        }
        int previous = config.picSize;
        config.picSize = size;
        try {
            saveConfig();
        } catch (IOException e) {
            config.picSize = previous;
            throw new IllegalStateException("无法保存封面设置，请检查客户端配置文件和目录权限", e);
        }
        if (hud != null) {
            hud.setPicSize(size);
        }
        return true;
    }

    public static List<String> getInfoLines() {
        List<String> lines = new ArrayList<>();
        lines.add("配置文件: config/allmusic_client.json");
        lines.add("兼容服务端版本: 4.2.5 / Fabric 26.3");
        lines.add("图片大小 picSize: " + getPicSize() + "（范围 " + MIN_PIC_SIZE + "-" + MAX_PIC_SIZE + "）");
        lines.add("音频队列: " + (config == null ? 0 : config.queueSize));
        lines.add("配置可保存: " + configWritable);
        lines.add("HUD 渲染层: " + (hud == null ? "未初始化" : "已初始化"));
        if (hud != null) {
            lines.add("HUD 数据: " + (hud.hasHudData() ? "已收到" : "未收到"));
            lines.add("当前封面: " + (hud.hasImage() ? "有，尺寸 " + hud.getPicSize() : "无"));
        }
        lines.add("播放状态: " + (isPlay() ? "播放中" : "未播放"));
        if (blockController != null) {
            lines.add("本地屏蔽: " + blockController.isBlocked() + " / 等待进度: " + blockController.isWaitingForPosition());
        }
        return lines;
    }

    public static List<String> getTestLines() {
        List<String> lines = new ArrayList<>();
        lines.add(check(config != null, "核心配置"));
        lines.add(check(bridge != null, "游戏桥"));
        lines.add(check(client != null, "网络客户端"));
        lines.add(check(player != null, "音频播放器"));
        lines.add(check(hud != null, "HUD 渲染层"));
        lines.add(check(configWritable, "客户端配置可保存"));
        if (hud != null) {
            lines.addAll(hud.getTestLines());
        }
        return lines;
    }

    private static String check(boolean success, String text) {
        return (success ? "[OK] " : "[!!] ") + text;
    }

    /**
     * 退出服务器时
     */
    public static void onServerQuit() {
        waitingForJoinPosition = false;
        try {
            if (blockController != null) {
                blockController.stop();
            }
            stopPlaying();
        } catch (Exception e) {
            e.printStackTrace();
        }
        if (hud != null) {
            hud.close();
        }
    }

    /**
     * 停止播放
     */
    private static void stopPlaying() {
        if (player != null) {
            player.closePlayer();
        }
        if (hud != null) {
            hud.clear();
        }
    }

    public static PlayerBlockSettings getBlockSettings() {
        return blockStore.get(bridge.getServerKey());
    }

    public static PlayerBlockStore getBlockStore() {
        return blockStore;
    }

    public static BlockedMusicController getBlockController() {
        return blockController;
    }

    /**
     * 重载音频
     */
    public static void reload() {
        if (player != null) {
            player.setReload();
        }
    }

    /**
     * 更新显示内容
     */
    public static void hudUpdate() {
        if (hud != null) {
            hud.update();
        }
    }

    /**
     * 读取数据包
     *
     * @param buffer 数据包
     */
    public static void packRead(ByteBuf buffer) {
        if (buffer == null || !buffer.isReadable()) {
            return;
        }
        packDo(MusicPacketCodec.decode(buffer));
    }

    /**
     * 解析数据包
     *
     * @param pack 数据
     */
    public static void packDo(MusicPack pack) {
        switch (pack.type) {
            case LYRIC:
                MusicPack.LyricMusicPack pack1 = (MusicPack.LyricMusicPack) pack;
                hud.setLyric(pack1.lyric, pack1.tlyric, pack1.klyric);
                break;
            case LYRIC_KTV:
                MusicPack.LyricKtvMusicPack pack2 = (MusicPack.LyricKtvMusicPack) pack;
                hud.setKtv(pack2.time, pack2.data);
                break;
            case INFO:
                MusicPack.StringMusicPack pack3 = (MusicPack.StringMusicPack) pack;
                blockController.onInfo(pack3.data);
                String status = blockController.isBlocked() ? "[本地已屏蔽] "
                        : blockController.isWaitingForPosition() ? "[等待同步进度] "
                        : blockController.isWaiting() ? "[等待识别点歌者] " : "";
                hud.setInfo(status + pack3.data);
                break;
            case PLAY:
                MusicPack.StringMusicPack pack5 = (MusicPack.StringMusicPack) pack;
                stopPlaying();
                blockController.onPlay(pack5.data, waitingForJoinPosition);
                waitingForJoinPosition = false;
                break;
            case IMG:
                MusicPack.StringMusicPack pack6 = (MusicPack.StringMusicPack) pack;
                hud.setImg(pack6.data);
                break;
            case STOP:
                waitingForJoinPosition = false;
                blockController.stop();
                stopPlaying();
                break;
            case CLEAR:
                hud.clear();
                break;
            case POS:
                MusicPack.IntMusicPack pack7 = (MusicPack.IntMusicPack) pack;
                blockController.onPosition(pack7.data);
                break;
            case HUD_DATA:
                MusicPack.StringMusicPack pack8 = (MusicPack.StringMusicPack) pack;
                boolean initialHud = !hud.hasHudData();
                boolean fail = hud.setPos(gson.fromJson(pack8.data, HudPosObj.class));
                if (!fail) {
                    bridge.kick();
                } else if (initialHud && !blockController.hasMusic()) {
                    // 旧服务端中途加入时先发 HUD_DATA，再发 PLAY，最后单独同步进度。
                    waitingForJoinPosition = true;
                }
                break;
            case TIME:
                MusicPack.TimeMusicPack pack9 = (MusicPack.TimeMusicPack) pack;
                blockController.onTime(pack9.now);
                hud.setTime(pack9.time, pack9.now);
                break;
        }
    }
}
