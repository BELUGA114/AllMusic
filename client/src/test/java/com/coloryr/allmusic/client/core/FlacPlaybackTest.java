package com.coloryr.allmusic.client.core;

import com.coloryr.allmusic.client.core.player.decoder.BuffPack;
import com.coloryr.allmusic.client.core.player.decoder.flac.FlacDecoder;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;

public class FlacPlaybackTest {
    private static final int BLOCK_SIZE = 256;
    private static final int SAMPLE_RATE = 8000;

    public static void main(String[] args) throws Exception {
        try {
            for (int depth : new int[]{8, 16, 24, 32}) {
                pcmAndSeek(depth, true, true);
            }
            pcmAndSeek(16, false, true);
            pcmAndSeek(16, false, false);
            estimatedSeek();
            seekFailure();
            System.out.println("FLAC playback checks passed");
        } finally {
            AllMusicCore.service.shutdownNow();
        }
    }

    private static void pcmAndSeek(int depth, boolean table, boolean lengthKnown) throws Exception {
        MemoryPlayer player = new MemoryPlayer(fixture(depth, table), lengthKnown);
        FlacDecoder decoder = new FlacDecoder(player);
        try {
            ClientCompatibilityTest.check(decoder.set(), "识别 FLAC");
            ClientCompatibilityTest.check(decoder.getOutputFrequency() == SAMPLE_RATE && decoder.getOutputChannels() == 1,
                    "FLAC 采样率和声道");
            checkSamples(decoder.decodeFrame(), 0, BLOCK_SIZE, depth);
            decoder.set(72);
            checkSamples(decoder.decodeFrame(), 576, 192, depth);
            ClientCompatibilityTest.check(decoder.decodeFrame() == null, "FLAC EOF 返回空帧");
            decoder.set(64);
            checkSamples(decoder.decodeFrame(), 512, BLOCK_SIZE, depth);
            decoder.set(-1);
            checkSamples(decoder.decodeFrame(), 0, BLOCK_SIZE, depth);
            decoder.set(10000);
            checkSamples(decoder.decodeFrame(), 767, 1, depth);
        } finally {
            decoder.close();
            player.stop();
        }
    }

    private static void seekFailure() throws Exception {
        MemoryPlayer player = new MemoryPlayer(fixture(16, true), true);
        FlacDecoder decoder = new FlacDecoder(player);
        try {
            decoder.set();
            player.failSeek = true;
            boolean failed = false;
            try {
                decoder.set(72);
            } catch (IOException e) {
                failed = true;
            }
            ClientCompatibilityTest.check(failed, "定位失败交给播放器处理，不能静默从错误进度发声");
        } finally {
            decoder.close();
            player.stop();
        }
    }

    private static void estimatedSeek() throws Exception {
        MemoryPlayer player = new MemoryPlayer(fixture(32, false, 120), true);
        FlacDecoder decoder = new FlacDecoder(player);
        try {
            decoder.set();
            decoder.set(3000);
            checkSamples(decoder.decodeFrame(), 24000, 64, 32);
            ClientCompatibilityTest.check(player.lastSeek > 42, "无定位表时从估算字节位置扫描帧");
            ClientCompatibilityTest.check(player.bytesRead < 24000 * 4, "无定位表时不必读取目标之前的全部音频");
        } finally {
            decoder.close();
            player.stop();
        }
    }

    private static void checkSamples(BuffPack frame, int start, int count, int depth) {
        ClientCompatibilityTest.check(frame != null && frame.len == count * 2, "16 位 PCM 缓冲长度");
        for (int i = 0; i < count; i++) {
            short actual = (short) ((frame.buff[i * 2] & 255) | (frame.buff[i * 2 + 1] << 8));
            int expected = depth == 8 ? ((start + i) % 256 - 128) << 8 : (start + i) % 65536 - 32768;
            ClientCompatibilityTest.check(actual == expected, "PCM 样本及定位偏移");
        }
    }

