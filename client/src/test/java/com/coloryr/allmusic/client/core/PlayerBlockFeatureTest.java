package com.coloryr.allmusic.client.core;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;

public class PlayerBlockFeatureTest {
    public static void main(String[] args) throws Exception {
        parseNames();
        blockBeforePlayback();
        switchUnknownPolicy();
        stopAndReplacePending();
        blockDuringPlayback();
        seekWhilePending();
        settingsPersistence(Paths.get(args[0]));
        saveFailures(Paths.get(args[0]));
        commandRouting();
        System.out.println("Player block checks passed");
    }

    private static void parseNames() {
        check("Alice".equals(BlockedMusicController.parseRequester("Song\r\n§aby: §bAlice§r")), "格式化点歌者");
        check(BlockedMusicController.parseRequester("Alice song\nartist") == null, "不能匹配歌名中的玩家名");
        check(BlockedMusicController.parseRequester("by: Alice\nother") == null, "只能匹配末行");
        check(BlockedMusicController.parseRequester("by: 空闲歌单") == null, "不能把系统来源当成玩家");
        check(BlockedMusicController.parseRequester("by: Alice Bob") == null, "拒绝不明确的玩家名");
    }

    private static void blockBeforePlayback() {
        Fixture fixture = new Fixture();
        fixture.settings.add("Alice");
        fixture.controller.onPlay("song-a");
        fixture.controller.onInfo("Title\nby: aLiCe");
        fixture.advance(5000);
        check(fixture.plays.isEmpty(), "拉黑歌曲不能启播");
        check(fixture.messages.size() == 1, "屏蔽提示不能重复发送");
        fixture.controller.onInfo("Title\nby: aLiCe");
        check(fixture.messages.size() == 1, "重复 INFO 不重复提示");
        fixture.controller.onPlay("song-a");
        fixture.controller.onInfo("Title\nby: Bob");
        check(fixture.plays.size() == 1, "同一歌曲由其他玩家点播可以播放");
    }

    private static void switchUnknownPolicy() {
        Fixture fixture = new Fixture();
        fixture.settings.add("Alice");
        fixture.controller.onPlay("unknown");
        fixture.advance(1500);
        check(fixture.plays.isEmpty(), "未知来源默认静音");
        check(fixture.messages.size() == 1, "缺失来源只提示一次");
        fixture.advance(1500);
        check(fixture.messages.size() == 1, "缺失来源不能刷屏");
        fixture.settings.setUnknownPolicy(PlayerBlockSettings.UnknownPolicy.PLAY);
        fixture.controller.settingsChanged();
        check(fixture.plays.size() == 1, "允许未知来源后恢复播放");
        int stops = fixture.stops;
        fixture.settings.setUnknownPolicy(PlayerBlockSettings.UnknownPolicy.MUTE);
        fixture.controller.settingsChanged();
        check(fixture.stops == stops + 1, "切换为静音立即停止未知来源");
        fixture.controller.onInfo("Song\nby: Bob");
        check(fixture.plays.size() == 2, "未知来源稍后明确为允许的玩家时启播");

        Fixture compatible = new Fixture();
        compatible.settings.add("Alice");
        compatible.settings.setUnknownPolicy(PlayerBlockSettings.UnknownPolicy.PLAY);
        compatible.controller.onPlay("unknown");
        compatible.advance(1499);
        check(compatible.plays.isEmpty(), "兼容策略也先等待来源");
        compatible.advance(1);
        check(compatible.plays.size() == 1, "兼容策略等待期结束后启播");
        compatible.controller.onInfo("Song\nby: Alice");
        check(compatible.controller.isBlocked(), "迟到的拉黑来源仍须停止");
    }

    private static void stopAndReplacePending() {
        Fixture fixture = new Fixture();
        fixture.settings.add("Alice");
        fixture.controller.onPlay("old");
        fixture.controller.onInfo("by: Alice");
        fixture.controller.onPlay("new");
        check(fixture.controller.getRequester() == null, "新 PLAY 不能复用旧点歌者");
        fixture.controller.onInfo("by: Bob");
        check(fixture.plays.equals(java.util.Collections.singletonList("new")), "只启播最新歌曲");
        fixture.controller.onPlay("pending");
        fixture.controller.stop();
        fixture.settings.setUnknownPolicy(PlayerBlockSettings.UnknownPolicy.PLAY);
        fixture.advance(5000);
        fixture.controller.onInfo("by: Bob");
        check(fixture.plays.size() == 1, "STOP 或断连不能使待播放歌曲复活");
    }

    private static void blockDuringPlayback() {
        Fixture fixture = new Fixture();
        fixture.controller.onPlay("song");
        check(fixture.plays.size() == 1, "空黑名单不能延迟原有播放");
        fixture.controller.onInfo("by: Alice");
        fixture.settings.add("Alice");
        int stops = fixture.stops;
        fixture.controller.settingsChanged();
        check(fixture.stops == stops + 1, "拉黑当前点歌者立即停止");
        fixture.settings.remove("Alice");
        fixture.advance(5000);
        check(fixture.plays.size() == 1, "解除拉黑不能突然恢复当前歌曲");
        fixture.controller.onPlay("next");
        check(fixture.plays.size() == 2, "解除拉黑下一首生效");
    }

