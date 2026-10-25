package io.jburrow.server.core.tunnel.adapter.netty.control;

/** Makes text a client sent safe to put in a log line. */
final class PeerText {

    static final int MAX_LOGGED_LENGTH = 200;

    private PeerText() {}

    /**
     * At most {@link #MAX_LOGGED_LENGTH} characters, with every control character replaced by {@code ?}, so a client
     * can't forge log lines or flood the log.
     */
    static String sanitize(String text) {
        int length = Math.min(text.length(), MAX_LOGGED_LENGTH);
        StringBuilder safe = new StringBuilder(length + 3);
        for (int i = 0; i < length; i++) {
            char c = text.charAt(i);
            safe.append(Character.isISOControl(c) || c == '"' || c == '\\' ? '?' : c);
        }
        if (text.length() > MAX_LOGGED_LENGTH) {
            safe.append("...");
        }
        return safe.toString();
    }
}
