package org.logstash.beats;

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.SimpleChannelInboundHandler;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.util.concurrent.atomic.AtomicBoolean;

public class BeatsHandler extends SimpleChannelInboundHandler<Batch> {
    private final static Logger logger = LogManager.getLogger(BeatsHandler.class);
    private final AtomicBoolean processing = new AtomicBoolean(false);
    private final IMessageListener messageListener;
    private ChannelHandlerContext context;


    public BeatsHandler(IMessageListener listener) {
        messageListener = listener;
    }

    @Override
    public void handlerAdded(ChannelHandlerContext ctx) throws Exception {
        context = ctx;
        messageListener.onNewConnection(ctx);
    }

    @Override
    public void handlerRemoved(ChannelHandlerContext ctx) throws Exception {
        messageListener.onConnectionClose(ctx);
    }

    @Override
    public void channelRead0(ChannelHandlerContext ctx, Batch batch) throws Exception {
        logger.debug("Received a new payload");

        processing.compareAndSet(false, true);

        for(Message message : batch.getMessages()) {
            if(logger.isDebugEnabled()) {
                logger.debug("Sending a new message for the listener, sequence: " + message.getSequence());
            }
            messageListener.onNewMessage(ctx, message);

            if(needAck(message)) {
                ack(ctx, message);
            }
        }
        ctx.flush();
        processing.compareAndSet(true, false);

    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        InetSocketAddress remoteAddress = (InetSocketAddress) ctx.channel().remoteAddress();

        if (remoteAddress != null) {
            logger.info("Exception: " + cause.getMessage() + ", from: " + remoteAddress.toString());
        } else {
            logger.info("Exception: " + cause.getMessage());
        }
        messageListener.onException(ctx, cause);
        ctx.close();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object event) {
        if(event instanceof IdleStateEvent) {
            IdleStateEvent e = (IdleStateEvent) event;

            if(e.state() == IdleState.WRITER_IDLE) {
                sendKeepAlive();
            } else if(e.state() == IdleState.READER_IDLE) {
                clientTimeout();
            }
        }
    }

    private boolean needAck(Message message) {
        return message.getSequence() == message.getBatch().getBatchSize();
    }

    private void ack(ChannelHandlerContext ctx, Message message) {
        writeAck(ctx, message.getBatch().getProtocol(), message.getSequence());
    }

    private void writeAck(ChannelHandlerContext ctx, byte protocol, int sequence) {
        ctx.write(new Ack(protocol, sequence));
    }

    private void clientTimeout() {
        if(!processing.get()) {
            logger.debug("Client Timeout");
            this.context.close();
        }
    }

    private void sendKeepAlive() {
        // If we are actually blocked on processing
        // we can send a keep alive.
        if(processing.get()) {
            writeAck(context, Protocol.VERSION_2, 0);
        }
    }
}
