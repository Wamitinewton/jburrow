package io.jburrow.client.tunnel.adapter.netty;

/**
 * Makes text from the server safe to hand to a person. A server's message reaches a terminal through the CLI, so
 * control, formatting and line-separator characters, which could move the cursor or reorder what the user reads, are
 * replaced.
 */
final class PeerText {

    static final int MAX_LENGTH = 512;

    private PeerText() {}

    static String sanitize(String text) {
        StringBuilder safe = new StringBuilder(Math.min(text.length(), MAX_LENGTH));
        text.codePoints().limit(MAX_LENGTH).forEach(c -> safe.appendCodePoint(isUnsafe(c) ? '?' : c));
        return safe.toString();
    }

    private static boolean isUnsafe(int codePoint) {
        int type = Character.getType(codePoint);
        return Character.isISOControl(codePoint)
                || type == Character.FORMAT
                || type == Character.LINE_SEPARATOR
                || type == Character.PARAGRAPH_SEPARATOR;
    }
}
