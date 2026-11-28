package cc.sighs.informationera.net;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 磁盘状态文本的网络载荷编码。
 *
 * <p>{@link DiskCodec} 的文本随文件数线性增长；超过约 200 个文件后会越过 Minecraft
 * 组件字符串的 32767 字符上限。这里只在载荷较大时做 gzip + Base64URL，保持包结构不变，
 * 解码端用前缀区分压缩与旧格式。
 *
 * <p>所有编码/解码都有显式上限：明文长度必须能安全放进组件字符串，压缩输出不能超过
 * 网络字符串预算，解压输出也不能无限增长。损坏、截断或超限的载荷返回 {@code null}，
 * 让调用方跳过本次快照而不是用空字符串覆盖上一份有效状态。
 */
final class DiskPayload {

    private static final String COMPRESSED_PREFIX = "gz1:";
    private static final int COMPRESS_THRESHOLD = 24_000;
    /** 单个网络组件的字符串上限为 32767；留出前缀和包内其它字段的余量。 */
    static final int MAX_ENCODED_CHARS = 30_000;
    /** 解压后允许的最大字节数，防止压缩炸弹。 */
    static final int MAX_DECOMPRESSED_BYTES = 4 * 1024 * 1024;
    /** Base64(30k) 的理论压缩字节上限，提前拒绝异常输入。 */
    private static final int MAX_COMPRESSED_BYTES = (MAX_ENCODED_CHARS * 3) / 4;

    private DiskPayload() {
    }

    /**
     * 编码为可放进网络字符串的载荷；明文足够小则保持旧格式。
     *
     * @return null 表示该快照超过允许传输的尺寸或压缩失败，调用方不得发送它
     */
    static String encode(String raw) {
        if (raw == null) {
            return "";
        }
        byte[] utf8 = raw.getBytes(StandardCharsets.UTF_8);
        if (utf8.length > MAX_DECOMPRESSED_BYTES) {
            return null;
        }
        if (raw.length() <= COMPRESS_THRESHOLD && raw.length() <= MAX_ENCODED_CHARS) {
            return raw;
        }
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
                gzip.write(utf8);
            }
            String encoded = Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(bytes.toByteArray());
            if (COMPRESSED_PREFIX.length() + encoded.length() > MAX_ENCODED_CHARS) {
                return null;
            }
            return COMPRESSED_PREFIX + encoded;
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }

    /**
     * 解码网络载荷。空字符串表示一次设备状态查询（没有磁盘快照）；损坏、截断、
     * 超限或无法识别的快照返回 {@code null}，调用方必须保留上一份有效镜像。
     */
    static String decode(String payload) {
        if (payload == null || payload.isEmpty()) {
            return "";
        }
        if (payload.length() > MAX_ENCODED_CHARS) {
            return null;
        }
        if (!payload.startsWith(COMPRESSED_PREFIX)) {
            return looksLikeSnapshot(payload) ? payload : null;
        }
        if (payload.length() <= COMPRESSED_PREFIX.length()) {
            return null;
        }
        try {
            byte[] compressed = Base64.getUrlDecoder().decode(payload.substring(COMPRESSED_PREFIX.length()));
            if (compressed.length > MAX_COMPRESSED_BYTES) {
                return null;
            }
            ByteArrayOutputStream bytes = new ByteArrayOutputStream(Math.min(8192, compressed.length));
            int total = 0;
            try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(compressed))) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = gzip.read(buffer)) >= 0) {
                    total += read;
                    if (total > MAX_DECOMPRESSED_BYTES) {
                        return null;
                    }
                    bytes.write(buffer, 0, read);
                }
            }
            String raw = bytes.toString(StandardCharsets.UTF_8);
            return looksLikeSnapshot(raw) ? raw : null;
        } catch (IOException | RuntimeException failure) {
            return null;
        }
    }

    /** 快照至少要有 DiskCodec 的磁盘头和 Z 行；避免把任意文本当成空磁盘。 */
    private static boolean looksLikeSnapshot(String raw) {
        return raw != null
                && raw.startsWith("D\t")
                && raw.indexOf("\nZ\t") >= 0;
    }
}
