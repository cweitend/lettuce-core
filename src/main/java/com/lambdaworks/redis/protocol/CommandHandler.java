// Copyright (C) 2011 - Will Glozer.  All rights reserved.

package com.lambdaworks.redis.protocol;

import java.nio.charset.Charset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.locks.ReentrantLock;

import com.google.common.annotations.VisibleForTesting;
import com.lambdaworks.redis.*;

import io.netty.buffer.ByteBuf;
import io.netty.channel.*;
import io.netty.util.internal.logging.InternalLogger;
import io.netty.util.internal.logging.InternalLoggerFactory;

/**
 * A netty {@link ChannelHandler} responsible for writing redis commands and reading responses from the server.
 * 
 * @param <K> Key type.
 * @param <V> Value type.
 * @author Will Glozer
 */
@ChannelHandler.Sharable
public class CommandHandler<K, V> extends ChannelDuplexHandler implements RedisChannelWriter<K, V> {

    private static final InternalLogger logger = InternalLoggerFactory.getInstance(CommandHandler.class);

    protected ClientOptions clientOptions;
    protected Queue<RedisCommand<K, V, ?>> queue;
    protected Queue<RedisCommand<K, V, ?>> commandBuffer = new ArrayDeque<RedisCommand<K, V, ?>>();
    protected ByteBuf buffer;
    protected RedisStateMachine<K, V> rsm;

    private LifecycleState lifecycleState = LifecycleState.NOT_CONNECTED;
    private Object stateLock = new Object();
    private Channel channel;

    /**
     * If TRACE level logging has been enabled at startup.
     */
    private final boolean traceEnabled;

    /**
     * If DEBUG level logging has been enabled at startup.
     */
    private final boolean debugEnabled;

    private final ReentrantLock writeLock = new ReentrantLock();
    private final Reliability reliability;
    private RedisChannelHandler<K, V> redisChannelHandler;
    private Throwable connectionError;
    private String logPrefix;

    /**
     * Initialize a new instance that handles commands from the supplied queue.
     *
     * @param clientOptions client options for this connection
     * @param queue The command queue
     */
    public CommandHandler(ClientOptions clientOptions, Queue<RedisCommand<K, V, ?>> queue) {
        this.clientOptions = clientOptions;
        this.queue = queue;
        this.traceEnabled = logger.isTraceEnabled();
        this.debugEnabled = logger.isDebugEnabled();
        this.reliability = clientOptions.isAutoReconnect() ? Reliability.AT_LEAST_ONCE : Reliability.AT_MOST_ONCE;
    }

    /**
     * 
     * @see io.netty.channel.ChannelInboundHandlerAdapter#channelRegistered(io.netty.channel.ChannelHandlerContext)
     */
    @Override
    public void channelRegistered(ChannelHandlerContext ctx) throws Exception {
        setState(LifecycleState.REGISTERED);
        buffer = ctx.alloc().heapBuffer();
        rsm = new RedisStateMachine<K, V>();
        synchronized (stateLock) {
            channel = ctx.channel();
        }
    }

    @Override
    public void channelUnregistered(ChannelHandlerContext ctx) throws Exception {
        releaseBuffer();

        if (lifecycleState == LifecycleState.CLOSED) {
            cancelCommands("Connection closed");
        }
        synchronized (stateLock) {
            channel = null;
        }
    }

