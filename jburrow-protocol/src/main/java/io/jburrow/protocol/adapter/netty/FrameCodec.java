package io.jburrow.protocol.adapter.netty;

import io.jburrow.protocol.adapter.jackson.ControlJson;
import io.jburrow.protocol.control.ControlMessage;
import io.jburrow.protocol.control.ErrorBody;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ErrorScope;
import io.jburrow.protocol.wire.FrameFlags;
import io.jburrow.protocol.wire.FrameLimits;
import io.jburrow.protocol.wire.FrameType;
import io.jburrow.protocol.wire.ProtocolException;
import io.jburrow.protocol.wire.StreamIds;
import io.netty.buffer.ByteBuf;
import io.netty.buffer.ByteBufAllocator;
import io.netty.buffer.ByteBufInputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Objects;

/**
 * Turns one binary WebSocket message into a {@link Frame} and back. The only code that reads untrusted bytes as
 * frames.
 *
 * <p>Decoding applies receiver rules 1 to 8 of {@code docs/protocol.md} §12, in that order, and stops at the first
 * failure with the {@link ProtocolException} the specification names. Rule 9, whether the frame fits the session's
 * state, is the session's. The direction rules of §6 are checked with rule 4: a peer that sends a type its
 * role never sends fails here, before any session sees it.
 *
 * <p>Stateless and thread-safe. Each side holds one instance for its {@link Role}.
 */
public final class FrameCodec {

    /** Which side this codec works for: it decodes what the other side sends and encodes what this side sends. */
    public enum Role {
        CLIENT,
        SERVER
    }

    private static final int STREAM_ID_OFFSET = 0;
    private static final int TYPE_OFFSET = 4;
    private static final int FLAGS_OFFSET = 5;
    private static final int LENGTH_OFFSET = 6;

    private final Role localRole;
    private final ControlJson json;

    public FrameCodec(Role localRole, ControlJson json) {
        this.localRole = Objects.requireNonNull(localRole, "localRole");
        this.json = Objects.requireNonNull(json, "json");
    }

    public Role localRole() {
        return localRole;
    }

    /**
     * Decodes one whole WebSocket message. The message is not modified and not released; the caller still owns it. A
     * returned {@link Frame.Data} holds a retained slice of it, which the receiver of the frame releases.
     *
     * @throws ProtocolException if the message breaks a receiver rule
     */
    public Frame decode(ByteBuf message) {
        Objects.requireNonNull(message, "message");
        int base = message.readerIndex();
        int messageLength = message.readableBytes();

        requireHeader(messageLength);
        requireReservedBitClear(message.getInt(base + STREAM_ID_OFFSET));
        int length = requireLengthMatchesMessage(message.getUnsignedInt(base + LENGTH_OFFSET), messageLength);
        FrameType type = requireAssignedType(message.getUnsignedByte(base + TYPE_OFFSET));
        requireSentByPeer(type);
        int streamId = requireStreamForType(type, message.getInt(base + STREAM_ID_OFFSET));
        int flags = message.getUnsignedByte(base + FLAGS_OFFSET);
        requireLengthForType(type, flags, length);
        requireDefinedFlags(type, flags);
        return parsePayload(type, streamId, flags, message, base + FrameLimits.HEADER_LENGTH, length);
    }

    /**
     * Encodes {@code frame} as one WebSocket message. Takes over the frame's buffer: the payload of a
     * {@link Frame.Data} becomes a component of the returned buffer, without a copy, or is released if encoding
     * fails.
     *
     * @throws IllegalArgumentException if this side never sends the frame, or a {@link Frame.Data} payload is empty or
     *     over 65,536 bytes: a bug in the caller, not something a peer did
     */
    public ByteBuf encode(Frame frame, ByteBufAllocator alloc) {
        Objects.requireNonNull(frame, "frame");
        try {
            Objects.requireNonNull(alloc, "alloc");
            requireSentByUs(frame);
            return switch (frame) {
                case Frame.Data data -> encodeData(data, alloc);
                case Frame.Hello hello -> encodeControl(hello, hello.message(), alloc);
                case Frame.ServerHello hello -> encodeControl(hello, hello.message(), alloc);
                case Frame.Register register -> encodeControl(register, register.message(), alloc);
                case Frame.Registered registered -> encodeControl(registered, registered.message(), alloc);
                case Frame.Open open -> encodeControl(open, open.message(), alloc);
                case Frame.Error error -> encodeControl(error, error.body(), alloc);
                case Frame.WindowUpdate update ->
                    header(alloc, update, 0, FrameLimits.WINDOW_UPDATE_PAYLOAD_LENGTH)
                            .writeInt(update.increment());
                case Frame.Close close -> encodeClose(close, alloc);
                case Frame.Ping ping ->
                    header(alloc, ping, 0, FrameLimits.PING_PAYLOAD_LENGTH).writeLong(ping.opaque());
                case Frame.Pong pong ->
                    header(alloc, pong, 0, FrameLimits.PING_PAYLOAD_LENGTH).writeLong(pong.opaque());
                case Frame.Unsupported unsupported ->
                    throw new IllegalArgumentException(unsupported.type() + " is not sent in this protocol draft");
            };
        } catch (RuntimeException | Error e) {
            frame.release();
            throw e;
        }
    }

