package io.jburrow.protocol.testsupport;

import java.io.ByteArrayOutputStream;
import java.util.HexFormat;

/**
 * Parses the hex notation of the conformance vectors: whitespace-separated tokens of hex digit pairs, where
 * {@code <hex>*<count>} repeats those bytes {@code count} times. See {@code vectors/README.md}.
 */
public final class Hex {

    private Hex() {}

    public static byte[] decode(String hex) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        String trimmed = hex.strip();
        if (trimmed.isEmpty()) {
            return new byte[0];
        }
        for (String token : trimmed.split("\\s+")) {
            int star = token.indexOf('*');
            byte[] bytes = HexFormat.of().parseHex(star < 0 ? token : token.substring(0, star));
            int count = star < 0 ? 1 : Integer.parseInt(token.substring(star + 1));
            for (int i = 0; i < count; i++) {
                out.writeBytes(bytes);
            }
        }
        return out.toByteArray();
    }
}
