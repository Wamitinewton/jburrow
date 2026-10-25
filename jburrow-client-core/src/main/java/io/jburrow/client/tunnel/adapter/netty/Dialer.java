package io.jburrow.client.tunnel.adapter.netty;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.Channel;
import io.netty.channel.ChannelFuture;
import io.netty.channel.EventLoop;
import io.netty.util.NetUtil;
import io.netty.util.concurrent.Future;
import io.netty.util.concurrent.Promise;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

/**
 * Connects to a host by trying each of its addresses in turn until one accepts, so a name like {@code localhost} that
 * resolves to both {@code ::1} and {@code 127.0.0.1} reaches a service listening on only one of them.
 *
 * <p>A name is resolved on the blocking executor, never on the event loop: resolution can wait on DNS for seconds, and
 * the loop carries every byte of the session. An IP literal is used as it is.
 */
final class Dialer {

    private final HostResolver resolver;
    private final Executor blocking;

    Dialer(HostResolver resolver, Executor blocking) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
        this.blocking = Objects.requireNonNull(blocking, "blocking");
    }

    /**
     * Connects {@code bootstrap}, whose group must be {@code loop}, to {@code host:port}. The future completes on
     * {@code loop} with the connected channel, or fails with the resolution failure or the last connect failure.
     * Cancelling it abandons the attempt: a channel that connects afterwards is closed.
     */
    Future<Channel> dial(Bootstrap bootstrap, EventLoop loop, String host, int port) {
        Promise<Channel> result = loop.newPromise();
        InetAddress literal = NetUtil.createInetAddressFromIpAddressString(host);
        if (literal != null) {
            attempt(bootstrap, List.of(literal), 0, port, result);
            return result;
        }
        try {
            blocking.execute(() -> {
                List<InetAddress> addresses;
                try {
                    addresses = resolver.resolve(host);
                    if (addresses.isEmpty()) {
                        throw new UnknownHostException(host + ": no addresses");
                    }
                } catch (UnknownHostException | RuntimeException e) {
                    result.tryFailure(e);
                    return;
                }
                try {
                    loop.execute(() -> attempt(bootstrap, addresses, 0, port, result));
                } catch (RejectedExecutionException e) {
                    result.tryFailure(e);
                }
            });
        } catch (RejectedExecutionException e) {
            result.tryFailure(e);
        }
        return result;
    }

    private static void attempt(
            Bootstrap bootstrap, List<InetAddress> addresses, int index, int port, Promise<Channel> result) {
        if (result.isDone()) {
            return;
        }
        ChannelFuture connect = bootstrap.connect(new InetSocketAddress(addresses.get(index), port));
        connect.addListener((ChannelFuture done) -> {
            if (done.isSuccess()) {
                if (!result.trySuccess(done.channel())) {
                    done.channel().close();
                }
            } else if (index + 1 < addresses.size() && !result.isDone()) {
                attempt(bootstrap, addresses, index + 1, port, result);
            } else {
                result.tryFailure(done.cause());
            }
        });
    }
}
