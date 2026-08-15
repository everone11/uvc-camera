package com.everone11.uvccamera.xposed;

/**
 * 帧处理工具：提供 NV21 原地水平翻转函数
 */
public class FrameUtils {
    /**
     * 原地水平翻转 NV21 数据（Y plane + interleaved VU）
     * width 必须为 preview 宽度（偶数），height 为 preview 高度。
     */
    public static void horizontalFlipNV21(byte[] nv21, int width, int height) {
        if (nv21 == null) return;
        int frameSize = width * height;
        // flip Y
        for (int y = 0; y < height; y++) {
            int rowStart = y * width;
            int i = rowStart, j = rowStart + width - 1;
            while (i < j) {
                byte tmp = nv21[i];
                nv21[i] = nv21[j];
                nv21[j] = tmp;
                i++; j--;
            }
        }
        // flip UV (NV21: VU VU ...)
        int uvStart = frameSize;
        int uvHeight = height / 2;
        for (int y = 0; y < uvHeight; y++) {
            int rowStart = uvStart + y * width;
            int i = rowStart, j = rowStart + width - 2; // process in pairs
            while (i < j) {
                byte a = nv21[i], b = nv21[i+1];
                nv21[i] = nv21[j];
                nv21[i+1] = nv21[j+1];
                nv21[j] = a;
                nv21[j+1] = b;
                i += 2;
                j -= 2;
            }
        }
    }
}
