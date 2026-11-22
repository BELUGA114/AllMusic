package com.coloryr.allmusic.client.core;

import com.coloryr.allmusic.client.core.objs.PlayTaskObj;
import com.coloryr.allmusic.client.core.player.decoder.BuffPack;
import com.coloryr.allmusic.client.core.player.decoder.IDecoder;
import com.coloryr.allmusic.client.core.player.decoder.m4a.M4ADecoder;
import com.coloryr.allmusic.client.core.player.decoder.mp3.Mp3Decoder;
import com.coloryr.allmusic.client.core.player.decoder.ogg.OggDecoder;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.io.CloseMode;
import org.lwjgl.BufferUtils;
import org.lwjgl.openal.AL10;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.nio.Buffer;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;
import java.util.Stack;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

public class AllMusicPlayer extends InputStream {

    private final Stack<PlayTaskObj> tasks = new Stack<>();
    private final Semaphore semaphore = new Semaphore(0);
    private final Semaphore semaphoreReload = new Semaphore(0);

    private volatile PlayTaskObj nowTask;
    private volatile HttpGet activeRequest;
    private volatile long generation;
    private CloseableHttpResponse response;
    private BufferedInputStream content;
    private volatile boolean isClose = false;
    private volatile boolean reload = false;
    private IDecoder decoder;
    private volatile boolean isPlay = false;
    private volatile boolean wait = false;
    private volatile int index = -1;
    private final IntBuffer source;
    private long local;
    private volatile boolean isRun = true;
    private boolean isChat;
    private int chatCount = 0;

    public AllMusicPlayer(IntBuffer source) {
        this.source = source;
        new Thread(this::run, "allmusic_run").start();
        AllMusicCore.service.scheduleAtFixedRate(this::timerTick, 0, 10, TimeUnit.MILLISECONDS);
    }

    public void stop() {
        isRun = false;
        closePlayer();
        semaphore.release();
        semaphoreReload.release();
    }

    public void setChat() {
        isChat = true;
    }

    public void timerTick() {
        PlayTaskObj task = nowTask;
        if (isPlay && task != null && task.generation == generation) {
            task.time = (int) Math.min(Integer.MAX_VALUE, (long) task.time + 10);
        }
    }

    public boolean isPlay() {
        return isPlay;
    }

    public void setTime(int time) {
        synchronized (tasks) {
            PlayTaskObj task = tasks.isEmpty() ? nowTask : tasks.peek();
            if (task == null || task.generation != generation) {
                return;
            }
            String url = task.url;
            closePlayer();
            queueMusic(url, time);
        }
    }

    public void connect() throws IOException {
        streamClose();
        HttpGet request = new HttpGet(nowTask.url);
        request.setHeader("Range", "bytes=" + local + "-");
        synchronized (tasks) {
            if (isCancelled()) {
                throw new InterruptedIOException("播放任务已取消");
            }
            activeRequest = request;
        }
        response = AllMusicCore.client.execute(request);
        if (isCancelled()) {
            response.close(CloseMode.IMMEDIATE);
            throw new InterruptedIOException("播放任务已取消");
        }
        int statusCode = response.getCode();
        if (statusCode < 200 || statusCode >= 400) {
            throw new IOException("Unexpected code " + statusCode);
        }
        HttpEntity entity = response.getEntity();
        if (entity == null) {
            throw new IOException("Response entity is null");
        }
        content = new BufferedInputStream(entity.getContent());
    }

    private void resetSource() {
        AL10.alSourceStop(index);
        AL10.alSourcei(index, AL10.AL_BUFFER, AL10.AL_NONE);

        int queued;
        do {
            queued = AL10.alGetSourcei(index, AL10.AL_BUFFERS_QUEUED);
            if (queued > 0) {
                int buffer = AL10.alSourceUnqueueBuffers(index);
                if (buffer != 0) {
                    AL10.alDeleteBuffers(buffer);
                }
            }
        } while (queued > 0);
    }

    private void checkChat() {
        if (isChat) {
            chatCount++;
            if (chatCount >= 200) {
                isChat = false;
                chatCount = 0;
            }
        }
    }

    private void dequeue() {
        int processed = AL10.alGetSourcei(index, AL10.AL_BUFFERS_PROCESSED);
        for (int i = 0; i < processed; i++) {
            int buffer = AL10.alSourceUnqueueBuffers(index);
            if (buffer != 0) {
                AL10.alDeleteBuffers(buffer);
            }
        }
    }