    // 生成带校验和的单声道 FLAC，样本值固定，用来独立验证解码和定位。
    private static byte[] fixture(int depth, boolean table) throws IOException {
        return fixture(depth, table, 3);
    }

    private static byte[] fixture(int depth, boolean table, int frames) throws IOException {
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        output.write(new byte[]{'f', 'L', 'a', 'C'});
        output.write(table ? 0 : 128);
        output.write(new byte[]{0, 0, 34});
        ByteBuffer info = ByteBuffer.allocate(34);
        info.putShort((short) BLOCK_SIZE).putShort((short) BLOCK_SIZE);
        info.put(new byte[6]);
        info.putLong(((long) SAMPLE_RATE << 44) | ((long) (depth - 1) << 36) | (BLOCK_SIZE * (long) frames));
        info.put(new byte[16]);
        output.write(info.array());
        byte[] firstFrame = frame(depth, 0);
        if (table) {
            output.write(new byte[]{(byte) 131, 0, 0, 18});
            output.write(ByteBuffer.allocate(18).putLong(512).putLong(firstFrame.length * 2L)
                    .putShort((short) BLOCK_SIZE).array());
        }
        output.write(firstFrame);
        for (int i = 1; i < frames; i++) {
            output.write(frame(depth, i));
        }
        return output.toByteArray();
    }

    private static byte[] frame(int depth, int index) throws IOException {
        ByteArrayOutputStream frame = new ByteArrayOutputStream();
        int code = depth == 8 ? 1 : depth == 16 ? 4 : depth == 24 ? 6 : 0;
        frame.write(new byte[]{(byte) 255, (byte) 248, (byte) 132, (byte) (code << 1), (byte) index});
        frame.write(crc(frame.toByteArray(), 8, 7));
        frame.write(2);
        for (int i = 0; i < BLOCK_SIZE; i++) {
            int sample = index * BLOCK_SIZE + i;
            int value = depth == 8 ? sample % 256 - 128 : (sample % 65536 - 32768) << (depth - 16);
            for (int shift = depth - 8; shift >= 0; shift -= 8) {
                frame.write(value >>> shift);
            }
        }
        int checksum = crc(frame.toByteArray(), 16, 0x8005);
        frame.write(checksum >>> 8);
        frame.write(checksum);
        return frame.toByteArray();
    }

    private static int crc(byte[] data, int bits, int polynomial) {
        int value = 0;
        for (byte item : data) {
            value ^= (item & 255) << (bits - 8);
            for (int i = 0; i < 8; i++) {
                value = (value & (1 << (bits - 1))) != 0 ? (value << 1) ^ polynomial : value << 1;
                value &= (1 << bits) - 1;
            }
        }
        return value;
    }

    private static final class MemoryPlayer extends AllMusicPlayer {
        private final byte[] data;
        private final boolean lengthKnown;
        private int position;
        private boolean failSeek;
        private long lastSeek;
        private int bytesRead;

        private MemoryPlayer(byte[] data, boolean lengthKnown) {
            super(null);
            this.data = data;
            this.lengthKnown = lengthKnown;
        }

        @Override
        public int read() {
            return position < data.length ? data[position++] & 255 : -1;
        }

        @Override
        public int read(byte[] buffer, int offset, int length) {
            if (position >= data.length) {
                return -1;
            }
            int count = Math.min(length, data.length - position);
            System.arraycopy(data, position, buffer, offset, count);
            position += count;
            bytesRead += count;
            return count;
        }

        @Override
        public int available() {
            return data.length - position;
        }

        @Override
        public void setLocal(long next) throws IOException {
            if (failSeek) {
                throw new IOException("模拟定位请求失败");
            }
            position = (int) next;
            lastSeek = next;
        }

        @Override
        public long getContentLength() {
            return lengthKnown ? data.length : -1;
        }
    }
}
