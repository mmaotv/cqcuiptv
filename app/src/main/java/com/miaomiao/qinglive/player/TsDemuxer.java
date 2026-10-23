package com.miaomiao.qinglive.player;

/**
 * MPEG-TS 解复用：PAT/PMT 找流，按 PID 拼 PES，再按时间戳把 ES 交给上层。
 * 只做直播需要的事：不缓存、不 seek，遇到坏包就丢。
 */
public final class TsDemuxer {

    public static final int VIDEO_H264 = 1;
    public static final int VIDEO_HEVC = 2;

    public static final int AUDIO_MP2 = 10;
    public static final int AUDIO_AAC = 11;
    public static final int AUDIO_AC3 = 12;
    public static final int AUDIO_EAC3 = 13;

    public interface Listener {
        /** 第一次拿到 PMT 时回调。 */
        void onTracks(int videoCodec, int audioCodec);

        /**
         * 从码流里凑齐的 VPS/SPS/PPS，用作 MediaCodec 的 csd-0 / csd-1。
         * 不带参数集去 configure，部分固件（尤其 HEVC 10bit 4K）会只出绿屏。
         */
        void onParameterSets(byte[] csd0, byte[] csd1);

        void onVideo(byte[] data, int length, long ptsUs, boolean keyframe);

        /** 从 SPS 解出的真实分辨率，只回调一次。 */
        void onVideoSize(int width, int height);

        void onAudio(byte[] data, int length, long ptsUs);
    }

    private final Listener listener;

    private byte[] in = new byte[128 * 1024];
    private int inLen;

    private int pmtPid = -1;
    private int videoPid = -1;
    private int audioPid = -1;
    private int videoCodec;
    private int audioCodec;
    private boolean tracksReady;

    private int videoWidth;
    private int videoHeight;
    private boolean paramsSent;

    private final Pid video = new Pid();
    private final Pid audio = new Pid();

    public TsDemuxer(Listener listener) {
        this.listener = listener;
    }

    private static final class Pid {
        byte[] buf = new byte[256 * 1024];
        int len;
        long ptsUs = -1;
        boolean active;
    }

    public void feed(byte[] data, int offset, int length) {
        if (inLen + length > in.length) {
            byte[] nb = new byte[Math.max(in.length * 2, inLen + length)];
            System.arraycopy(in, 0, nb, 0, inLen);
            in = nb;
        }
        System.arraycopy(data, offset, in, inLen, length);
        inLen += length;

        int pos = 0;
        while (inLen - pos >= 188) {
            if ((in[pos] & 0xFF) != 0x47) {
                pos++;
                continue;
            }
            // 校验下一个包，避免假同步
            if (inLen - pos >= 376 && (in[pos + 188] & 0xFF) != 0x47) {
                pos++;
                continue;
            }
            parsePacket(pos);
            pos += 188;
        }
        if (pos > 0) {
            System.arraycopy(in, pos, in, 0, inLen - pos);
            inLen -= pos;
        }
    }

    private void parsePacket(int off) {
        int b1 = in[off + 1] & 0xFF;
        int b3 = in[off + 3] & 0xFF;
        boolean pusi = (b1 & 0x40) != 0;
        int pid = ((b1 & 0x1F) << 8) | (in[off + 2] & 0xFF);
        int afc = (b3 >> 4) & 0x03;
        int payload = off + 4;
        int end = off + 188;
        if (afc == 0 || afc == 2) {
            return;
        }
        if (afc == 3) {
            int afLen = in[payload] & 0xFF;
            payload += 1 + afLen;
            if (payload >= end) {
                return;
            }
        }
        if (pid == 0) {
            parsePat(payload, end);
        } else if (pid == pmtPid) {
            parsePmt(payload, end);
        } else if (pid == videoPid) {
            parsePes(video, payload, end, pusi, true);
        } else if (pid == audioPid) {
            parsePes(audio, payload, end, pusi, false);
        }
    }

    private void parsePat(int payload, int end) {
        int p = payload;
        int pointer = in[p] & 0xFF;
        p += 1 + pointer;
        if (p + 8 > end) {
            return;
        }
        int sectionLen = ((in[p + 1] & 0x0F) << 8) | (in[p + 2] & 0xFF);
        int stop = Math.min(end, p + 3 + sectionLen - 4);
        for (int i = p + 8; i + 4 <= stop; i += 4) {
            int program = ((in[i] & 0xFF) << 8) | (in[i + 1] & 0xFF);
            int pid = ((in[i + 2] & 0x1F) << 8) | (in[i + 3] & 0xFF);
            if (program != 0) {
                pmtPid = pid;
                return;
            }
        }
    }

