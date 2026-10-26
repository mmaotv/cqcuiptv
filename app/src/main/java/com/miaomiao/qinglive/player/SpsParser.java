package com.miaomiao.qinglive.player;

/**
 * 从 Annex-B 码流里找 SPS 并解出真实分辨率。
 * 解码器必须按源分辨率配置，否则部分芯片会直接按配置尺寸输出（4K 被压成 1080p）。
 */
final class SpsParser {

    private SpsParser() {
    }

    private static final class Bits {
        final byte[] data;
        int bitPos;

        Bits(byte[] data, int offset, int length) {
            this.data = data;
            this.bitPos = offset * 8;
            endBit = (offset + length) * 8;
        }

        final int endBit;

        int u(int n) {
            int v = 0;
            for (int i = 0; i < n; i++) {
                v = (v << 1) | bit();
            }
            return v;
        }

        int bit() {
            if (bitPos >= endBit) {
                throw new IllegalStateException("eof");
            }
            int b = (data[bitPos >> 3] >> (7 - (bitPos & 7))) & 1;
            bitPos++;
            return b;
        }

        void skip(int n) {
            for (int i = 0; i < n; i++) {
                bit();
            }
        }

        int ue() {
            int zeros = 0;
            while (bit() == 0) {
                zeros++;
                if (zeros > 32) {
                    throw new IllegalStateException("bad ue");
                }
            }
            if (zeros == 0) {
                return 0;
            }
            return (1 << zeros) - 1 + u(zeros);
        }

        int se() {
            int k = ue();
            return (k & 1) == 1 ? (k + 1) / 2 : -(k / 2);
        }
    }

    /** 在 Annex-B 数据里找到第一个 SPS 并返回 {宽, 高, 是否隔行(1/0)}，找不到返回 null。 */
    static int[] parse(byte[] data, int length, boolean hevc) {
        int i = 0;
        while (i + 4 < length) {
            if (data[i] != 0 || data[i + 1] != 0 || data[i + 2] != 1) {
                i++;
                continue;
            }
            int nalStart = i + 3;
            int nalEnd = nalStart;
            while (nalEnd + 3 < length) {
                if (data[nalEnd] == 0 && data[nalEnd + 1] == 0 && data[nalEnd + 2] == 1) {
                    break;
                }
                nalEnd++;
            }
            if (nalEnd + 3 >= length) {
                nalEnd = length;
            }
            int type = hevc ? ((data[nalStart] >> 1) & 0x3F) : (data[nalStart] & 0x1F);
            boolean isSps = hevc ? type == 33 : type == 7;
            if (isSps) {
                try {
                    int skip = hevc ? 2 : 1;
                    byte[] rbsp = toRbsp(data, nalStart + skip, nalEnd - nalStart - skip);
                    int[] size = hevc ? parseHevc(rbsp) : parseH264(rbsp);
                    if (size != null && size[0] > 0 && size[1] > 0) {
                        return size;
                    }
                } catch (Throwable t) {
                    android.util.Log.w("SPS", "parse fail: " + t);
                }
                // parseH264/parseHevc 只在找到 SPS 时返回，这里兜底继续找下一个 NAL
            }
            i = nalEnd;
        }
        return null;
    }

    /** 去掉防竞争字节（0x000003 → 0x0000），转成真正的 RBSP。 */
    private static byte[] toRbsp(byte[] src, int offset, int length) {
        byte[] out = new byte[length];
        int n = 0;
        int zeros = 0;
        for (int i = 0; i < length; i++) {
            byte b = src[offset + i];
            if (zeros >= 2 && b == 0x03) {
                zeros = 0;
                continue;
            }
            out[n++] = b;
            zeros = b == 0 ? zeros + 1 : 0;
        }
        byte[] dst = new byte[n];
        System.arraycopy(out, 0, dst, 0, n);
        return dst;
    }

