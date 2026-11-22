package com.coloryr.allmusic.client;

import com.coloryr.allmusic.client.core.BlockedMusicController;
import com.coloryr.allmusic.client.core.PlayerBlockSettings;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.suggestion.Suggestion;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

public class PlayerBlockCommandsTest {
    public static void main(String[] args) throws Exception {
        Fixture fixture = new Fixture();
        PlayerBlockCommands commands = new PlayerBlockCommands(fixture);
        check(!commands.intercept("music mute"), "原有命令不能被消费");
        check(!commands.intercept("music"), "根命令不能被消费");
        check(commands.intercept("music block add Alice"), "拉黑命令在本地执行");
        check(fixture.settings.contains("alice") && fixture.saves == 1, "保存拉黑玩家");
        commands.intercept("music block unknown play");
        check(fixture.settings.getUnknownPolicy() == PlayerBlockSettings.UnknownPolicy.PLAY, "切换兼容策略");
        int saves = fixture.saves;
        commands.intercept("music block unknown");
        commands.intercept("music block list");
        check(fixture.saves == saves, "查询不能写入配置");
        commands.intercept("music block unknown invalid");
        check(fixture.settings.getUnknownPolicy() == PlayerBlockSettings.UnknownPolicy.PLAY, "无效策略不修改设置");
        fixture.controller.onPlay("song");
        fixture.controller.onInfo("by: Bob");
        commands.intercept("music block current");
        check(fixture.settings.contains("Bob") && fixture.controller.isBlocked(), "拉黑当前点歌者");
        commands.intercept("music block remove alice");
        check(!fixture.settings.contains("Alice"), "大小写不敏感解除拉黑");

        CommandDispatcher<Void> server = new CommandDispatcher<>();
        server.register(LiteralArgumentBuilder.<Void>literal("music").executes(context -> 7)
                .then(RequiredArgumentBuilder.<Void, String>argument("args", StringArgumentType.greedyString())
                        .executes(context -> 8)));
        commands.registerSuggestions(server);
        check(server.execute("music", null) == 7, "保留服务器根命令");
        check(server.execute("music mute", null) == 8, "保留服务器参数分支");
        List<Suggestion> subcommands = server.getCompletionSuggestions(server.parse("music ", null)).get().getList();
        check(subcommands.stream().anyMatch(item -> item.getText().equals("block")), "共享根下补全 block");
        List<Suggestion> players = server.getCompletionSuggestions(server.parse("music block add A", null)).get().getList();
        check(players.stream().anyMatch(item -> item.getText().equals("Alice")), "补全在线玩家");
        List<Suggestion> policies = server.getCompletionSuggestions(server.parse("music block unknown ", null)).get().getList();
        check(policies.size() == 2, "补全两种未知来源策略");
        List<Suggestion> removals = server.getCompletionSuggestions(server.parse("music block remove ", null)).get().getList();
        check(removals.stream().anyMatch(item -> item.getText().equals("Bob")), "补全黑名单玩家");
        System.out.println("Player block command checks passed");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            throw new AssertionError(message);
        }
    }

    private static class Fixture implements PlayerBlockCommands.Access, BlockedMusicController.Playback {
        private final PlayerBlockSettings settings = new PlayerBlockSettings();
        private final BlockedMusicController controller = new BlockedMusicController(() -> settings, () -> 0, this);
        private final List<String> messages = new ArrayList<>();
        private int saves;

        public PlayerBlockSettings settings() { return settings; }
        public BlockedMusicController controller() { return controller; }
        public void requireWritable() { }
        public void save() { saves++; }
        public Collection<String> onlinePlayers() { return Arrays.asList("Alice", "Bob"); }
        public void message(String text) { messages.add(text); }
        public void play(String url, int position) { }
        public void stop() { }
        public void seek(int position) { }
    }
}