    private void parsePmt(int payload, int end) {
        int p = payload;
        int pointer = in[p] & 0xFF;
        p += 1 + pointer;
        if (p + 12 > end || (in[p] & 0xFF) != 0x02) {
            return;
        }
        int sectionLen = ((in[p + 1] & 0x0F) << 8) | (in[p + 2] & 0xFF);
        int stop = Math.min(end, p + 3 + sectionLen - 4);
        int programInfoLen = ((in[p + 10] & 0x0F) << 8) | (in[p + 11] & 0xFF);
        int i = p + 12 + programInfoLen;
        int newVideo = -1, newAudio = -1;
        int newVideoCodec = 0, newAudioCodec = 0;
        while (i + 5 <= stop) {
            int streamType = in[i] & 0xFF;
            int esPid = ((in[i + 1] & 0x1F) << 8) | (in[i + 2] & 0xFF);
            int esInfoLen = ((in[i + 3] & 0x0F) << 8) | (in[i + 4] & 0xFF);
            int d = i + 5;
            int dEnd = Math.min(stop, d + esInfoLen);
            int codec = 0;
            switch (streamType) {
                case 0x1B:
                    codec = VIDEO_H264;
                    break;
                case 0x24:
                    codec = VIDEO_HEVC;
                    break;
                case 0x03:
                case 0x04:
                    codec = AUDIO_MP2;
                    break;
                case 0x0F:
                    codec = AUDIO_AAC;
                    break;
                case 0x81:
                    codec = AUDIO_AC3;
                    break;
                case 0x87:
                    codec = AUDIO_EAC3;
                    break;
                case 0x06:
                    while (d < dEnd) {
                        int tag = in[d] & 0xFF;
                        if (tag == 0x6A) {
                            codec = AUDIO_AC3;
                        } else if (tag == 0x7A) {
                            codec = AUDIO_EAC3;
                        }
                        if (d + 1 >= dEnd) {
                            break;
                        }
                        d += 2 + (in[d + 1] & 0xFF);
                    }
                    break;
                default:
                    break;
            }
            if (codec >= VIDEO_H264 && codec <= VIDEO_HEVC) {
                if (newVideo < 0) {
                    newVideo = esPid;
                    newVideoCodec = codec;
                }
            } else if (codec >= AUDIO_MP2) {
                if (newAudio < 0) {
                    newAudio = esPid;
                    newAudioCodec = codec;
                }
            }
            i += 5 + esInfoLen;
        }
        if (newVideo < 0 && newAudio < 0) {
            return;
        }
        boolean changed = newVideo != videoPid || newAudio != audioPid;
        videoPid = newVideo;
        audioPid = newAudio;
        videoCodec = newVideoCodec;
        audioCodec = newAudioCodec;
        if (changed) {
            video.len = 0;
            audio.len = 0;
            video.active = false;
            audio.active = false;
        }
        if (!tracksReady) {
            tracksReady = true;
            listener.onTracks(videoCodec, audioCodec);
        }
    }

    private void parsePes(Pid state, int payload, int end, boolean pusi, boolean isVideo) {
        if (pusi) {
            if (state.len > 0) {
                emit(state, isVideo);
            }
            state.len = 0;
            state.ptsUs = -1;
            if (end - payload < 9) {
                return;
            }
            if ((in[payload] & 0xFF) != 0 || (in[payload + 1] & 0xFF) != 0
                    || (in[payload + 2] & 0xFF) != 1) {
                return;
            }
            int flags = in[payload + 7] & 0xFF;
            int headerLen = in[payload + 8] & 0xFF;
            int ptsFlag = (flags >> 6) & 0x03;
            if (ptsFlag == 2 || ptsFlag == 3) {
                long pts = ((long) (in[payload + 9] & 0x0E) << 29)
                        | ((long) (in[payload + 10] & 0xFF) << 22)
                        | ((long) (in[payload + 11] & 0xFE) << 14)
                        | ((long) (in[payload + 12] & 0xFF) << 7)
                        | ((long) (in[payload + 13] & 0xFE) >> 1);
                state.ptsUs = pts * 100L / 9L;
            }
            payload += 9 + headerLen;
            if (payload >= end) {
                return;
            }
        } else if (state.len == 0 && state.ptsUs < 0) {
            // 还没拿到起始包，丢掉
            return;
        }
        append(state, payload, end);
    }

    private void append(Pid state, int from, int to) {
        int n = to - from;
        if (state.len + n > state.buf.length) {
            byte[] nb = new byte[Math.max(state.buf.length * 2, state.len + n)];
            System.arraycopy(state.buf, 0, nb, 0, state.len);
            state.buf = nb;
        }
        System.arraycopy(in, from, state.buf, state.len, n);
        state.len += n;
        state.active = true;
    }

