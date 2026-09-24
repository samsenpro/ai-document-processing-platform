package com.documind.document;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * Formatos aceptados. Se detectan por la firma binaria del archivo (magic bytes), nunca por el
 * Content-Type que declara el cliente.
 */
public enum DocumentFormat {
    PDF("application/pdf"),
    PNG("image/png"),
    JPEG("image/jpeg"),
    TIFF("image/tiff"),
    WEBP("image/webp"),
    DOCX("application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
    TEXT("text/plain");

    private final String mimeType;

    DocumentFormat(String mimeType) {
        this.mimeType = mimeType;
    }

    public String mimeType() {
        return mimeType;
    }

    /**
     * Detecta el formato a partir de los primeros bytes. Para ZIP (DOCX) y texto también se exige la
     * extensión, porque sus firmas son demasiado genéricas (cualquier ZIP, cualquier texto).
     */
    public static Optional<DocumentFormat> detect(byte[] head, String filename) {
        String name = filename == null ? "" : filename.toLowerCase(Locale.ROOT);
        if (startsWith(head, "%PDF-".getBytes(StandardCharsets.US_ASCII))) {
            return Optional.of(PDF);
        }
        if (startsWith(head, new byte[]{(byte) 0x89, 'P', 'N', 'G', '\r', '\n', 0x1a, '\n'})) {
            return Optional.of(PNG);
        }
        if (startsWith(head, new byte[]{(byte) 0xFF, (byte) 0xD8, (byte) 0xFF})) {
            return Optional.of(JPEG);
        }
        if (startsWith(head, new byte[]{'I', 'I', 42, 0}) || startsWith(head, new byte[]{'M', 'M', 0, 42})) {
            return Optional.of(TIFF);
        }
        if (head.length >= 12 && startsWith(head, "RIFF".getBytes(StandardCharsets.US_ASCII))
                && Arrays.equals(Arrays.copyOfRange(head, 8, 12), "WEBP".getBytes(StandardCharsets.US_ASCII))) {
            return Optional.of(WEBP);
        }
        if (startsWith(head, new byte[]{'P', 'K', 3, 4}) && name.endsWith(".docx")) {
            return Optional.of(DOCX);
        }
        if (name.endsWith(".txt") && isText(head)) {
            return Optional.of(TEXT);
        }
        return Optional.empty();
    }

    private static boolean startsWith(byte[] data, byte[] prefix) {
        return data.length >= prefix.length && Arrays.equals(Arrays.copyOf(data, prefix.length), prefix);
    }

    private static boolean isText(byte[] head) {
        for (byte b : head) {
            if (b == 0) {
                return false;
            }
        }
        try {
            // Los últimos bytes pueden cortar un carácter multibyte: se ignoran en la validación
            int length = Math.max(0, head.length - 3);
            StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(head, 0, length));
            return true;
        } catch (CharacterCodingException ex) {
            // Texto con codificación heredada (Latin-1/Windows-1252): válido si no hay caracteres de control
            for (byte b : head) {
                int c = b & 0xFF;
                if (c < 0x20 && c != '\n' && c != '\r' && c != '\t') {
                    return false;
                }
            }
            return true;
        }
    }
}