    private void checkVolume() {
        float temp = AllMusicCore.bridge.getVolume();
        float now = AL10.alGetSourcef(index, AL10.AL_GAIN);
        if (isChat) {
            temp *= 0.5F;
        }
        if (now != temp) {
            AL10.alSourcef(index, AL10.AL_GAIN, temp);
        }
    }

    private void run() {
        while (isRun) {
            try {
                semaphore.acquire();
                if (!isRun) {
                    return;
                }

                if (index == -1) {
                    index = AL10.alGenSources();
                    if (index == 0 && source != null) {
                        index = source.get(0);
                        if (index == 0) {
                            AllMusicCore.bridge.sendMessage("音频源创建失败");
                            return;
                        }
                    }
                }

                dequeue();
                resetSource();

                synchronized (tasks) {
                    if (tasks.isEmpty()) {
                        continue;
                    }
                    nowTask = tasks.pop();
                    tasks.clear();
                    isClose = false;
                }
                try {
                    local = 0;
                    connect();
                } catch (Exception e) {
                    if (!isCancellation(e)) {
                        e.printStackTrace();
                        AllMusicCore.bridge.sendMessage("获取音乐失败");
                    }
                    continue;
                }

                if (!prepareDecoder() || isCancelled()) {
                    continue;
                }

                synchronized (tasks) {
                    if (isCancelled()) {
                        continue;
                    }
                    isPlay = true;
                }

                int frequency = decoder.getOutputFrequency();
                int channels = decoder.getOutputChannels();
                if (channels != 1 && channels != 2) continue;
                if (nowTask.time != 0) {
                    decoder.set(nowTask.time);
                }

                while (true) {
                    if (!isRun) {
                        return;
                    }
                    if (isCancelled()) {
                        break;
                    }
                    if (!AL10.alIsSource(index)) {
                        setReload();
                        break;
                    }
                    try {
                        while (AL10.alGetSourcei(index, AL10.AL_BUFFERS_QUEUED) < AllMusicCore.config.queueSize) {
                            if (!isRun) {
                                return;
                            }
                            if (isCancelled()) {
                                break;
                            }
                            BuffPack output = decoder.decodeFrame();
                            if (output == null || isCancelled()) {
                                break;
                            }
                            ByteBuffer byteBuffer = BufferUtils.createByteBuffer(output.len)
                                    .put(output.buff, 0, output.len);
                            ((Buffer) byteBuffer).flip();

                            IntBuffer intBuffer = BufferUtils.createIntBuffer(1);
                            AL10.alGenBuffers(intBuffer);
                            int buffer = intBuffer.get(0);

                            if (buffer == 0) continue;

                            AL10.alBufferData(
                                    buffer,
                                    channels == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16,
                                    byteBuffer,
                                    frequency);

                            // 取消与入队、启播共用锁，取消后的旧解码结果不能重新发声。
                            synchronized (tasks) {
                                if (isCancelled()) {
                                    AL10.alDeleteBuffers(buffer);
                                    break;
                                }
                                AL10.alSourceQueueBuffers(index, buffer);
                                if (AL10.alGetSourcei(index, AL10.AL_SOURCE_STATE) != AL10.AL_PLAYING) {
                                    AL10.alSourcePlay(index);
                                }
                            }
                        }

                        Thread.sleep(5);

                        checkChat();
                        checkVolume();
                        dequeue();
                    } catch (Exception e) {
                        if (!isCancellation(e)) {
                            e.printStackTrace();
                        }
                        break;
                    }
                }

                streamClose();
                decodeClose();

                if (AL10.alIsSource(index)) {
                    while (!isCancelled() && AL10.alGetSourcei(index, AL10.AL_SOURCE_STATE) == AL10.AL_PLAYING) {
                        Thread.sleep(50);
                        checkChat();
                        checkVolume();
                        dequeue();
                    }

                    dequeue();
                    resetSource();
                }

                isPlay = false;

                if (!isRun) {
                    return;
                }
            } catch (Exception e) {
                if (!isCancellation(e)) {
                    e.printStackTrace();
                    AllMusicCore.bridge.sendMessage("播放音乐失败");
                }
            } finally {
                isPlay = false;
                try {
                    streamClose();
                    decodeClose();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                resumeReload();
            }
        }
    }

    private void resumeReload() {
        if (!reload || !isRun) {
            return;
        }
        wait = true;
        try {
            if (semaphoreReload.tryAcquire(1, TimeUnit.SECONDS)) {
                synchronized (tasks) {
                    if (reload && nowTask != null && nowTask.generation == generation) {
                        reload = false;
                        index = -1;
                        tasks.push(nowTask);
                        semaphore.release();
                    }
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            AllMusicCore.bridge.sendMessage("重载音频被中断");
        } finally {
            wait = false;
        }
    }

    public void tick() {
        if (wait) {
            wait = false;
            semaphoreReload.release();
        }
    }

    public void closePlayer() {
        synchronized (tasks) {
            generation++;
            isClose = true;
            isPlay = false;
            reload = false;
            tasks.clear();
            if (activeRequest != null) {
                activeRequest.cancel();
            }
            if (index > 0 && AL10.alIsSource(index)) {
                AL10.alSourceStop(index);
            }
        }
        semaphore.release();
    }

    public void setMusic(String url) {
        setMusic(url, 0);
    }

    public void setMusic(String url, int time) {
        synchronized (tasks) {
            if (nowTask != null && nowTask.generation == generation && nowTask.url.equals(url)) {
                return;
            }
            closePlayer();
            queueMusic(url, time);
        }
    }

    private void queueMusic(String url, int time) {
        PlayTaskObj taskObj = new PlayTaskObj();
        taskObj.time = Math.max(0, time);
        taskObj.url = url;
        taskObj.generation = generation;
        tasks.push(taskObj);
        semaphore.release();
    }

    private boolean isCancelled() {
        return !isRun || isClose || nowTask == null || nowTask.generation != generation;
    }

    private boolean isCancellation(Exception error) {
        // 主动取消导致的网络中断不作为播放故障，其他异常仍须报告。
        return isCancelled() && (error instanceof IOException
                || error instanceof java.util.concurrent.CancellationException);
    }

    private boolean prepareDecoder() throws Exception {
        byte[] head = new byte[4];
        content.mark(4);
        content.read(head);
        content.reset();
        if (isCancelled()) {
            return false;
        }
        if (head[0] == 0 && head[1] == 0 && head[2] == 0 && head[3] == 0x1c) {
            decoder = new M4ADecoder(this);
        } else if ((head[0] == 'I' && head[1] == 'D' && head[2] == '3')
                || (head[0] == (byte) 0xFF && head[1] == (byte) 0xFB)
                || (head[0] == (byte) 0xFF && head[1] == (byte) 0xFA)
                || (head[0] == (byte) 0xFF && head[1] == (byte) 0xF3)
                || (head[0] == (byte) 0xFF && head[1] == (byte) 0xF2)
                || (head[0] == (byte) 0xFF && head[1] == (byte) 0xE3)
                || (head[0] == (byte) 0xFF && head[1] == (byte) 0xE2)) {
            decoder = new Mp3Decoder(this);
        } else {
            decoder = new OggDecoder(this);
        }
        if (!decoder.set()) {
            AllMusicCore.bridge.sendMessage("不支持这样的文件播放");
            return false;
        }
        return true;
    }

    private void streamClose() throws IOException {
        if (response != null) {
            response.close(CloseMode.IMMEDIATE);
            response = null;
        }
        if (content != null) {
            content.close();
            content = null;
        }
    }

    private void decodeClose() throws Exception {
        if (decoder != null) {
            decoder.close();
            decoder = null;
        }
    }

    public void setReload() {
        synchronized (tasks) {
            if (isPlay && nowTask != null && nowTask.generation == generation) {
                reload = true;
                isClose = true;
                if (activeRequest != null) {
                    activeRequest.cancel();
                }
            }
        }
    }

    @Override
    public int read() throws IOException {
        local++;
        return content.read();
    }

    @Override
    public int read(byte[] buf) throws IOException {
        int temp = content.read(buf);
        local += temp;
        return temp;
    }

    @Override
    public long skip(long n) throws IOException {
        if (n <= 2048) {
            long temp = content.skip(n);
            local += temp;
            return temp;
        } else {
            local += n;
            connect();
        }
        return n;
    }

    @Override
    public synchronized int read(byte[] buf, int off, int len) throws IOException {
        try {
            int temp = content.read(buf, off, len);
            local += temp;
            return temp;
        } catch (IOException e) {
            if (isCancelled()) {
                throw e;
            }
            connect();
            return this.read(buf, off, len);
        }
    }

    @Override
    public synchronized int available() throws IOException {
        return content.available();
    }

    @Override
    public void close() throws IOException {
        streamClose();
    }

    public void setLocal(long local) throws IOException {
        streamClose();
        this.local = local;
        connect();
    }
}
