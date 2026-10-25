package io.jburrow.server.core.tunnel.adapter.netty.tcp;

import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPromise;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.socket.ChannelInputShutdownEvent;
import io.netty.channel.socket.DuplexChannel;
import java.net.InetSocketAddress;
import java.net.SocketAddress;

/** An {@link EmbeddedChannel} that stands in for an accepted visitor connection, half-closure included. */
final class DuplexEmbeddedChannel extends EmbeddedChannel implements DuplexChannel {

    private final InetSocketAddress remote;
    private boolean inputShutdown;
    private boolean outputShutdown;

    DuplexEmbeddedChannel(InetSocketAddress remote, ChannelHandler... handlers) {
        super(false, true, handlers);
        this.remote = remote;
        try {
            register();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    protected SocketAddress remoteAddress0() {
        return remote;
    }

    @Override
    public boolean isInputShutdown() {
        return inputShutdown;
    }

    @Override
    public ChannelFuture shutdownInput() {
        return shutdownInput(newPromise());
    }

    @Override
    public ChannelFuture shutdownInput(ChannelPromise promise) {
        inputShutdown = true;
        return promise.setSuccess();
    }

    @Override
    public boolean isOutputShutdown() {
        return outputShutdown;
    }

    @Override
    public ChannelFuture shutdownOutput() {
        return shutdownOutput(newPromise());
    }

    @Override
    public ChannelFuture shutdownOutput(ChannelPromise promise) {
        outputShutdown = true;
        return promise.setSuccess();
    }

    @Override
    public boolean isShutdown() {
        return inputShutdown && outputShutdown;
    }

    @Override
    public ChannelFuture shutdown() {
        return shutdown(newPromise());
    }

    @Override
    public ChannelFuture shutdown(ChannelPromise promise) {
        inputShutdown = true;
        outputShutdown = true;
        return promise.setSuccess();
    }

    /** The visitor sent {@code FIN}, as a real socket channel with half-closure allowed reports it. */
    void receiveFin() {
        inputShutdown = true;
        pipeline().fireUserEventTriggered(ChannelInputShutdownEvent.INSTANCE);
    }
}
