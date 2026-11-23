package com.coloryr.allmusic.client;

import com.coloryr.allmusic.client.core.AllMusicBridge;
import com.coloryr.allmusic.client.core.AllMusicCore;
import com.coloryr.allmusic.client.core.BlockedMusicController;
import com.coloryr.allmusic.client.core.PlayerBlockSettings;
import com.coloryr.allmusic.client.core.render.PictureFrameBuffer;
import com.coloryr.allmusic.client.core.render.TextFrameBuffer;
import com.coloryr.allmusic.client.core.render.TextureRender;
import com.coloryr.allmusic.comm.MusicCodec;
import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.fabric.api.client.event.lifecycle.v1.ClientLifecycleEvents;
import net.fabricmc.fabric.api.client.networking.v1.ClientPlayNetworking;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.multiplayer.ClientPacketListener;
import net.minecraft.network.Connection;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.Identifier;
import net.minecraft.server.packs.resources.Resource;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.level.storage.LevelResource;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.Collections;
import java.util.Locale;
import java.util.stream.Collectors;

public class AllMusicClient implements ClientModInitializer, AllMusicBridge, PlayerBlockCommands.Access {
    public static final String MODID = "allmusic_client";
    public static final Logger LOGGER = LogManager.getLogger("AllMusic Client");
    public static GuiGraphicsExtractor context;
    private PlayerBlockCommands blockCommands;

    public PlayerBlockCommands getBlockCommands() {
        return blockCommands;
    }

    @Override
    public String getServerKey() {
        Minecraft minecraft = Minecraft.getInstance();
        if (minecraft.getConnection() == null) {
            return null;
        }
        if (minecraft.getSingleplayerServer() != null) {
            return "world:" + minecraft.getSingleplayerServer().getWorldPath(LevelResource.ROOT)
                    .toAbsolutePath().normalize();
        }
        var server = minecraft.getCurrentServer();
        if (server == null) {
            var remote = minecraft.getConnection().getConnection().getRemoteAddress();
            if (remote instanceof java.net.InetSocketAddress address) {
                return "server:" + address.getHostString().toLowerCase(Locale.ROOT) + ":" + address.getPort();
            }
            return remote == null ? null : "connection:" + remote;
        }
        // 使用连接时的地址，不通过 DNS 推测服务器身份。
        var address = net.minecraft.client.multiplayer.resolver.ServerAddress.parseString(server.ip);
        return "server:" + address.getHost().toLowerCase(Locale.ROOT) + ":" + address.getPort();
    }

    @Override
    public PlayerBlockSettings settings() {
        return AllMusicCore.getBlockSettings();
    }

    @Override
    public BlockedMusicController controller() {
        return AllMusicCore.getBlockController();
    }

    @Override
    public void requireWritable() {
        AllMusicCore.getBlockStore().requireWritable();
    }

    @Override
    public void save() {
        AllMusicCore.getBlockStore().save().whenComplete((unused, error) -> {
            if (error != null) {
                sendFeedback("黑名单设置保存失败，本次设置仅在当前会话生效，请检查配置目录权限或磁盘空间");
            }
        });
    }

    @Override
    public Collection<String> onlinePlayers() {
        var connection = Minecraft.getInstance().getConnection();
        if (connection == null) {
            return Collections.emptyList();
        }
        return connection.getOnlinePlayers().stream().map(player -> player.getProfile().name())
                .collect(Collectors.toList());
    }

    @Override
    public void message(String text) {
        sendFeedback(text);
    }

    @Override
    public void sendFeedback(String text) {
        Minecraft.getInstance().execute(() -> {
            var player = Minecraft.getInstance().player;
            if (player != null) {
                player.sendSystemMessage(Component.literal("[AllMusic Client] " + text));
            }
        });
    }

    public static void update(GuiGraphicsExtractor draw) {
        context = draw;
        AllMusicCore.hudUpdate();
    }

    public int getScreenWidth() {
        return Minecraft.getInstance().getWindow().getGuiScaledWidth();
    }

    public int getScreenHeight() {
        return Minecraft.getInstance().getWindow().getGuiScaledHeight();
    }

    public int getTextWidth(String item) {
        return Minecraft.getInstance().font.width(item);
    }

    public int getFontHeight() {
        return Minecraft.getInstance().font.lineHeight;
    }

    public void sendMessage(String data) {
        data = "[AllMusic Client]" + data;
        LOGGER.warn(data);
        String finalData = data;
        Minecraft.getInstance().execute(() -> {
            if (Minecraft.getInstance().player == null)
                return;
            Minecraft.getInstance().player.sendSystemMessage(Component.literal(finalData));
        });
    }

    public float getVolume() {
        return Minecraft.getInstance().options.getFinalSoundSourceVolume(SoundSource.RECORDS);
    }

    @Override
    public void stopPlayMusic() {
        Minecraft.getInstance().getSoundManager().stop(null, SoundSource.MUSIC);
        Minecraft.getInstance().getSoundManager().stop(null, SoundSource.RECORDS);
    }

    @Override
    public TextFrameBuffer makeTextRender(String name) {
        return new CoreRenderTarget(name);
    }

    @Override
    public TextureRender makeTextureRender(String file) {
        return new TexRender(file);
    }

    @Override
    public PictureFrameBuffer makePictureRender(int size) {
        return new PicRender(size);
    }

    @Override
    public String readText(String file) {
        try {
            var man = Minecraft.getInstance().getResourceManager();
            Resource resource = man.getResource(Identifier.fromNamespaceAndPath(MODID, file)).orElseThrow();
            try (InputStream inputStream = resource.open()) {
                byte[] bytes = inputStream.readAllBytes();
                return new String(bytes, StandardCharsets.UTF_8);
            }
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public InputStream readFile(String file) {
        try {
            var man = Minecraft.getInstance().getResourceManager();
            Resource resource = man.getResource(Identifier.fromNamespaceAndPath(MODID, file)).orElseThrow();
            return resource.open();
        } catch (Exception e) {
            e.printStackTrace();
            return null;
        }
    }

    @Override
    public void kick() {
        Minecraft client = Minecraft.getInstance();

        ClientPacketListener packetListener = client.getConnection();
        if (packetListener != null) {
            Connection connection = packetListener.getConnection();
            connection.disconnect(Component.nullToEmpty("Old AllMusic server"));
        }
    }

    @Override
    public void onInitializeClient() {
        ClientPlayNetworking.registerGlobalReceiver(MusicCodec.ID, (pack, handler) -> AllMusicCore.packDo(pack.pack()));

        AllMusicCore.init(FabricLoader.getInstance().getConfigDir(), this);
        blockCommands = new PlayerBlockCommands(this);
        CommandMusicClientFabric.register();

        ClientLifecycleEvents.CLIENT_STOPPING.register((a) -> AllMusicCore.stop());
    }
}
