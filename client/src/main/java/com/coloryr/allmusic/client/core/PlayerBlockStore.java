package com.coloryr.allmusic.client.core;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public class PlayerBlockStore implements AutoCloseable {
    private static final Logger LOGGER = LogManager.getLogger(PlayerBlockStore.class);
    private final Gson gson = new GsonBuilder().setPrettyPrinting().create();
    private final Path file;
    private final ExecutorService writer = Executors.newSingleThreadExecutor(runnable -> {
        Thread thread = new Thread(runnable, "allmusic_block_save");
        thread.setDaemon(true);
        return thread;
    });
    private BlockConfig config = new BlockConfig();
    private boolean writable = true;

    public PlayerBlockStore(Path directory) {
        file = directory.resolve("allmusic_client_blocklist.json");
        if (Files.exists(file)) {
            try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
                BlockConfig loaded = gson.fromJson(reader, BlockConfig.class);
                if (loaded == null || loaded.servers == null) {
                    throw new IllegalArgumentException("黑名单配置为空");
                }
                for (Map.Entry<String, PlayerBlockSettings> entry : loaded.servers.entrySet()) {
                    if (entry.getKey() == null || entry.getKey().trim().isEmpty() || entry.getValue() == null) {
                        throw new IllegalArgumentException("服务器黑名单配置无效");
                    }
                    entry.getValue().validate();
                }
                config = loaded;
            } catch (IOException | RuntimeException e) {
                writable = false;
                LOGGER.error("无法读取黑名单配置 {}，保留原文件", file, e);
            }
        }
    }

    public PlayerBlockSettings get(String server) {
        if (server == null || server.trim().isEmpty()) {
            throw new IllegalStateException("请先进入服务器或单人世界");
        }
        return config.servers.computeIfAbsent(server, key -> new PlayerBlockSettings());
    }

    public void requireWritable() {
        if (!writable) {
            throw new IllegalStateException("黑名单配置无法读取，请修复 " + file.getFileName() + " 后重启客户端");
        }
    }

    public CompletableFuture<Void> save() {
        requireWritable();
        // 在游戏线程获取快照，单个写入线程保证多条命令的保存顺序。
        String snapshot = gson.toJson(config);
        return CompletableFuture.runAsync(() -> {
            try {
                write(snapshot);
            } catch (IOException e) {
                LOGGER.error("无法保存黑名单配置 {}", file, e);
                throw new CompletionException(e);
            }
        }, writer);
    }

    private void write(String snapshot) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), "allmusic_block_", ".tmp");
        try {
            Files.write(temporary, snapshot.getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    @Override
    public void close() {
        writer.shutdown();
        try {
            if (!writer.awaitTermination(5, TimeUnit.SECONDS)) {
                LOGGER.error("等待黑名单配置保存超时：{}", file);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            LOGGER.error("等待黑名单配置保存被中断：{}", file, e);
        }
    }

    private static class BlockConfig {
        private Map<String, PlayerBlockSettings> servers = new TreeMap<>();
    }
}
