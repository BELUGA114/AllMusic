package com.coloryr.allmusic.client;

import com.coloryr.allmusic.client.core.BlockCommandRoute;
import com.coloryr.allmusic.client.core.BlockedMusicController;
import com.coloryr.allmusic.client.core.PlayerBlockSettings;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.builder.LiteralArgumentBuilder;
import com.mojang.brigadier.builder.RequiredArgumentBuilder;
import com.mojang.brigadier.exceptions.CommandSyntaxException;
import com.mojang.brigadier.suggestion.Suggestions;
import com.mojang.brigadier.suggestion.SuggestionsBuilder;

import java.util.Collection;
import java.util.Locale;
import java.util.TreeSet;
import java.util.concurrent.CompletableFuture;

public class PlayerBlockCommands {
    public interface Access {
        PlayerBlockSettings settings();

        BlockedMusicController controller();

        void requireWritable();

        void save();

        Collection<String> onlinePlayers();

        void message(String text);
    }

    private final Access access;
    private final CommandDispatcher<Void> dispatcher = new CommandDispatcher<>();

    public PlayerBlockCommands(Access access) {
        this.access = access;
        dispatcher.register(tree());
    }

    public boolean intercept(String command) {
        if (!BlockCommandRoute.matches(command)) {
            return false;
        }
        try {
            dispatcher.execute(command.trim().replaceAll("\\s+", " "), null);
        } catch (CommandSyntaxException e) {
            access.message("命令格式有误。用 /music block 查看帮助：" + e.getRawMessage().getString());
        } catch (IllegalArgumentException | IllegalStateException e) {
            access.message(e.getMessage());
        }
        return true;
    }

    public <S> void registerSuggestions(CommandDispatcher<S> target) {
        // 完整构建后再合并到服务端命令树，不在 Fabric 客户端分发器注册同名根命令。
        target.register(tree());
    }

    private <S> LiteralArgumentBuilder<S> tree() {
        LiteralArgumentBuilder<S> block = LiteralArgumentBuilder.<S>literal("block")
                .executes(context -> help())
                .then(LiteralArgumentBuilder.<S>literal("list").executes(context -> list()))
                .then(LiteralArgumentBuilder.<S>literal("current").executes(context -> current()))
                .then(LiteralArgumentBuilder.<S>literal("add")
                        .then(RequiredArgumentBuilder.<S, String>argument("player", StringArgumentType.word())
                                .suggests((context, builder) -> suggest(addSuggestions(), builder))
                                .executes(context -> add(StringArgumentType.getString(context, "player")))))
                .then(LiteralArgumentBuilder.<S>literal("remove")
                        .then(RequiredArgumentBuilder.<S, String>argument("player", StringArgumentType.word())
                                .suggests((context, builder) -> suggest(access.settings().getPlayers().values(), builder))
                                .executes(context -> remove(StringArgumentType.getString(context, "player")))))
                .then(LiteralArgumentBuilder.<S>literal("unknown").executes(context -> policy())
                        .then(LiteralArgumentBuilder.<S>literal("mute")
                                .executes(context -> policy(PlayerBlockSettings.UnknownPolicy.MUTE)))
                        .then(LiteralArgumentBuilder.<S>literal("play")
                                .executes(context -> policy(PlayerBlockSettings.UnknownPolicy.PLAY))));
        return LiteralArgumentBuilder.<S>literal("music").then(block);
    }

    private int help() {
        access.message("本地玩家点歌屏蔽（按服务器保存）：\n"
                + "/music block add <玩家名>：拉黑玩家\n"
                + "/music block current：拉黑当前已识别的点歌者\n"
                + "/music block remove <玩家名>：解除拉黑，从下一首生效\n"
                + "/music block list：查看黑名单\n"
                + "/music block unknown [mute|play]：查询或设置未知来源策略\n"
                + "黑名单非空时默认静音未知来源；识别依赖服务器 INFO 末行 by: 玩家名");
        return 1;
    }

    private int list() {
        access.requireWritable();
        PlayerBlockSettings settings = access.settings();
        access.message(settings.getPlayers().isEmpty() ? "当前服务器黑名单为空"
                : "当前服务器黑名单：" + String.join("、", settings.getPlayers().values()));
        return policy();
    }

    private int current() {
        String requester = access.controller().getRequester();
        if (requester == null) {
            access.message("当前没有已识别的点歌者，可用 /music block add <玩家名> 手动添加");
            return 0;
        }
        return add(requester);
    }

    private int add(String player) {
        access.requireWritable();
        if (!access.settings().add(player)) {
            access.message(player + " 已在当前服务器黑名单中");
            return 0;
        }
        access.controller().settingsChanged();
        access.save();
        access.message("已拉黑 " + player + " 的点歌，仅对本客户端生效");
        return 1;
    }

    private int remove(String player) {
        access.requireWritable();
        if (!access.settings().remove(player)) {
            access.message(player + " 不在当前服务器黑名单中");
            return 0;
        }
        access.save();
        access.message("已解除对 " + player + " 的拉黑，从下一首生效");
        return 1;
    }

    private int policy() {
        access.requireWritable();
        boolean mute = access.settings().getUnknownPolicy() == PlayerBlockSettings.UnknownPolicy.MUTE;
        access.message("未知来源策略：" + (mute ? "mute（保持静音）" : "play（允许播放，可能漏过屏蔽）")
                + "；仅在黑名单非空时启用，可用 /music block unknown mute|play 调整");
        return 1;
    }

    private int policy(PlayerBlockSettings.UnknownPolicy policy) {
        access.requireWritable();
        access.settings().setUnknownPolicy(policy);
        access.controller().settingsChanged();
        access.save();
        return policy();
    }

    private Collection<String> addSuggestions() {
        Collection<String> names = new TreeSet<>(access.onlinePlayers());
        String requester = access.controller().getRequester();
        if (requester != null) {
            names.add(requester);
        }
        return names;
    }

    private CompletableFuture<Suggestions> suggest(Collection<String> names, SuggestionsBuilder builder) {
        String remaining = builder.getRemaining().toLowerCase(Locale.ROOT);
        for (String name : names) {
            if (name.toLowerCase(Locale.ROOT).startsWith(remaining)) {
                builder.suggest(name);
            }
        }
        return builder.buildFuture();
    }
}