    /** 把一帧数据交出去。缓冲区是复用的，上层必须立即消费。 */
    private void emit(Pid state, boolean isVideo) {
        if (state.len == 0) {
            return;
        }
        if (isVideo) {
            if (!paramsSent) {
                collectParameterSets(state.buf, state.len);
            }
            if (videoWidth == 0) {
                int[] size = SpsParser.parse(state.buf, state.len, videoCodec == VIDEO_HEVC);
                if (size != null) {
                    videoWidth = size[0];
                    videoHeight = size[1];
                    listener.onVideoSize(videoWidth, videoHeight);
                }
            }
            listener.onVideo(state.buf, state.len, state.ptsUs,
                    isKeyframe(state.buf, state.len, videoCodec));
        } else {
            listener.onAudio(state.buf, state.len, state.ptsUs);
        }
        state.len = 0;
        state.ptsUs = -1;
    }

    /**
     * 从 Annex-B 码流里捞 VPS/SPS/PPS，拼成 csd。HEVC 三个塞一个 csd-0，
     * AVC 分开：csd-0=SPS、csd-1=PPS。
     */
    private void collectParameterSets(byte[] d, int len) {
        boolean hevc = videoCodec == VIDEO_HEVC;
        byte[] vps = null;
        byte[] sps = null;
        byte[] pps = null;
        int i = 0;
        while (true) {
            int start = findStartCode(d, i, len);
            if (start < 0) {
                break;
            }
            int nalStart = start + 3;
            int next = findStartCode(d, nalStart, len);
            int nalEnd = next < 0 ? len : next;
            if (nalEnd > nalStart) {
                int type = hevc ? ((d[nalStart] >> 1) & 0x3F) : (d[nalStart] & 0x1F);
                byte[] nal = withStartCode(d, nalStart, nalEnd);
                if (hevc) {
                    if (type == 32 && vps == null) {
                        vps = nal;
                    } else if (type == 33 && sps == null) {
                        sps = nal;
                    } else if (type == 34 && pps == null) {
                        pps = nal;
                    }
                } else {
                    if (type == 7 && sps == null) {
                        sps = nal;
                    } else if (type == 8 && pps == null) {
                        pps = nal;
                    }
                }
            }
            if (next < 0) {
                break;
            }
            i = next;
        }

        if (hevc) {
            if (sps == null) {
                return;
            }
            byte[] csd0 = concat3(vps, sps, pps);
            paramsSent = true;
            listener.onParameterSets(csd0, null);
        } else {
            if (sps == null) {
                return;
            }
            paramsSent = true;
            listener.onParameterSets(sps, pps);
        }
    }

    private static int findStartCode(byte[] d, int from, int len) {
        for (int i = from; i + 3 <= len; i++) {
            if (d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1) {
                return i;
            }
        }
        return -1;
    }

    /** 参数集统一补 4 字节起始码，MediaCodec 两种都能吃，统一更好排错。 */
    private static byte[] withStartCode(byte[] d, int from, int to) {
        byte[] out = new byte[4 + to - from];
        out[0] = 0;
        out[1] = 0;
        out[2] = 0;
        out[3] = 1;
        System.arraycopy(d, from, out, 4, to - from);
        return out;
    }

    private static byte[] concat3(byte[] a, byte[] b, byte[] c) {
        int n = (a == null ? 0 : a.length) + (b == null ? 0 : b.length) + (c == null ? 0 : c.length);
        byte[] out = new byte[n];
        int p = 0;
        if (a != null) {
            System.arraycopy(a, 0, out, p, a.length);
            p += a.length;
        }
        if (b != null) {
            System.arraycopy(b, 0, out, p, b.length);
            p += b.length;
        }
        if (c != null) {
            System.arraycopy(c, 0, out, p, c.length);
        }
        return out;
    }

    /** 粗略判断关键帧，用于“等第一个 I 帧再起播”。 */
    private boolean isKeyframe(byte[] data, int len, int codec) {
        int i = 0;
        while (i + 4 < len) {
            if (data[i] == 0 && data[i + 1] == 0 && data[i + 2] == 1) {
                int nalType;
                if (codec == VIDEO_HEVC) {
                    nalType = (data[i + 3] >> 1) & 0x3F;
                    if (nalType == 19 || nalType == 20 || nalType == 21
                            || nalType == 32 || nalType == 33 || nalType == 34) {
                        return true;
                    }
                } else {
                    nalType = data[i + 3] & 0x1F;
                    if (nalType == 5 || nalType == 7) {
                        return true;
                    }
                }
                i += 3;
            } else {
                i++;
            }
        }
        return false;
    }

    public boolean hasTracks() {
        return tracksReady;
    }
}
