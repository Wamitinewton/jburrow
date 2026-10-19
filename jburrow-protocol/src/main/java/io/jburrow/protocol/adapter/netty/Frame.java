package io.jburrow.protocol.adapter.netty;

import io.jburrow.protocol.control.ErrorBody;
import io.jburrow.protocol.wire.ErrorCode;
import io.jburrow.protocol.wire.ErrorScope;
import io.jburrow.protocol.wire.FrameType;
import io.jburrow.protocol.wire.StreamIds;
import io.netty.buffer.ByteBuf;
import io.netty.util.ReferenceCounted;
import java.util.Objects;
import java.util.Optional;

/**
 * One protocol frame, as {@link FrameCodec} decodes and encodes it.
 *
 * <p>Only {@link Data} holds a buffer, and Netty's rule applies to it: whoever holds the frame last releases it. Every
 * other frame is {@link NoBuffer}, whose reference counting does nothing, so a handler can release any frame it drops
 * without asking which kind it is.
 *
 * <p>{@code HELLO} and {@code REGISTER} carry a different payload in each direction, so each has two records here.
 */
public sealed interface Frame extends ReferenceCounted permits Frame.Data, Frame.NoBuffer {

    int streamId();

    FrameType type();

    @Override
    Frame retain();

    @Override
    Frame retain(int increment);

    @Override
    Frame touch();

    @Override
    Frame touch(Object hint);

    /** A frame without a buffer. Its reference count is always 1 and releasing it never frees anything. */
    sealed interface NoBuffer extends Frame
            permits Hello,
                    ServerHello,
                    Register,
                    Registered,
                    Open,
                    WindowUpdate,
                    Close,
                    Ping,
                    Pong,
                    Error,
                    Unsupported {

        @Override
        default int refCnt() {
            return 1;
        }

        @Override
        default Frame retain() {
            return this;
        }

        @Override
        default Frame retain(int increment) {
            return this;
        }

        @Override
        default Frame touch() {
            return this;
        }

        @Override
        default Frame touch(Object hint) {
            return this;
        }

        @Override
        default boolean release() {
            return false;
        }

        @Override
        default boolean release(int decrement) {
            return false;
        }
    }

    /** {@code HELLO}, client to server. */
    record Hello(io.jburrow.protocol.control.Hello message) implements NoBuffer {

        public Hello {
            Objects.requireNonNull(message, "message");
        }

        @Override
        public int streamId() {
            return StreamIds.CONTROL;
        }

        @Override
        public FrameType type() {
            return FrameType.HELLO;
        }
    }

    /** {@code HELLO}, server to client. */
    record ServerHello(io.jburrow.protocol.control.ServerHello message) implements NoBuffer {

        public ServerHello {
            Objects.requireNonNull(message, "message");
        }

        @Override
        public int streamId() {
            return StreamIds.CONTROL;
        }

        @Override
        public FrameType type() {
            return FrameType.HELLO;
        }
    }

    /** {@code REGISTER}, client to server. Its {@code toString} never contains the authtoken. */
    record Register(io.jburrow.protocol.control.Register message) implements NoBuffer {

        public Register {
            Objects.requireNonNull(message, "message");
        }

        @Override
        public int streamId() {
            return StreamIds.CONTROL;
        }

        @Override
        public FrameType type() {
            return FrameType.REGISTER;
        }
    }

    /** {@code REGISTER}, server to client. */
    record Registered(io.jburrow.protocol.control.Registered message) implements NoBuffer {

        public Registered {
            Objects.requireNonNull(message, "message");
        }

        @Override
        public int streamId() {
            return StreamIds.CONTROL;
        }

        @Override
        public FrameType type() {
            return FrameType.REGISTER;
        }
    }

    /** {@code OPEN}, server to client: a new stream. */
    record Open(int streamId, io.jburrow.protocol.control.Open message) implements NoBuffer {

        public Open {
            requireDataStream(streamId);
            Objects.requireNonNull(message, "message");
        }

        @Override
        public FrameType type() {
            return FrameType.OPEN;
        }
    }

    /**
     * {@code DATA}: 1 to 65,536 bytes of stream payload, never copied. Reference counting is the payload's.
     *
     * <p>The payload length is checked when the frame is encoded, not here, so an oversized payload is released by
     * the encoder that refuses it.
     */
    record Data(int streamId, ByteBuf payload) implements Frame {

