package com.coloryr.allmusic.codec;

public class HudPicPosObj extends HudBasePosObj {
    public int size;
    public boolean rotate;
    public int speed;
    // 旧服务端不发送此字段，缺省时保持原有唱片背景。
    public boolean bg = true;

    public HudPicPosObj copy() {
        HudPicPosObj obj = new HudPicPosObj();
        obj.x = x;
        obj.y = y;
        obj.pos = pos;
        obj.size = size;
        obj.rotate = rotate;
        obj.enable = enable;
        obj.alpha = alpha;
        obj.speed = speed;
        obj.bg = bg;
        return obj;
    }
}
