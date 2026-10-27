package org.folio.factory.devfactory.runtime;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

class ImageFormatTest {

    private static byte[] png(String... chunksAfterHeader) {
        var out = new java.io.ByteArrayOutputStream();
        out.writeBytes(new byte[] {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'});
        out.writeBytes(new byte[] {0, 0, 0, 13});
        out.writeBytes("IHDR".getBytes(StandardCharsets.US_ASCII));
        out.writeBytes(new byte[13]);
        out.writeBytes(new byte[4]);
        for (String type : chunksAfterHeader) {
            out.writeBytes(new byte[] {0, 0, 0, 0});
            out.writeBytes(type.getBytes(StandardCharsets.US_ASCII));
            out.writeBytes(new byte[4]);
        }
        return out.toByteArray();
    }

    @Test
    void recognisesTheSupportedFormatsFromTheirBytes() {
        assertThat(ImageFormat.detect(png("IDAT", "IEND"))).contains(ImageFormat.PNG);
        assertThat(ImageFormat.detect(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xe0, 0, 0})).contains(ImageFormat.JPEG);
        assertThat(ImageFormat.detect("GIF89a....".getBytes(StandardCharsets.US_ASCII))).contains(ImageFormat.GIF);
        assertThat(ImageFormat.detect("GIF87a....".getBytes(StandardCharsets.US_ASCII))).contains(ImageFormat.GIF);
        assertThat(ImageFormat.detect("RIFF\0\0\0\0WEBPVP8 ".getBytes(StandardCharsets.US_ASCII))).contains(ImageFormat.WEBP);
    }

    @Test
    void rejectsWhatPiWouldPasteIntoThePromptAsText() {
        assertThat(ImageFormat.detect(png("acTL", "IDAT"))).as("animated png").isEmpty();
        assertThat(ImageFormat.detect(Arrays.copyOf(png("IDAT"), 20))).as("truncated png header").isEmpty();
        assertThat(ImageFormat.detect(new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff, (byte) 0xf7})).isEmpty();
        assertThat(ImageFormat.detect("<html>not an image</html>".getBytes(StandardCharsets.UTF_8))).isEmpty();
        assertThat(ImageFormat.detect("GIFT".getBytes(StandardCharsets.US_ASCII))).isEmpty();
        assertThat(ImageFormat.detect("BM".getBytes(StandardCharsets.US_ASCII))).as("bmp is not passed on").isEmpty();
        assertThat(ImageFormat.detect(new byte[0])).isEmpty();
        assertThat(ImageFormat.detect(null)).isEmpty();
    }

    @Test
    void knowsWhichJiraMimeTypesAreSupported() {
        assertThat(ImageFormat.isSupportedMimeType("image/png")).isTrue();
        assertThat(ImageFormat.isSupportedMimeType("IMAGE/JPEG")).isTrue();
        assertThat(ImageFormat.isSupportedMimeType("image/svg+xml")).isFalse();
        assertThat(ImageFormat.isSupportedMimeType("video/mp4")).isFalse();
        assertThat(ImageFormat.isSupportedMimeType(null)).isFalse();
    }
}
