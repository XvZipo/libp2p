package org.tron.p2p.connection.socket;

import io.netty.bootstrap.Bootstrap;
import io.netty.channel.ChannelFuture;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelOption;
import io.netty.channel.DefaultMessageSizeEstimator;
import io.netty.channel.EventLoopGroup;
import io.netty.channel.nio.NioEventLoopGroup;
import io.netty.channel.socket.nio.NioSocketChannel;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.extern.slf4j.Slf4j;
import org.bouncycastle.util.encoders.Hex;
import org.tron.p2p.base.Parameter;
import org.tron.p2p.connection.ChannelManager;
import org.tron.p2p.discover.Node;
import org.tron.p2p.utils.NetUtil;

@Slf4j(topic = "net")
public class PeerClient {

  private EventLoopGroup workerGroup;

  public void init() {
    workerGroup = new NioEventLoopGroup(0, new ThreadFactory() {
      private final AtomicInteger cnt = new AtomicInteger(0);

      @Override
      public Thread newThread(Runnable r) {
        return new Thread(r, "PeerClient-" + cnt.getAndIncrement());
      }
    });
  }

  public void close() {
    workerGroup.shutdownGracefully();
    workerGroup.terminationFuture().syncUninterruptibly();
  }

  public void connect(String host, int port, String remoteId) {
    try {
      ChannelFuture f = connectAsync(host, port, remoteId, false, false);
      f.sync().channel().closeFuture().sync();
    } catch (Exception e) {
      log.warn("PeerClient can't connect to {}:{} ({})", host, port, e.getMessage());
    }
  }

  public ChannelFuture connect(Node node, ChannelFutureListener future) {
    ChannelFuture channelFuture = connectAsync(
        node.getPreferInetSocketAddress().getAddress().getHostAddress(),
        node.getPort(),
        node.getId() == null ? Hex.toHexString(NetUtil.getNodeId()) : node.getHexId(), false, false);
    if (future != null) {
      channelFuture.addListener(future);
    }
    return channelFuture;
  }

  public ChannelFuture connectAsync(Node node, boolean discoveryMode) {
    return connectAsync(node.getPreferInetSocketAddress().getAddress().getHostAddress(),
        node.getPort(),
        node.getId() == null ? Hex.toHexString(NetUtil.getNodeId()) : node.getHexId(),
        discoveryMode, true)
        .addListener((ChannelFutureListener) future -> {
          if (!future.isSuccess()) {
            log.warn("Connect to peer {} fail, cause:{}", node.getPreferInetSocketAddress(),
                future.cause().getMessage());
            future.channel().close();
            if (!discoveryMode) {
              ChannelManager.triggerConnect(node.getPreferInetSocketAddress());
            }
          }
        });
  }

  private ChannelFuture connectAsync(String host, int port, String remoteId,
      boolean discoveryMode, boolean trigger) {

    P2pChannelInitializer p2pChannelInitializer = new P2pChannelInitializer(remoteId,
        discoveryMode, trigger);

    Bootstrap b = new Bootstrap();
    b.group(workerGroup);
    b.channel(NioSocketChannel.class);
    b.option(ChannelOption.SO_KEEPALIVE, true);
    b.option(ChannelOption.MESSAGE_SIZE_ESTIMATOR, DefaultMessageSizeEstimator.DEFAULT);
    b.option(ChannelOption.CONNECT_TIMEOUT_MILLIS, Parameter.NODE_CONNECTION_TIMEOUT);
    b.remoteAddress(host, port);
    b.handler(p2pChannelInitializer);
    return b.connect();
  }
}