    private static int[] parseH264(byte[] rbsp) {
        Bits b = new Bits(rbsp, 0, rbsp.length);
        int profile = b.u(8);
        b.skip(8);
        b.skip(8);
        b.ue();                                    // seq_parameter_set_id
        int chromaFormat = 1;
        if (profile == 100 || profile == 110 || profile == 122 || profile == 244 || profile == 44
                || profile == 83 || profile == 86 || profile == 118 || profile == 128
                || profile == 138 || profile == 139 || profile == 134 || profile == 135) {
            chromaFormat = b.ue();
            if (chromaFormat == 3) {
                b.bit();
            }
            b.ue();                                // bit_depth_luma_minus8
            b.ue();                                // bit_depth_chroma_minus8
            b.bit();
            if (b.bit() == 1) {                    // seq_scaling_matrix_present
                int lists = chromaFormat == 3 ? 12 : 8;
                for (int i = 0; i < lists; i++) {
                    if (b.bit() == 1) {
                        int size = i < 6 ? 16 : 64;
                        int last = 8;
                        int next = 8;
                        for (int j = 0; j < size; j++) {
                            if (next != 0) {
                                next = (last + b.se() + 256) % 256;
                            }
                            last = next == 0 ? last : next;
                        }
                    }
                }
            }
        }
        b.ue();                                    // log2_max_frame_num_minus4
        int pocType = b.ue();
        if (pocType == 0) {
            b.ue();
        } else if (pocType == 1) {
            b.bit();
            b.se();
            b.se();
            int n = b.ue();
            for (int i = 0; i < n; i++) {
                b.se();
            }
        }
        b.ue();                                    // max_num_ref_frames
        b.bit();                                   // gaps_in_frame_num_value_allowed
        int widthMbs = b.ue() + 1;
        int heightMapUnits = b.ue() + 1;
        boolean frameMbsOnly = b.bit() == 1;
        if (!frameMbsOnly) {
            b.bit();
        }
        b.bit();                                   // direct_8x8_inference
        int cropLeft = 0;
        int cropRight = 0;
        int cropTop = 0;
        int cropBottom = 0;
        if (b.bit() == 1) {
            cropLeft = b.ue();
            cropRight = b.ue();
            cropTop = b.ue();
            cropBottom = b.ue();
        }
        int subWidthC = chromaFormat == 3 ? 1 : 2;
        int subHeightC = chromaFormat == 1 ? 2 : 1;
        int width = widthMbs * 16 - (cropLeft + cropRight) * subWidthC;
        int height = (2 - (frameMbsOnly ? 1 : 0)) * heightMapUnits * 16
                - (cropTop + cropBottom) * subHeightC * (2 - (frameMbsOnly ? 1 : 0));
        // 第三个值：是否隔行。frameMbsOnly=0 说明是 1080i 这类隔行（或 MBAFF），
        // 这种源直接送 Surface 会出梳状/发虚，电视直播里常见于 CCTV1 等新闻台。
        return new int[]{width, height, frameMbsOnly ? 0 : 1};
    }

    private static int[] parseHevc(byte[] rbsp) {
        Bits b = new Bits(rbsp, 0, rbsp.length);
        b.skip(4);                                 // sps_video_parameter_set_id
        int maxSubLayersMinus1 = b.u(3);
        b.skip(1);                                 // temporal_id_nesting
        // profile_tier_level
        b.skip(2 + 1 + 5);
        b.skip(32);                                // compatibility flags
        b.skip(4);                                 // source flags
        b.skip(44);                                // constraint_indicator_flags 剩下的 44 位
        b.skip(8);                                 // level_idc
        boolean[] profilePresent = new boolean[maxSubLayersMinus1];
        boolean[] levelPresent = new boolean[maxSubLayersMinus1];
        for (int i = 0; i < maxSubLayersMinus1; i++) {
            profilePresent[i] = b.bit() == 1;
            levelPresent[i] = b.bit() == 1;
        }
        if (maxSubLayersMinus1 > 0) {
            for (int i = maxSubLayersMinus1; i < 8; i++) {
                b.skip(2);
            }
        }
        for (int i = 0; i < maxSubLayersMinus1; i++) {
            if (profilePresent[i]) {
                b.skip(2 + 1 + 5);
                b.skip(32);
                b.skip(4);
                b.skip(44);
            }
            if (levelPresent[i]) {
                b.skip(8);
            }
        }
        b.ue();                                    // sps_seq_parameter_set_id
        int chromaFormat = b.ue();
        if (chromaFormat == 3) {
            b.bit();
        }
        int width = b.ue();
        int height = b.ue();
        if (b.bit() == 1) {                        // conformance_window_flag
            int left = b.ue();
            int right = b.ue();
            int top = b.ue();
            int bottom = b.ue();
            int subWidthC = chromaFormat == 1 || chromaFormat == 2 ? 2 : 1;
            int subHeightC = chromaFormat == 1 ? 2 : 1;
            width -= (left + right) * subWidthC;
            height -= (top + bottom) * subHeightC;
        }
        return new int[]{width, height, 0};
    }
}
