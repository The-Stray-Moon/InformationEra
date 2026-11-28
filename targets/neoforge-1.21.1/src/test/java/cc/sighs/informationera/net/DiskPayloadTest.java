package cc.sighs.informationera.net;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Random;
import java.util.zip.GZIPOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DiskPayloadTest {

    @Test
    void plainTextRoundTrip() {
        String raw = snapshot(2);
        assertEquals(raw, DiskPayload.encode(raw));
        assertEquals(raw, DiskPayload.decode(raw));
    }

    @Test
    void compressedRoundTrip() {
        String raw = snapshot(2_000);
        String encoded = DiskPayload.encode(raw);
        assertTrue(encoded != null && encoded.startsWith("gz1:"));
        assertTrue(encoded.length() <= DiskPayload.MAX_ENCODED_CHARS);
        assertEquals(raw, DiskPayload.decode(encoded));
    }

    @Test
    void emptyPayloadIsAStatusQuery() {
        assertEquals("", DiskPayload.decode(null));
        assertEquals("", DiskPayload.decode(""));
        assertEquals("", DiskPayload.encode(null));
    }

    @Test
    void corruptPlainTextIsRejected() {
        assertNull(DiskPayload.decode("not a snapshot"));
        assertNull(DiskPayload.decode("D\tmissing-z-line"));
        assertNull(DiskPayload.decode("Z\t30\t0\n"));
    }

    @Test
    void corruptCompressedTextIsRejected() {
        assertNull(DiskPayload.decode("gz1:"));
        assertNull(DiskPayload.decode("gz1:!!!!"));
        assertNull(DiskPayload.decode("gz1:" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString("not gzip".getBytes(StandardCharsets.UTF_8))));

        String encoded = DiskPayload.encode(snapshot(2_000));
        assertTrue(encoded != null);
        assertNull(DiskPayload.decode(encoded.substring(0, encoded.length() / 2)));
    }

    @Test
    void oversizedEncodedTextIsRejectedBeforeParsing() {
        String raw = "D\tname\nZ\t30\t0\n" + "A".repeat(DiskPayload.MAX_ENCODED_CHARS);
        assertNull(DiskPayload.decode(raw));
        assertNull(DiskPayload.decode("gz1:" + "A".repeat(DiskPayload.MAX_ENCODED_CHARS)));
    }

    @Test
    void exactEncodedBoundaryIsAccepted() {
        String prefix = "D\tname\nZ\t30\t0\n";
        String raw = prefix + "F".repeat(DiskPayload.MAX_ENCODED_CHARS - prefix.length());
        assertEquals(DiskPayload.MAX_ENCODED_CHARS, raw.length());
        assertEquals(raw, DiskPayload.decode(raw));
    }

    @Test
    void decompressionBombIsRejected() throws IOException {
        String bomb = "D\tname\nZ\t30\t0\n" + "A".repeat(DiskPayload.MAX_DECOMPRESSED_BYTES);
        String encoded = "gz1:" + Base64.getUrlEncoder().withoutPadding()
                .encodeToString(gzip(bomb));
        assertNull(DiskPayload.decode(encoded));
    }

    @Test
    void oversizedPlainSnapshotIsRejected() {
        String raw = "D\tname\nZ\t30\t0\n" + "A".repeat(DiskPayload.MAX_DECOMPRESSED_BYTES);
        assertNull(DiskPayload.encode(raw));
    }

    @Test
    void compressedOutputOverBudgetIsRejected() {
        StringBuilder sb = new StringBuilder("D\tname\nZ\t30\t0\n");
        Random random = new Random(42L);
        for (int i = 0; i < 300_000; i++) {
            sb.append((char) ('a' + random.nextInt(26)));
        }
        assertNull(DiskPayload.encode(sb.toString()));
    }

    @Test
    void compressedPayloadOverBudgetIsRejectedWithoutDecompression() {
        assertNull(DiskPayload.decode("gz1:" + "A".repeat(DiskPayload.MAX_ENCODED_CHARS)));
    }

    private static String snapshot(int lines) {
        StringBuilder sb = new StringBuilder("D\tTest Disk\nZ\t30\t0\n");
        for (int i = 0; i < lines; i++) {
            sb.append("F\tf").append(i).append("\tfolder\troot\t1\t1\n");
        }
        return sb.toString();
    }

    private static byte[] gzip(String text) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bytes)) {
            gzip.write(text.getBytes(StandardCharsets.UTF_8));
        }
        return bytes.toByteArray();
    }
}
