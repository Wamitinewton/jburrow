package io.jburrow.server.core.tunnel.adapter.netty.edge;

import io.jburrow.server.core.tunnel.adapter.netty.http.DemandReadAllocator;
import io.jburrow.server.core.tunnel.adapter.netty.http.EdgeErrorPage;
import io.jburrow.server.core.tunnel.application.port.TunnelMetrics;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.group.ChannelGroup;
import io.netty.handler.codec.http.HttpDecoderConfig;
import io.netty.handler.codec.http.HttpServerCodec;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * Builds the pipeline of an accepted edge connection for its request phase: the request deadline, the HTTP codec and
 * the {@link HostDispatcher}, which adds the handlers each request needs behind itself
 * ({@code docs/phase-3-http-tunnels/ARCHITECTURE.md} §Dispatch on {@code Host}). Its reads are sized by a
 * {@link DemandReadAllocator} from the first, since Netty only asks the allocator a channel had for its first read.
 */
final class EdgeChannelInitializer extends ChannelInitializer<Channel> {

    static final String REQUEST_DEADLINE = "request-deadline";
    static final String HTTP_CODEC = "http-codec";
    static final String HOST_DISPATCHER = "host-dispatcher";

    static final int MAX_INITIAL_LINE_LENGTH = 4_096;
    static final int MAX_HEADER_SIZE = 8_192;
    static final int MAX_CHUNK_SIZE = 16 * 1024;

    private final EdgeSettings settings;
    private final ChannelGroup connections;
    private final ConnectPipeline connect;
    private final ClientAddressResolver clientAddresses;
    private final Supplier<? extends ChannelHandler> exchanges;
    private final EdgeErrorPage pages;
    private final TunnelMetrics metrics;

    /** @param exchanges makes the exchange handler of a connection, the first time it carries a tunnel request */
    EdgeChannelInitializer(
            EdgeSettings settings,
            ChannelGroup connections,
            ConnectPipeline connect,
            ClientAddressResolver clientAddresses,
            Supplier<? extends ChannelHandler> exchanges,
            EdgeErrorPage pages,
            TunnelMetrics metrics) {
        this.settings = Objects.requireNonNull(settings, "settings");
        this.connections = Objects.requireNonNull(connections, "connections");
        this.connect = Objects.requireNonNull(connect, "connect");
        this.clientAddresses = Objects.requireNonNull(clientAddresses, "clientAddresses");
        this.exchanges = Objects.requireNonNull(exchanges, "exchanges");
        this.pages = Objects.requireNonNull(pages, "pages");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    @Override
    protected void initChannel(Channel channel) {
        connections.add(channel);
        DemandReadAllocator.install(channel);
        HttpDecoderConfig http = new HttpDecoderConfig()
                .setMaxInitialLineLength(MAX_INITIAL_LINE_LENGTH)
                .setMaxHeaderSize(MAX_HEADER_SIZE)
                .setMaxChunkSize(MAX_CHUNK_SIZE)
                .setValidateHeaders(true);
        RequestDeadline deadline = new RequestDeadline(settings.requestTimeout().toNanos());
        channel.pipeline()
                .addLast(REQUEST_DEADLINE, deadline)
                .addLast(HTTP_CODEC, new HttpServerCodec(http))
                .addLast(
                        HOST_DISPATCHER,
                        new HostDispatcher(
                                settings.connectHost(),
                                settings.http(),
                                settings.requestTimeout(),
                                connect,
                                clientAddresses,
                                exchanges,
                                pages,
                                deadline,
                                metrics));
    }
}
