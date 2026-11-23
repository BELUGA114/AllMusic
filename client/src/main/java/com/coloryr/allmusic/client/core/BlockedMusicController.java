package com.coloryr.allmusic.client.core;

import java.util.function.LongSupplier;
import java.util.function.Supplier;

public class BlockedMusicController {
    public interface Playback {
        void play(String url, int position);

        void stop();

        void seek(int position);

        void message(String text);
    }

    private static final long IDENTIFICATION_WAIT_MS = 1500;
    private static final long POSITION_WAIT_MS = 2000;
    private final Supplier<PlayerBlockSettings> settings;
    private final LongSupplier clock;
    private final Playback playback;
    private String url;
    private String requester;
    private boolean playing;
    private boolean blocked;
    private boolean unknownNotified;
    private long startedAt;
    private long position;
    private long positionAt;
    private boolean waitingForPosition;

    public BlockedMusicController(Supplier<PlayerBlockSettings> settings, LongSupplier clock, Playback playback) {
        this.settings = settings;
        this.clock = clock;
        this.playback = playback;
    }

    public void onPlay(String nextUrl) {
        onPlay(nextUrl, false);
    }

    public void onPlay(String nextUrl, boolean waitForPosition) {
        stop();
        url = nextUrl;
        waitingForPosition = waitForPosition;
        startedAt = positionAt = clock.getAsLong();
        evaluate(false);
    }

    public void onInfo(String info) {
        if (url == null) {
            return;
        }
        requester = parseRequester(info);
        evaluate(false);
    }

    public void onPosition(int nextPosition) {
        if (url == null) {
            return;
        }
        updatePosition(nextPosition);
        waitingForPosition = false;
        if (playing) {
            playback.seek(Math.max(0, nextPosition));
        } else {
            evaluate(false);
        }
    }

    public void onTime(long nextPosition) {
        if (url != null) {
            updatePosition(nextPosition);
            // TIME 依赖进度 HUD 开关；重进优先等 POS，丢包时以 TIME 作为超时兜底。
            evaluate(false);
        }
    }

    private void updatePosition(long nextPosition) {
        position = Math.max(0, Math.min(Integer.MAX_VALUE, nextPosition));
        positionAt = clock.getAsLong();
    }

    public void tick() {
        if (url != null) {
            evaluate(true);
        }
    }

    public void settingsChanged() {
        if (url != null) {
            evaluate(true);
        }
    }

    private void evaluate(boolean notifyUnknown) {
        PlayerBlockSettings current = settings.get();
        if (!blocked && current.contains(requester)) {
            blocked = true;
            playback.message("已在本地屏蔽 " + requester + " 的点歌");
        }
        if (blocked) {
            pause();
            return;
        }
        boolean filtering = !current.getPlayers().isEmpty();
        boolean unidentified = requester == null && filtering;
        boolean waited = clock.getAsLong() - startedAt >= IDENTIFICATION_WAIT_MS;
        if (unidentified && (!waited || current.getUnknownPolicy() == PlayerBlockSettings.UnknownPolicy.MUTE)) {
            pause();
            if (notifyUnknown && waited && !unknownNotified) {
                unknownNotified = true;
                playback.message("无法识别点歌者，本地保持静音。可用 /music block unknown play 允许未知来源播放");
            }
            return;
        }
        if (!playing) {
            if (isWaitingForPosition()) {
                return;
            }
            playing = true;
            long elapsed = Math.max(0, clock.getAsLong() - positionAt);
            int startPosition = (int) Math.min(Integer.MAX_VALUE, position + elapsed);
            playback.play(url, startPosition);
        }
    }

    private void pause() {
        if (playing) {
            playing = false;
            playback.stop();
        }
    }

    public void stop() {
        playback.stop();
        url = requester = null;
        playing = blocked = unknownNotified = false;
        waitingForPosition = false;
        position = 0;
    }

    public String getRequester() {
        return requester;
    }

    public boolean hasMusic() {
        return url != null;
    }

    public boolean isBlocked() {
        return blocked;
    }

    public boolean isWaiting() {
        return url != null && !playing && !blocked;
    }

    public boolean isWaitingForPosition() {
        return url != null && waitingForPosition && clock.getAsLong() - startedAt < POSITION_WAIT_MS;
    }

    public static String parseRequester(String info) {
        if (info == null) {
            return null;
        }
        // 仅接受默认模板的完整末行，避免歌名和歌手名中的玩家名误命中。
        String plain = info.replaceAll("(?i)§[0-9a-fk-orx]", "").trim();
        int line = Math.max(plain.lastIndexOf('\n'), plain.lastIndexOf('\r'));
        String last = plain.substring(line + 1).trim();
        if (!last.startsWith("by: ")) {
            return null;
        }
        String name = last.substring(4).trim();
        return PlayerBlockSettings.isPlayerName(name) ? name : null;
    }
}
