package com.coloryr.allmusic.client.core;

import com.coloryr.allmusic.client.core.render.PictureFrameBuffer;
import com.coloryr.allmusic.client.core.render.TextFrameBuffer;
import com.coloryr.allmusic.client.core.render.TextureRender;
import com.coloryr.allmusic.codec.HudPosObj;
import com.coloryr.allmusic.codec.HudPosType;
import com.sun.net.httpserver.HttpServer;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

final class HudResourcesTest {
    static void run() throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        ImageIO.write(new BufferedImage(4, 4, BufferedImage.TYPE_INT_ARGB), "png", bytes);
        byte[] png = bytes.toByteArray();
        CountDownLatch slowEntered = new CountDownLatch(1);
        CountDownLatch releaseSlow = new CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/cover", exchange -> {
            if (exchange.getRequestURI().getQuery() != null) {
                slowEntered.countDown();
                try {
                    if (!releaseSlow.await(5, TimeUnit.SECONDS)) {
                        throw new IOException("封面测试等待超时");
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IOException(e);
                }
            }
            try {
                exchange.sendResponseHeaders(200, png.length);
                exchange.getResponseBody().write(png);
            } finally {
                exchange.close();
            }
        });
        server.start();
        String url = "http://127.0.0.1:" + server.getAddress().getPort() + "/cover";
        FakeBridge bridge = new FakeBridge(png);
        AllMusicCore.bridge = bridge;
        try (CloseableHttpClient client = HttpClients.createDefault()) {
            AllMusicCore.client = client;
            AllMusicHud hud = new AllMusicHud(240);
            try {
                ClientCompatibilityTest.check(hud.setPos(HudPosObj.make()), "旧 HUD 数据可用");
                hud.setImg(url);
                FakePicture first = bridge.pictures.get(0);
                await(hud, () -> first.uploads == 1);

                hud.setPicSize(512);
                FakePicture second = bridge.pictures.get(1);
                ClientCompatibilityTest.check(first.disposals == 0, "新封面上传前保留旧资源");
                await(hud, () -> second.uploads == 1);
                ClientCompatibilityTest.check(first.disposals == 1, "替换后释放旧封面");

                hud.setPicSize(256);
                FakePicture abandoned = bridge.pictures.get(2);
                hud.setPicSize(128);
                FakePicture current = bridge.pictures.get(3);
                ClientCompatibilityTest.check(abandoned.disposals == 1, "连续改尺寸释放未使用的缓冲");
                await(hud, () -> current.uploads == 1);
                ClientCompatibilityTest.check(second.disposals == 1, "最终尺寸替换旧资源");

                hud.setImg(url + "?slow");
                await(hud, () -> slowEntered.getCount() == 0);
                int beforeClear = current.uploads;
                hud.clear();
                releaseSlow.countDown();
                hud.setImg(url);
                await(hud, () -> current.uploads > beforeClear);
                ClientCompatibilityTest.check(current.uploads == beforeClear + 1, "切歌后旧封面结果被丢弃");

                hud.setPicSize(320);
                hud.stop();
                ClientCompatibilityTest.check(current.disposals == 1, "停止时释放当前封面");
                ClientCompatibilityTest.check(bridge.pictures.get(4).disposals == 1, "停止时释放待替换封面");
            } finally {
                hud.stop();
            }
        } finally {
            releaseSlow.countDown();
            server.stop(0);
        }
    }

    private static void await(AllMusicHud hud, BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            hud.update();
            Thread.sleep(10);
        }
        ClientCompatibilityTest.check(condition.getAsBoolean(), "封面上传完成");
    }

    private static final class FakePicture extends PictureFrameBuffer {
        private final int size;
        private int uploads;
        private int disposals;

        private FakePicture(int size) {
            this.size = size;
        }

        @Override
        public void update(byte[] source, byte[] rotate) {
            try {
                BufferedImage image = ImageIO.read(new ByteArrayInputStream(source));
                ClientCompatibilityTest.check(image.getWidth() == size && image.getHeight() == size,
                        "图片尺寸必须与缓冲匹配");
                uploads++;
            } catch (IOException e) {
                throw new AssertionError(e);
            }
        }

        @Override
        public void dispose() {
            disposals++;
        }

        @Override
        public void draw(boolean rotate, int size, float x, float y, int angle, HudPosType direction, float alpha) {
        }
    }

    private static final class FakeBridge implements AllMusicBridge {
        private final byte[] png;
        private final List<FakePicture> pictures = new ArrayList<>();

        private FakeBridge(byte[] png) {
            this.png = png;
        }

        public int getScreenWidth() { return 640; }
        public int getScreenHeight() { return 480; }
        public int getTextWidth(String text) { return text.length() * 8; }
        public int getFontHeight() { return 9; }
        public float getVolume() { return 1; }
        public String getServerKey() { return "server:test"; }
        public void stopPlayMusic() { }
        public void sendFeedback(String text) { }
        public void sendMessage(String text) { throw new AssertionError(text); }
        public void kick() { throw new AssertionError("旧服务端不能被踢出"); }
        public String readText(String file) { return file.endsWith("offset.txt") ? "0" : "0.8"; }
        public InputStream readFile(String file) { return new ByteArrayInputStream(png); }

        public PictureFrameBuffer makePictureRender(int size) {
            FakePicture picture = new FakePicture(size);
            pictures.add(picture);
            return picture;
        }

        public TextFrameBuffer<String> makeTextRender(String name) {
            return new TextFrameBuffer<String>() {
                public void putText(String text, int y, int color, boolean shadow) { }
                public void drawLine(float x, float y, float alpha, int line) { }
                public Point2f getLine(int line) { return new Point2f(1, 1); }
                public void draw(float alpha, int x, int y, int maxWidth, HudPosType direction) { }
                public void drawWithState(float alpha, int x, int y, int maxWidth, float state, HudPosType direction) { }
            };
        }

        public TextureRender makeTextureRender(String file) {
            return new TextureRender(file) {
                public void drawPic(float x, float y, float alpha) { }
                public void drawPic(float x, float y, float width, float alpha) { }
                public void drawPic(float x, float y, float width, float height, HudPosType direction, float alpha) { }
            };
        }
    }
}
