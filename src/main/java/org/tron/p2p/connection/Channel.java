package org.tron.p2p.connection;

import com.google.common.base.Throwables;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import io.netty.handler.codec.protobuf.ProtobufVarint32LengthFieldPrepender;
import io.netty.handler.timeout.ReadTimeoutException;
import io.netty.handler.timeout.ReadTimeoutHandler;
import java.util.concurrent.TimeUnit;
import lombok.Getter;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;
import org.tron.p2p.connection.socket.MessageHandler;
import org.tron.p2p.connection.socket.TrxProtobufVarint32FrameDecoder;
import org.tron.p2p.exception.P2pException;
import com.google.protobuf.ByteString;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import org.tron.p2p.discover.Node;

import java.io.IOException;
import java.net.SocketAddress;
import org.tron.p2p.stats.P2pStats;

@Slf4j(topic = "net")
public class Channel {

  private ChannelManager channelManager;
  @Setter
  private ChannelHandlerContext ctx;

  private P2pStats p2pStats;
  private MessageHandler messageHandler;
  @Getter
  private volatile long disconnectTime;
  private volatile boolean isDisconnect;
  @Getter
  @Setter
  long lastSendTime = 0;
  @Getter
  private boolean isActive;
  private InetSocketAddress inetSocketAddress;
  private Node node;
  private long startTime;

  @Getter
  @Setter
  private ByteString address;

  private boolean isTrustPeer;

  public void init(ChannelPipeline pipeline, String remoteId, boolean discoveryMode,
      ChannelManager channelManager) {

    this.channelManager = channelManager;

    isActive = remoteId != null && !remoteId.isEmpty();

    startTime = System.currentTimeMillis();

    //TODO: use config here
    pipeline.addLast("readTimeoutHandler", new ReadTimeoutHandler(60, TimeUnit.SECONDS));
    //pipeline.addLast(stats.tcp); // todo
    pipeline.addLast("protoPender", new ProtobufVarint32LengthFieldPrepender());
    pipeline.addLast("lengthDecode", new TrxProtobufVarint32FrameDecoder(this));

    //handshake first
    //pipeline.addLast("handshakeHandler", handshakeHandler); //todo

    //messageCodec.setChannel(this);
    //handshakeHandler.setChannel(this, remoteId); //todo
    messageHandler.setChannel(this);
  }

  public void disconnect(Channel channel, int code) {
    this.isDisconnect = true;
    this.disconnectTime = System.currentTimeMillis();
    channelManager.processDisconnect(this, code);
//    DisconnectMessage msg = new DisconnectMessage(reason);
//    logger.info("Send to {} online-time {}s, {}",
//        ctx.channel().remoteAddress(),
//        (System.currentTimeMillis() - startTime) / 1000,
//        msg);
//    getNodeStatistics().nodeDisconnectedLocal(reason);
//    ctx.writeAndFlush(msg.getSendData()).addListener(future -> close());
  }

  public void send(byte[] data) {
    if (isDisconnect) {
      log.warn("Send to {} failed as channel has closed, message-type:{} ",
          ctx.channel().remoteAddress(), data[0]);
      return;
    }
    ctx.writeAndFlush(data).addListener((ChannelFutureListener) future -> {
      if (!future.isSuccess() && !isDisconnect) {
        log.warn("Send to {} failed, message-type:{}",
            ctx.channel().remoteAddress(), data[0]);
      }
    });
  }

  public void processException(Throwable throwable) {
    Throwable baseThrowable = throwable;
    try {
      baseThrowable = Throwables.getRootCause(baseThrowable);
    } catch (IllegalArgumentException e) {
      baseThrowable = e.getCause();
      log.warn("Loop in causal chain detected");
    }
    SocketAddress address = ctx.channel().remoteAddress();
    if (throwable instanceof ReadTimeoutException
        || throwable instanceof IOException) {
      log.warn("Close peer {}, reason: {}", address, throwable.getMessage());
    } else if (baseThrowable instanceof P2pException) {
      log.warn("Close peer {}, type: {}, info: {}",
          address, ((P2pException) baseThrowable).getType(), baseThrowable.getMessage());
    } else {
      log.error("Close peer {}, exception caught", address, throwable);
    }
    close();
  }

  public boolean isDisconnect() {
    return isDisconnect;
  }

  //if handshake finished, wo have ctx
  public void setChannelHandlerContext(ChannelHandlerContext ctx) {
    this.ctx = ctx;
    this.inetSocketAddress = ctx == null ? null : (InetSocketAddress) ctx.channel().remoteAddress();
  }

  public InetAddress getInetAddress() {
    return ctx == null ? null : ((InetSocketAddress) ctx.channel().remoteAddress()).getAddress();
  }

  public String getPeerId() {
    return node == null ? "<null>" : node.getHexId();
  }

  public void close() {
    this.isDisconnect = true;
    this.disconnectTime = System.currentTimeMillis();
    ctx.close();
  }
}
