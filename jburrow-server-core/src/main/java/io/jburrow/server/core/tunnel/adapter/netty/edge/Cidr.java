package io.jburrow.server.core.tunnel.adapter.netty.edge;

import io.netty.util.NetUtil;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.Objects;
import java.util.Optional;

/**
 * An IPv4 or IPv6 network written {@code address/prefix}, such as {@code 10.42.0.0/16}. Parsed from its text alone: a
 * host name is never resolved, here or anywhere in the edge.
 */
public final class Cidr {

    private static final int BITS_PER_BYTE = 8;

    private final byte[] network;
    private final int prefixLength;

    private Cidr(byte[] network, int prefixLength) {
        this.network = network;
        this.prefixLength = prefixLength;
    }

    /**
     * Parses {@code <address>/<prefix>}. The address must be an IP literal and have no bits set beyond the prefix, so
     * {@code 10.42.0.7/16} is refused rather than read as {@code 10.42.0.0/16}.
     *
     * @throws IllegalArgumentException if the text is not such a network
     */
    public static Cidr parse(String text) {
        Objects.requireNonNull(text, "text");
        int slash = text.indexOf('/');
        if (slash < 0) {
            throw new IllegalArgumentException("A CIDR must be written <address>/<prefix>: " + text);
        }
        byte[] network = NetUtil.createByteArrayFromIpAddressString(text.substring(0, slash));
        if (network == null) {
            throw new IllegalArgumentException("A CIDR must start with an IP address literal: " + text);
        }
        int maxPrefix = network.length * BITS_PER_BYTE;
        int prefixLength = parsePrefix(text.substring(slash + 1), maxPrefix, text);
        for (int bit = prefixLength; bit < maxPrefix; bit++) {
            if ((network[bit / BITS_PER_BYTE] & (0x80 >>> (bit % BITS_PER_BYTE))) != 0) {
                throw new IllegalArgumentException("A CIDR must have no address bits set beyond its prefix: " + text);
            }
        }
        return new Cidr(network, prefixLength);
    }

    /**
     * An IP address literal as an address, or empty if {@code text} is anything else. Never resolves a name.
     */
    public static Optional<InetAddress> parseAddress(String text) {
        byte[] bytes = text == null ? null : NetUtil.createByteArrayFromIpAddressString(text);
        if (bytes == null) {
            return Optional.empty();
        }
        try {
            return Optional.of(InetAddress.getByAddress(bytes));
        } catch (UnknownHostException impossible) {
            throw new IllegalStateException("A 4- or 16-byte address was refused", impossible);
        }
    }

    public boolean contains(InetAddress address) {
        byte[] candidate = Objects.requireNonNull(address, "address").getAddress();
        if (candidate.length != network.length) {
            return false;
        }
        int fullBytes = prefixLength / BITS_PER_BYTE;
        for (int i = 0; i < fullBytes; i++) {
            if (candidate[i] != network[i]) {
                return false;
            }
        }
        int remainingBits = prefixLength % BITS_PER_BYTE;
        if (remainingBits == 0) {
            return true;
        }
        int mask = (0xFF << (BITS_PER_BYTE - remainingBits)) & 0xFF;
        return (candidate[fullBytes] & mask) == (network[fullBytes] & mask);
    }

    public int prefixLength() {
        return prefixLength;
    }

    private static int parsePrefix(String digits, int maxPrefix, String text) {
        if (digits.isEmpty() || digits.length() > 3 || !digits.chars().allMatch(c -> c >= '0' && c <= '9')) {
            throw new IllegalArgumentException("A CIDR prefix must be a number: " + text);
        }
        int prefix = Integer.parseInt(digits);
        if (prefix > maxPrefix) {
            throw new IllegalArgumentException("A CIDR prefix must be at most " + maxPrefix + ": " + text);
        }
        return prefix;
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof Cidr that && prefixLength == that.prefixLength && Arrays.equals(network, that.network);
    }

    @Override
    public int hashCode() {
        return 31 * Arrays.hashCode(network) + prefixLength;
    }

    @Override
    public String toString() {
        return NetUtil.bytesToIpAddress(network) + "/" + prefixLength;
    }
}
