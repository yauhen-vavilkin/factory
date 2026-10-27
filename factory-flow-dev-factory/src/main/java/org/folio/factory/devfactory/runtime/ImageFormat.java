package org.folio.factory.devfactory.runtime;

import java.util.Optional;

/**
 * The image formats Factory hands to a coding agent, recognised from the file's own bytes. Pi treats any
 * {@code @file} it does not recognise as an image as text and pastes it into the prompt, so a file is passed
 * on only when this check accepts it, and this check is never looser than Pi's.
 */
public enum ImageFormat {
    PNG("image/png", "png"),
    JPEG("image/jpeg", "jpg"),
    GIF("image/gif", "gif"),
    WEBP("image/webp", "webp");

    private static final byte[] PNG_SIGNATURE = {(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'};

    private final String mimeType;
    private final String extension;

    ImageFormat(String mimeType, String extension) {
        this.mimeType = mimeType;
        this.extension = extension;
    }

    public String mimeType() {
        return mimeType;
    }

    public String extension() {
        return extension;
    }

    public static boolean isSupportedMimeType(String mimeType) {
        for (ImageFormat format : values()) {
            if (format.mimeType.equalsIgnoreCase(mimeType)) {
                return true;
            }
        }
        return false;
    }

    public static Optional<ImageFormat> detect(byte[] bytes) {
        if (bytes == null) {
            return Optional.empty();
        }
        if (startsWith(bytes, 0, (byte) 0xff, (byte) 0xd8, (byte) 0xff)) {
            return bytes.length > 3 && bytes[3] != (byte) 0xf7 ? Optional.of(JPEG) : Optional.empty();
        }
        if (startsWith(bytes, 0, PNG_SIGNATURE)) {
            return isStillPng(bytes) ? Optional.of(PNG) : Optional.empty();
        }
        if (startsWithAscii(bytes, 0, "GIF87a") || startsWithAscii(bytes, 0, "GIF89a")) {
            return Optional.of(GIF);
        }
        if (startsWithAscii(bytes, 0, "RIFF") && startsWithAscii(bytes, 8, "WEBP")) {
            return Optional.of(WEBP);
        }
        return Optional.empty();
    }

    /** Needs the IHDR header chunk first, and no animation control chunk before the image data. */
    private static boolean isStillPng(byte[] bytes) {
        if (bytes.length < 33 || !startsWithAscii(bytes, 12, "IHDR") || readInt(bytes, 8) != 13) {
            return false;
        }
        int offset = PNG_SIGNATURE.length;
        while (offset + 8 <= bytes.length) {
            if (startsWithAscii(bytes, offset + 4, "acTL")) {
                return false;
            }
            if (startsWithAscii(bytes, offset + 4, "IDAT")) {
                return true;
            }
            long next = (long) offset + 8 + (readInt(bytes, offset) & 0xffffffffL) + 4;
            if (next <= offset || next > bytes.length) {
                return true;
            }
            offset = (int) next;
        }
        return true;
    }

    private static int readInt(byte[] bytes, int offset) {
        return (bytes[offset] & 0xff) << 24 | (bytes[offset + 1] & 0xff) << 16
                | (bytes[offset + 2] & 0xff) << 8 | (bytes[offset + 3] & 0xff);
    }

    private static boolean startsWith(byte[] bytes, int offset, byte... prefix) {
        if (bytes.length < offset + prefix.length) {
            return false;
        }
        for (int i = 0; i < prefix.length; i++) {
            if (bytes[offset + i] != prefix[i]) {
                return false;
            }
        }
        return true;
    }

    private static boolean startsWithAscii(byte[] bytes, int offset, String text) {
        if (bytes.length < offset + text.length()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            if (bytes[offset + i] != (byte) text.charAt(i)) {
                return false;
            }
        }
        return true;
    }
}