        public Data {
            requireDataStream(streamId);
            Objects.requireNonNull(payload, "payload");
        }

        @Override
        public FrameType type() {
            return FrameType.DATA;
        }

        @Override
        public int refCnt() {
            return payload.refCnt();
        }

        @Override
        public Data retain() {
            payload.retain();
            return this;
        }

        @Override
        public Data retain(int increment) {
            payload.retain(increment);
            return this;
        }

        @Override
        public Data touch() {
            payload.touch(this);
            return this;
        }

        @Override
        public Data touch(Object hint) {
            payload.touch(hint);
            return this;
        }

        @Override
        public boolean release() {
            return payload.release();
        }

        @Override
        public boolean release(int decrement) {
            return payload.release(decrement);
        }

        /** Never the payload bytes, which may be anyone's traffic. */
        @Override
        public String toString() {
            return "Data[streamId=" + streamId + ", length=" + payload.readableBytes() + "]";
        }
    }

    /**
     * {@code WINDOW_UPDATE}: flow-control credit, from 1 to 2^31 - 1. Decoded and encodable, but no session of
     * {@code jburrow.v1-draft.1} accepts it.
     */
    record WindowUpdate(int streamId, int increment) implements NoBuffer {

        public WindowUpdate {
            requireDataStream(streamId);
            if (increment < 1) {
                throw new IllegalArgumentException("Window increment must be 1 to 2^31 - 1: " + increment);
            }
        }

        @Override
        public FrameType type() {
            return FrameType.WINDOW_UPDATE;
        }
    }

    /** {@code CLOSE}: a half-close when {@code reset} is empty, otherwise a reset with a stream error code. */
    record Close(int streamId, Optional<ErrorCode> reset) implements NoBuffer {

        public Close {
            requireDataStream(streamId);
            Objects.requireNonNull(reset, "reset");
            reset.ifPresent(code -> {
                if (!code.scopes().contains(ErrorScope.STREAM)) {
                    throw new IllegalArgumentException(code + " is not a stream error code");
                }
            });
        }

        public static Close half(int streamId) {
            return new Close(streamId, Optional.empty());
        }

        public static Close reset(int streamId, ErrorCode code) {
            return new Close(streamId, Optional.of(code));
        }

        @Override
        public FrameType type() {
            return FrameType.CLOSE;
        }
    }

    /** {@code PING}: 8 opaque bytes, which the answering {@link Pong} echoes. */
    record Ping(long opaque) implements NoBuffer {

        @Override
        public int streamId() {
            return StreamIds.CONTROL;
        }

        @Override
        public FrameType type() {
            return FrameType.PING;
        }
    }

    /** {@code PONG}: the 8 bytes of the {@link Ping} it answers. */
    record Pong(long opaque) implements NoBuffer {

        @Override
        public int streamId() {
            return StreamIds.CONTROL;
        }

        @Override
        public FrameType type() {
            return FrameType.PONG;
        }
    }

    /** {@code ERROR}: a session error. The sender closes the connection right after it. */
    record Error(ErrorBody body) implements NoBuffer {

        public Error {
            Objects.requireNonNull(body, "body");
        }

        public static Error of(ErrorCode code, String message) {
            return new Error(new ErrorBody(code, message, null));
        }

        @Override
        public int streamId() {
            return StreamIds.CONTROL;
        }

        @Override
        public FrameType type() {
            return FrameType.ERROR;
        }
    }

    /**
     * A {@code HEADERS} or {@code GOAWAY} frame, decoded only as far as its header: their payloads belong to phases 3
     * and 4. No peer of {@code jburrow.v1-draft.1} may send either, so a session treats this as
     * {@code PROTOCOL_ERROR}. It is never encoded.
     */
    record Unsupported(int streamId, FrameType type) implements NoBuffer {

        public Unsupported {
            if (type != FrameType.HEADERS && type != FrameType.GOAWAY) {
                throw new IllegalArgumentException(type + " is a supported frame type");
            }
            if (type.onControlStream() != (streamId == StreamIds.CONTROL)) {
                throw new IllegalArgumentException(type + " can't be on stream " + streamId);
            }
        }
    }

    private static void requireDataStream(int streamId) {
        if (streamId <= StreamIds.CONTROL) {
            throw new IllegalArgumentException("A data stream id must be 1 to 2^31 - 1: " + streamId);
        }
    }
}
