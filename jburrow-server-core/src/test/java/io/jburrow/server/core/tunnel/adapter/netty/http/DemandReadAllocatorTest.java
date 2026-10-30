package io.jburrow.server.core.tunnel.adapter.netty.http;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import io.jburrow.server.core.tunnel.adapter.netty.edge.EdgeTransport;
import io.netty.bootstrap.ServerBootstrap;
import io.netty.buffer.ByteBuf;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.channel.ChannelInitializer;
import io.netty.channel.ChannelOption;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.MultiThreadIoEventLoopGroup;
import io.netty.channel.RecvByteBufAllocator;
import io.netty.channel.ServerChannel;
import io.netty.channel.embedded.EmbeddedChannel;
import io.netty.channel.epoll.EpollIoHandler;
import io.netty.channel.epoll.EpollServerSocketChannel;
import io.netty.channel.nio.NioIoHandler;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.netty.util.ReferenceCountUtil;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/** {@code docs/phase-3-http-tunnels/ARCHITECTURE.md} §Visitor to client: demand reads, step 2. */
class DemandReadAllocatorTest {

    @Test
    void aDemandSizesExactlyTheNextReadAndHeadsAreReadInSmallChunks() {
        EmbeddedChannel channel = installed();
        DemandReadAllocator reads = DemandReadAllocator.of(channel);
        RecvByteBufAllocator.Handle handle =
                channel.config().getRecvByteBufAllocator().newHandle();

        reads.demand(1_000);
        ByteBuf demanded = handle.allocate(channel.alloc());
        ByteBuf next = handle.allocate(channel.alloc());

        assertThat(demanded.writableBytes()).isEqualTo(1_000);
        assertThat(next.writableBytes()).isEqualTo(DemandReadAllocator.HEAD_READ);
        demanded.release();
        next.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void aReadIsOnePerTurnAndNeverGuessedLargerByFullReads() {
        EmbeddedChannel channel = installed();
        RecvByteBufAllocator.Handle handle =
                channel.config().getRecvByteBufAllocator().newHandle();

        for (int i = 0; i < 10; i++) {
            handle.reset(channel.config());
            ByteBuf buffer = handle.allocate(channel.alloc());
            handle.attemptedBytesRead(buffer.writableBytes());
            handle.lastBytesRead(buffer.writableBytes());
            handle.incMessagesRead(1);
            assertThat(handle.continueReading()).isFalse();
            handle.readComplete();
            buffer.release();
        }

        assertThat(handle.guess()).isEqualTo(DemandReadAllocator.HEAD_READ);
        channel.finishAndReleaseAll();
    }

    @Test
    void aSessionsReadsAreTheChannelsOwnAgain() {
        EmbeddedChannel channel = installed();
        RecvByteBufAllocator.Handle handle =
                channel.config().getRecvByteBufAllocator().newHandle();
        DemandReadAllocator.of(channel).demand(10);

        DemandReadAllocator.toSessionReads(channel);
        handle.reset(channel.config());
        ByteBuf buffer = handle.allocate(channel.alloc());

        assertThat(buffer.writableBytes()).isNotEqualTo(10).isNotEqualTo(DemandReadAllocator.HEAD_READ);
        handle.attemptedBytesRead(buffer.writableBytes());
        handle.lastBytesRead(buffer.writableBytes());
        handle.incMessagesRead(1);
        assertThat(handle.continueReading()).isTrue();
        buffer.release();
        channel.finishAndReleaseAll();
    }

    @Test
    void aDemandIsAtLeastOneByte() {
        EmbeddedChannel channel = installed();

        assertThatIllegalArgumentException()
                .isThrownBy(() -> DemandReadAllocator.of(channel).demand(0));
        channel.finishAndReleaseAll();
    }

    @ParameterizedTest
    @MethodSource("io.jburrow.server.core.tunnel.adapter.netty.support.Transports#available")
    void onASocketADemandIsOneReadOfAtMostItsSize(EdgeTransport transport) throws Exception {
        BlockingQueue<Object> events = new LinkedBlockingQueue<>();
        CompletableFuture<Channel> accepted = new CompletableFuture<>();
        boolean epoll = transport == EdgeTransport.EPOLL;
        EventLoopGroup group =
                new MultiThreadIoEventLoopGroup(1, epoll ? EpollIoHandler.newFactory() : NioIoHandler.newFactory());
        Class<? extends ServerChannel> serverChannel =
                epoll ? EpollServerSocketChannel.class : NioServerSocketChannel.class;
        try {
            Channel server = new ServerBootstrap()
                    .group(group)
                    .channel(serverChannel)
                    .childOption(ChannelOption.AUTO_READ, false)
                    .childHandler(new ChannelInitializer<>() {
                        @Override
                        protected void initChannel(Channel channel) {
                            DemandReadAllocator.install(channel);
                            channel.pipeline().addLast(new ChannelInboundHandlerAdapter() {
                                @Override
                                public void channelActive(ChannelHandlerContext ctx) {
                                    accepted.complete(ctx.channel());
                                }

                                @Override
                                public void channelRead(ChannelHandlerContext ctx, Object msg) {
                                    events.add(((ByteBuf) msg).readableBytes());
                                    ReferenceCountUtil.release(msg);
                                }

                                @Override
                                public void channelReadComplete(ChannelHandlerContext ctx) {
                                    events.add("complete");
                                }
                            });
                        }
                    })
                    .bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0))
                    .sync()
                    .channel();
            try (Socket client = new Socket(
                    InetAddress.getLoopbackAddress(), ((InetSocketAddress) server.localAddress()).getPort())) {
                OutputStream out = client.getOutputStream();
                out.write(new byte[100_000]);
                out.flush();
                Channel channel = accepted.get(5, TimeUnit.SECONDS);
                awaitReadable(channel);

                for (int demand : List.of(1_000, 7, 16_384)) {
                    channel.eventLoop()
                            .submit(() -> {
                                DemandReadAllocator.of(channel).demand(demand);
                                channel.read();
                            })
                            .sync();

                    assertThat(events.poll(5, TimeUnit.SECONDS)).isEqualTo(demand);
                    assertThat(events.poll(5, TimeUnit.SECONDS)).isEqualTo("complete");
                    assertThat(events.poll(100, TimeUnit.MILLISECONDS)).isNull();
                }
            } finally {
                server.close().sync();
            }
        } finally {
            group.shutdownGracefully(0, 1, TimeUnit.SECONDS).sync();
        }
    }

    /** Lets the client's bytes reach the socket, so each demand finds more than it asks for. */
    private static void awaitReadable(Channel channel) throws InterruptedException {
        TimeUnit.MILLISECONDS.sleep(200);
        assertThat(channel.isActive()).isTrue();
    }

    private static EmbeddedChannel installed() {
        EmbeddedChannel channel = new EmbeddedChannel();
        DemandReadAllocator.install(channel);
        return channel;
    }
}