    /**
     * 
     * @see io.netty.channel.ChannelInboundHandlerAdapter#channelRead(io.netty.channel.ChannelHandlerContext, java.lang.Object)
     */
    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) throws Exception {
        ByteBuf input = (ByteBuf) msg;

        if (!input.isReadable() || input.refCnt() == 0 || buffer == null) {
            return;
        }

        try {
            buffer.writeBytes(input);

            if (traceEnabled) {
                logger.trace("{} Received: {}", logPrefix(), buffer.toString(Charset.defaultCharset()).trim());
            }

            decode(ctx, buffer);
        } finally {
            input.release();
        }
    }

    protected void decode(ChannelHandlerContext ctx, ByteBuf buffer) throws InterruptedException {

        while (!queue.isEmpty()) {

            RedisCommand<K, V, ?> command = queue.peek();

            if (debugEnabled) {
                logger.debug("{} Queue contains: {} commands", logPrefix(), queue.size());
            }

            if (!rsm.decode(buffer, command, command.getOutput())) {
                return;
            }

            command = queue.poll();
            command.complete();
            if (buffer != null && buffer.refCnt() != 0) {
                buffer.discardReadBytes();
            }
        }
    }

    @Override
    public <T, C extends RedisCommand<K, V, T>> C write(C command) {

        if (lifecycleState == LifecycleState.CLOSED) {
            throw new RedisException("Connection is closed");
        }

        if ((channel == null || !isConnected()) && !clientOptions.isAutoReconnect()) {
            command.completeExceptionally(new RedisException(
                    "Connection is in a disconnected state and reconnect is disabled. Commands are not accepted."));
            return command;
        }

        try {
            /**
             * This lock causes safety for connection activation and somehow netty gets more stable and predictable performance
             * than without a lock and all threads are hammering towards writeAndFlush.
             */

            writeLock.lock();
            Channel channel = this.channel;
            if (channel != null && isConnected() && channel.isActive()) {
                if (debugEnabled) {
                    logger.debug("{} write() writeAndFlush Command {}", logPrefix(), command);
                }

                if (reliability == Reliability.AT_MOST_ONCE) {
                    // cancel on exceptions and remove from queue, because there is no housekeeping
                    channel.write(command).addListener(new AtMostOnceWriteListener(command, queue));
                    channel.flush();
                }

                if (reliability == Reliability.AT_LEAST_ONCE) {
                    // commands are ok to stay within the queue, reconnect will retrigger them
                    channel.write(command, channel.voidPromise());
                    channel.flush();
                }
            } else {

                if (commandBuffer.contains(command) || queue.contains(command)) {
                    return command;
                }

                if (connectionError != null) {
                    if (debugEnabled) {
                        logger.debug("{} write() completing Command {} due to connection error", logPrefix(), command);
                    }
                    command.completeExceptionally(connectionError);
                    return command;
                }

                if (debugEnabled) {
                    logger.debug("{} write() buffering Command {}", logPrefix(), command);
                }
                commandBuffer.add(command);
            }
        } finally {
            writeLock.unlock();
            if (debugEnabled) {
                logger.debug("{} write() done", logPrefix());
            }
        }

        return command;
    }

    private boolean isConnected() {
        return lifecycleState.ordinal() >= LifecycleState.CONNECTED.ordinal()
                && lifecycleState.ordinal() <= LifecycleState.DISCONNECTED.ordinal();
    }

    /**
     *
     * @see io.netty.channel.ChannelDuplexHandler#write(io.netty.channel.ChannelHandlerContext, java.lang.Object,
     *      io.netty.channel.ChannelPromise)
     */
    @Override
    @SuppressWarnings("unchecked")
    public void write(ChannelHandlerContext ctx, Object msg, ChannelPromise promise) throws Exception {

        final RedisCommand<K, V, ?> cmd = (RedisCommand<K, V, ?>) msg;

        try {
            if (cmd.getOutput() == null) {
                cmd.complete();
            } else {
                queue.add(cmd);
            }
        } catch (Exception e) {
            cmd.completeExceptionally(e);
            cmd.cancel();
            promise.setFailure(e);
            throw e;
        }

        ctx.write(cmd, promise);
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) throws Exception {
        logPrefix = null;
        if (debugEnabled) {
            logger.debug("{} channelActive()", logPrefix());
        }
        setStateIfNotClosed(LifecycleState.CONNECTED);

        try {
            executeQueuedCommands(ctx);
        } catch (Exception e) {
            if (debugEnabled) {
                logger.debug("{} channelActive() ran into an exception", logPrefix());
            }
            if (clientOptions.isCancelCommandsOnReconnectFailure()) {
                reset();
            }
            throw e;
        }

        super.channelActive(ctx);
        channel.eventLoop().submit(new Runnable() {
            @Override
            public void run() {
                channel.pipeline().fireUserEventTriggered(new ConnectionEvents.Activated());
            }
        });

        if (debugEnabled) {
            logger.debug("{} channelActive() done", logPrefix());
        }
    }

    protected void executeQueuedCommands(ChannelHandlerContext ctx) {
        List<RedisCommand<K, V, ?>> tmp = new ArrayList<>(queue.size() + commandBuffer.size());

        try {
            writeLock.lock();
            connectionError = null;

            tmp.addAll(commandBuffer);
            tmp.addAll(queue);

            queue.clear();
            commandBuffer.clear();

            if (debugEnabled) {
                logger.debug("{} executeQueuedCommands {} command(s) queued", logPrefix(), tmp.size());
            }

            synchronized (stateLock) {
                channel = ctx.channel();
            }

            if (redisChannelHandler != null) {
                if (debugEnabled) {
                    logger.debug("{} activating channel handler", logPrefix());
                }
                setStateIfNotClosed(LifecycleState.ACTIVATING);
                redisChannelHandler.activated();
            }
            setStateIfNotClosed(LifecycleState.ACTIVE);

            for (RedisCommand<K, V, ?> cmd : tmp) {
                if (!cmd.isCancelled()) {

                    if (debugEnabled) {
                        logger.debug("{} channelActive() triggering command {}", logPrefix(), cmd);
                    }

                    write(cmd);
                }
            }

            tmp.clear();

        } finally {
            writeLock.unlock();
        }

    }

    /**
     * 
     * @see io.netty.channel.ChannelInboundHandlerAdapter#channelInactive(io.netty.channel.ChannelHandlerContext)
     */
    @Override
    public void channelInactive(ChannelHandlerContext ctx) throws Exception {
        if (debugEnabled) {
            logger.debug("{} channelInactive()", logPrefix());
        }
        setStateIfNotClosed(LifecycleState.DISCONNECTED);

        if (redisChannelHandler != null) {
            if (debugEnabled) {
                logger.debug("{} deactivating channel handler", logPrefix());
            }
            setStateIfNotClosed(LifecycleState.DEACTIVATING);
            redisChannelHandler.deactivated();
        }
        setStateIfNotClosed(LifecycleState.DEACTIVATED);

        if (buffer != null) {
            rsm.reset();
            buffer.clear();
        }

        if (debugEnabled) {
            logger.debug("{} channelInactive() done", logPrefix());
        }
        super.channelInactive(ctx);
    }

    protected void setStateIfNotClosed(LifecycleState lifecycleState) {
        if (this.lifecycleState != LifecycleState.CLOSED) {
            setState(lifecycleState);
        }
    }

    protected void setState(LifecycleState lifecycleState) {
        synchronized (stateLock) {
            this.lifecycleState = lifecycleState;
        }
    }

    private void cancelCommands(String message) {
        int size = 0;
        if (queue != null) {
            size += queue.size();
        }

        if (commandBuffer != null) {
            size += commandBuffer.size();
        }

        List<RedisCommand<K, V, ?>> toCancel = new ArrayList<>(size);

        if (queue != null) {
            toCancel.addAll(queue);
            queue.clear();
        }

        if (commandBuffer != null) {
            toCancel.addAll(commandBuffer);
            commandBuffer.clear();
        }

        for (RedisCommand<K, V, ?> cmd : toCancel) {
            if (cmd.getOutput() != null) {
                cmd.getOutput().setError(message);
            }
            cmd.cancel();
        }
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) throws Exception {
        if (debugEnabled) {
            logger.debug("{} exceptionCaught() {}", logPrefix(), cause);
        }
        if (!queue.isEmpty()) {
            RedisCommand<K, V, ?> command = queue.poll();
            if (debugEnabled) {
                logger.debug("{} Storing exception in {}", logPrefix(), command);
            }
            command.completeExceptionally(cause);
        }

        if (channel == null || !channel.isActive() || !isConnected()) {
            if (debugEnabled) {
                logger.debug("{} Storing exception in connectionError", logPrefix());
            }
            connectionError = cause;
            return;
        }
        super.exceptionCaught(ctx, cause);
    }

    /**
     * Close the connection.
     */
    @Override
    public void close() {

        if (debugEnabled) {
            logger.debug("{} close()", logPrefix());
        }

        if (lifecycleState == LifecycleState.CLOSED) {
            return;
        }

        setStateIfNotClosed(LifecycleState.CLOSED);
        Channel currentChannel = this.channel;
        if (currentChannel != null) {
            currentChannel.pipeline().fireUserEventTriggered(new ConnectionEvents.PrepareClose());
            currentChannel.pipeline().fireUserEventTriggered(new ConnectionEvents.Close());
            currentChannel.closeFuture().syncUninterruptibly();
        }
    }

    private void releaseBuffer() {
        if (buffer != null) {
            buffer.release();
            buffer = null;
        }
    }

    public boolean isClosed() {
        return lifecycleState == LifecycleState.CLOSED;
    }

    /**
     * Reset the writer state. Queued commands will be canceled and the internal state will be reset. This is useful when the
     * internal state machine gets out of sync with the connection.
     */
    @Override
    public void reset() {
        if (debugEnabled) {
            logger.debug("{} reset()", logPrefix());
        }
        try {
            writeLock.lock();
            cancelCommands("Reset");
        } finally {
            writeLock.unlock();
        }

        if (buffer != null) {
            rsm.reset();
            buffer.clear();
        }
    }

    @Override
    public void setRedisChannelHandler(RedisChannelHandler<K, V> redisChannelHandler) {
        this.redisChannelHandler = redisChannelHandler;
    }

    private String logPrefix() {
        if (logPrefix != null) {
            return logPrefix;
        }
        StringBuffer buffer = new StringBuffer(16);
        buffer.append('[');
        if (channel != null) {
            buffer.append(channel.remoteAddress());
        } else {
            buffer.append("not connected");
        }
        buffer.append(']');
        return logPrefix = buffer.toString();
    }

    @VisibleForTesting
    enum LifecycleState {

        NOT_CONNECTED, REGISTERED, CONNECTED, ACTIVATING, ACTIVE, DISCONNECTED, DEACTIVATING, DEACTIVATED, CLOSED,
    }

    private enum Reliability {
        AT_MOST_ONCE, AT_LEAST_ONCE;
    }

    private static class AtMostOnceWriteListener implements ChannelFutureListener {

        private final RedisCommand<?, ?, ?> sentCommand;
        private final Queue<?> queue;

        public AtMostOnceWriteListener(RedisCommand<?, ?, ?> sentCommand, Queue<?> queue) {
            this.sentCommand = sentCommand;
            this.queue = queue;
        }

        @Override
        public void operationComplete(ChannelFuture future) throws Exception {
            future.await();
            if (future.cause() != null) {
                sentCommand.completeExceptionally(future.cause());
                queue.remove(sentCommand);
            }
        }
    }

}
