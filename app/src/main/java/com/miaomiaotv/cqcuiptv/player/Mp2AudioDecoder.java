package com.miaomiaotv.cqcuiptv.player;

/**
 * MPEG-1/2 Layer II 音频解码：把 PES 里连续的 MP2 字节流按帧切开，逐帧解码成 16bit PCM。
 * 内核移植自 JavaMP3（MIT，https://github.com/delthas/JavaMP3）。
 */
public final class Mp2AudioDecoder {

    private static final int[] BITRATE_L2 = {0, 32000, 48000, 56000, 64000, 80000, 96000,
            112000, 128000, 160000, 192000, 224000, 256000, 320000, 384000};
    private static final int[] RATE = {44100, 48000, 32000};

    private byte[] buf = new byte[16 * 1024];
    private int len;

    private Mp2Decoder.SoundData state;
    private byte[] pcm;

    public int sampleRate;
    public int channels = 2;

    /** 喂入一段 ES 数据，返回本次能解出的 PCM（可能为 null）。 */
    public byte[] feed(byte[] data, int offset, int count) {
        append(data, offset, count);
        while (true) {
            // 找同步字：0xFFE 且 layer == II
            int start = -1;
            for (int i = 0; i + 3 < len; i++) {
                if ((buf[i] & 0xFF) == 0xFF && (buf[i + 1] & 0xE0) == 0xE0) {
                    int layer = (buf[i + 1] >> 1) & 0x03;
                    int brIdx = (buf[i + 2] >> 4) & 0x0F;
                    int srIdx = (buf[i + 2] >> 2) & 0x03;
                    if (layer == 2 && brIdx > 0 && brIdx < 15 && srIdx < 3) {
                        start = i;
                        break;
                    }
                }
            }
            if (start < 0) {
                // 全是垃圾，留 2 字节防止跨块同步
                if (len > 2) {
                    len = 2;
                    buf[0] = buf[len - 2];
                    buf[1] = buf[len - 1];
                }
                return pcm;
            }
            if (start > 0) {
                System.arraycopy(buf, start, buf, 0, len - start);
                len -= start;
            }
            if (len < 4) {
                return pcm;
            }
            int version = (buf[1] >> 3) & 0x03;          // 3=MPEG1, 2=MPEG2, 0=MPEG2.5
            int brIdx = (buf[2] >> 4) & 0x0F;
            int srIdx = (buf[2] >> 2) & 0x03;
            int pad = (buf[2] >> 1) & 0x01;
            int rate = RATE[srIdx];
            if (version == 2) {
                rate /= 2;
            } else if (version == 0) {
                rate /= 4;
            }
            int bitrate = BITRATE_L2[brIdx];
            if (version != 3) {
                bitrate /= 2;                             // LSF 位率表是 MPEG-1 的一半
            }
            int frameLen = 144 * bitrate / rate + pad;
            if (frameLen < 24 || frameLen > 2880) {
                // 头部不可信，跳过这个字节重新找
                System.arraycopy(buf, 1, buf, 0, len - 1);
                len--;
                continue;
            }
            if (len < frameLen) {
                return pcm;
            }
            try {
                if (state == null) {
                    state = Mp2Decoder.init(buf, 0, frameLen);
                    if (state == null) {
                        drop(frameLen);
                        continue;
                    }
                    sampleRate = state.frequency;
                    channels = state.stereo == 1 ? 2 : 1;
                } else {
                    Mp2Decoder.setFrame(state, buf, 0, frameLen);
                    Mp2Decoder.decodeFrame(state);
                }
                if (state.samplesBuffer != null) {
                    pcm = state.samplesBuffer.clone();
                }
            } catch (Throwable t) {
                state = null;
            }
            drop(frameLen);
        }
    }

    public void reset() {
        state = null;
        len = 0;
        pcm = null;
    }

    private void drop(int n) {
        if (n >= len) {
            len = 0;
        } else {
            System.arraycopy(buf, n, buf, 0, len - n);
            len -= n;
        }
    }

    private void append(byte[] data, int offset, int count) {
        if (len + count > buf.length) {
            int size = buf.length;
            while (size < len + count) {
                size *= 2;
            }
            byte[] nb = new byte[size];
            System.arraycopy(buf, 0, nb, 0, len);
            buf = nb;
        }
        System.arraycopy(data, offset, buf, len, count);
        len += count;
    }
}