    // Rule 1. Whether the message is binary is the WebSocket bridge's check.
    private static void requireHeader(int messageLength) {
        if (messageLength < FrameLimits.HEADER_LENGTH) {
            throw frameSizeError("message is " + messageLength + " bytes, shorter than the frame header");
        }
    }

    // Rule 2.
    private static void requireReservedBitClear(int rawStreamId) {
        if (StreamIds.isReservedBitSet(rawStreamId)) {
            throw protocolError("reserved bit of the stream id is set");
        }
    }

    // Rule 3. A length that fits the message also fits an int, since a message can't exceed 2^31 - 1 bytes.
    private static int requireLengthMatchesMessage(long length, int messageLength) {
        if (length != messageLength - FrameLimits.HEADER_LENGTH) {
            throw frameSizeError("length field " + length + " doesn't match the "
                    + (messageLength - FrameLimits.HEADER_LENGTH) + "-byte payload");
        }
        return (int) length;
    }

    // Rule 4.
    private static FrameType requireAssignedType(int code) {
        return FrameType.fromCode(code)
                .orElseThrow(() -> protocolError("frame type 0x" + Integer.toHexString(code) + " is unassigned"));
    }

    // Rule 4, the direction half: a type the peer's role never sends is as foreign as an unassigned one.
    private void requireSentByPeer(FrameType type) {
        if (!sends(peerRole(), type)) {
            throw protocolError(type + " is never sent by a " + roleName(peerRole()));
        }
    }

    // Rule 5.
    private static int requireStreamForType(FrameType type, int streamId) {
        if (type.onControlStream() != (streamId == StreamIds.CONTROL)) {
            throw protocolError(type + (type.onControlStream() ? " must be on stream 0" : " must not be on stream 0"));
        }
        return streamId;
    }

    // Rule 6.
    private static void requireLengthForType(FrameType type, int flags, int length) {
        if (!FrameLimits.lengthAllowed(type, flags, length)) {
            throw frameSizeError(type + " payload of " + length + " bytes is not allowed");
        }
    }

    // Rule 7.
    private static void requireDefinedFlags(FrameType type, int flags) {
        if ((flags & ~FrameFlags.definedFor(type)) != 0) {
            throw protocolError(type + " has undefined flag bits set");
        }
    }

    // Rule 8.
    private Frame parsePayload(FrameType type, int streamId, int flags, ByteBuf message, int offset, int length) {
        return switch (type) {
            case DATA -> new Frame.Data(streamId, message.retainedSlice(offset, length));
            case HELLO ->
                localRole == Role.SERVER
                        ? new Frame.Hello(readJson(message, offset, length, io.jburrow.protocol.control.Hello.class))
                        : new Frame.ServerHello(
                                readJson(message, offset, length, io.jburrow.protocol.control.ServerHello.class));
            case REGISTER ->
                localRole == Role.SERVER
                        ? new Frame.Register(
                                readJson(message, offset, length, io.jburrow.protocol.control.Register.class))
                        : new Frame.Registered(
                                readJson(message, offset, length, io.jburrow.protocol.control.Registered.class));
            case OPEN ->
                new Frame.Open(streamId, readJson(message, offset, length, io.jburrow.protocol.control.Open.class));
            case ERROR -> new Frame.Error(readJson(message, offset, length, ErrorBody.class));
            case WINDOW_UPDATE -> new Frame.WindowUpdate(streamId, readIncrement(message.getUnsignedInt(offset)));
            case CLOSE ->
                (flags & FrameFlags.RST) == 0
                        ? Frame.Close.half(streamId)
                        : Frame.Close.reset(streamId, readResetCode(message, offset, length));
            case PING -> new Frame.Ping(message.getLong(offset));
            case PONG -> new Frame.Pong(message.getLong(offset));
            case HEADERS, GOAWAY -> new Frame.Unsupported(streamId, type);
        };
    }