    private static void seekWhilePending() {
        Fixture fixture = new Fixture();
        fixture.settings.add("Alice");
        fixture.controller.onPlay("joined");
        fixture.controller.onPosition(30000);
        fixture.advance(200);
        fixture.controller.onInfo("by: Bob");
        check(fixture.positions.get(0) == 30200, "等待期间保留 POS 和经过的时间");
        fixture.controller.onPosition(50000);
        check(fixture.seeks.equals(java.util.Collections.singletonList(50000)), "启播后仍处理 POS");
        fixture.controller.onPlay("timed");
        fixture.controller.onTime(10000);
        fixture.advance(100);
        fixture.controller.onInfo("by: Bob");
        check(fixture.positions.get(1) == 10100, "等待期间保留 TIME");
        fixture.controller.onPlay("invalid-time");
        fixture.controller.onTime(Long.MAX_VALUE);
        fixture.advance(100);
        fixture.controller.onInfo("by: Bob");
        check(fixture.positions.get(2) == Integer.MAX_VALUE, "异常 TIME 不能溢出为负进度");
    }

    private static void settingsPersistence(Path root) throws Exception {
        Path directory = Files.createTempDirectory(root, "settings-");
        try (PlayerBlockStore store = new PlayerBlockStore(directory)) {
            check(store.get("server:a").getUnknownPolicy() == PlayerBlockSettings.UnknownPolicy.MUTE, "默认策略");
            store.get("server:a").add("Alice");
            java.util.concurrent.CompletableFuture<Void> first = store.save();
            store.get("server:a").setUnknownPolicy(PlayerBlockSettings.UnknownPolicy.PLAY);
            store.save().get();
            first.get();
        }
        try (PlayerBlockStore reloaded = new PlayerBlockStore(directory)) {
            check(reloaded.get("server:a").contains("ALICE"), "玩家名持久化且大小写不敏感");
            check(reloaded.get("server:a").getUnknownPolicy() == PlayerBlockSettings.UnknownPolicy.PLAY, "策略持久化");
            check(reloaded.get("server:b").getPlayers().isEmpty(), "服务器之间隔离");
        }
        Path broken = Files.createTempDirectory(root, "broken-");
        Path file = broken.resolve("allmusic_client_blocklist.json");
        String invalid = "{invalid";
        Files.write(file, invalid.getBytes(StandardCharsets.UTF_8));
        try (PlayerBlockStore store = new PlayerBlockStore(broken)) {
            boolean rejected = false;
            try {
                store.requireWritable();
            } catch (IllegalStateException e) {
                rejected = true;
            }
            check(rejected, "损坏配置必须明确拒绝覆盖");
        }
        check(invalid.equals(new String(Files.readAllBytes(file), StandardCharsets.UTF_8)), "保留损坏的原始配置");
    }

    private static void saveFailures(Path root) throws Exception {
        Path directory = Files.createTempDirectory(root, "save-failure-");
        try (PlayerBlockStore store = new PlayerBlockStore(directory)) {
            store.get("server:a").add("Alice");
            Path target = directory.resolve("allmusic_client_blocklist.json");
            Files.createDirectory(target);
            boolean failed = false;
            try {
                store.save().get();
            } catch (java.util.concurrent.ExecutionException e) {
                failed = e.getCause() instanceof java.io.IOException;
            }
            check(failed, "写入失败通过 Future 显式返回");
            check(store.get("server:a").contains("Alice"), "保存失败保留会话设置");
            check(Files.isDirectory(target), "保存失败不删除已有目标");
        }
    }

    private static void commandRouting() {
        check(BlockCommandRoute.matches("music block"), "识别帮助命令");
        check(BlockCommandRoute.matches("music  block unknown play"), "识别策略命令");
        check(!BlockCommandRoute.matches("music mute"), "其他子命令交给服务器");
        check(!BlockCommandRoute.matches("music blockade Alice"), "不拦截类似前缀");
        check(!BlockCommandRoute.matches("music"), "不拦截根命令");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static class Fixture implements BlockedMusicController.Playback {
        private final PlayerBlockSettings settings = new PlayerBlockSettings();
        private final List<String> plays = new ArrayList<>();
        private final List<Integer> positions = new ArrayList<>();
        private final List<Integer> seeks = new ArrayList<>();
        private final List<String> messages = new ArrayList<>();
        private long now;
        private final BlockedMusicController controller = new BlockedMusicController(() -> settings, () -> now, this);
        private int stops;

        private void advance(long milliseconds) {
            now += milliseconds;
            controller.tick();
        }

        public void play(String url, int position) {
            plays.add(url);
            positions.add(position);
        }

        public void stop() {
            stops++;
        }

        public void seek(int position) {
            seeks.add(position);
        }

        public void message(String text) {
            messages.add(text);
        }
    }
}