    /** Parses straight from the message's memory; a plain slice shares it without touching the reference count. */
    private <T extends ControlMessage> T readJson(ByteBuf message, int offset, int length, Class<T> type) {
        try (ByteBufInputStream in = new ByteBufInputStream(message.slice(offset, length))) {
            return json.read(in, type);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static int readIncrement(long increment) {
        if (increment < 1 || increment > StreamIds.MAX) {
            throw protocolError("window increment must be 1 to 2^31 - 1");
        }
        return (int) increment;
    }

    /**
     * Checks every byte before building a string from them, so a code is only ever made of {@code [A-Z0-9_]}. A
     * code the registry doesn't know, or one that isn't a stream error, is a broken rule: a peer may not invent codes.
     */
    private static ErrorCode readResetCode(ByteBuf message, int offset, int length) {
        for (int i = offset; i < offset + length; i++) {
            if (!isErrorCodeByte(message.getByte(i))) {
                throw protocolError("reset code must be [A-Z0-9_] only");
            }
        }
        ErrorCode code = ErrorCode.fromCode(message.toString(offset, length, StandardCharsets.US_ASCII))
                .orElseThrow(() -> protocolError("reset code is not in the error code registry"));
        if (!code.scopes().contains(ErrorScope.STREAM)) {
            throw protocolError("reset code " + code + " is not a stream error");
        }
        return code;
    }

    private static boolean isErrorCodeByte(byte b) {
        return (b >= 'A' && b <= 'Z') || (b >= '0' && b <= '9') || b == '_';
    }

    private void requireSentByUs(Frame frame) {
        if (!sends(localRole, frame.type()) || !matchesDirection(frame)) {
            throw new IllegalArgumentException("A " + roleName(localRole) + " never sends "
                    + frame.getClass().getSimpleName());
        }
    }

    /** {@code HELLO} and {@code REGISTER} are sent by both sides, each with its own record. */
    private boolean matchesDirection(Frame frame) {
        return switch (frame) {
            case Frame.Hello ignored -> localRole == Role.CLIENT;
            case Frame.Register ignored -> localRole == Role.CLIENT;
            case Frame.ServerHello ignored -> localRole == Role.SERVER;
            case Frame.Registered ignored -> localRole == Role.SERVER;
            default -> true;
        };
    }

    private ByteBuf encodeData(Frame.Data data, ByteBufAllocator alloc) {
        int length = data.payload().readableBytes();
        if (length < 1 || length > FrameLimits.MAX_DATA_PAYLOAD) {
            throw new IllegalArgumentException(
                    "DATA payload must be 1 to " + FrameLimits.MAX_DATA_PAYLOAD + " bytes, not " + length);
        }
        ByteBuf header = alloc.directBuffer(FrameLimits.HEADER_LENGTH, FrameLimits.HEADER_LENGTH);
        try {
            writeHeader(header, data, 0, length);
            return alloc.compositeDirectBuffer(2).addComponents(true, header, data.payload());
        } catch (RuntimeException | Error e) {
            header.release();
            throw e;
        }
    }

    private ByteBuf encodeControl(Frame frame, ControlMessage message, ByteBufAllocator alloc) {
        byte[] payload = json.write(message);
        return header(alloc, frame, 0, payload.length).writeBytes(payload);
    }

    private static ByteBuf encodeClose(Frame.Close close, ByteBufAllocator alloc) {
        if (close.reset().isEmpty()) {
            return header(alloc, close, 0, 0);
        }
        byte[] code = close.reset().get().code().getBytes(StandardCharsets.US_ASCII);
        return header(alloc, close, FrameFlags.RST, code.length).writeBytes(code);
    }

    /** A buffer sized for the whole frame, with the header written. */
    private static ByteBuf header(ByteBufAllocator alloc, Frame frame, int flags, int payloadLength) {
        int size = FrameLimits.HEADER_LENGTH + payloadLength;
        return writeHeader(alloc.buffer(size, size), frame, flags, payloadLength);
    }

    private static ByteBuf writeHeader(ByteBuf buf, Frame frame, int flags, int payloadLength) {
        return buf.writeInt(frame.streamId())
                .writeByte(frame.type().code())
                .writeByte(flags)
                .writeInt(payloadLength);
    }

    private Role peerRole() {
        return localRole == Role.SERVER ? Role.CLIENT : Role.SERVER;
    }

    /** The "Sent by" column of {@code docs/protocol.md} §6. */
    private static boolean sends(Role role, FrameType type) {
        return switch (type) {
            case OPEN, GOAWAY -> role == Role.SERVER;
            case HEADERS -> role == Role.CLIENT;
            default -> true;
        };
    }

    private static String roleName(Role role) {
        return role == Role.SERVER ? "server" : "client";
    }

    private static ProtocolException protocolError(String message) {
        return ProtocolException.session(ErrorCode.PROTOCOL_ERROR, message);
    }

    private static ProtocolException frameSizeError(String message) {
        return ProtocolException.session(ErrorCode.FRAME_SIZE_ERROR, message);
    }
}
