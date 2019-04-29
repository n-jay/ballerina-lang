/*
 * Copyright (c) 2018 WSO2 Inc. (http://www.wso2.org) All Rights Reserved.
 *
 * WSO2 Inc. licenses this file to you under the Apache License,
 * Version 2.0 (the "License"); you may not use this file except
 * in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.ballerinalang.stdlib.socket.tcp;

import org.ballerinalang.connector.api.BLangConnectorSPIUtil;
import org.ballerinalang.model.types.BArrayType;
import org.ballerinalang.model.types.BTupleType;
import org.ballerinalang.model.values.BError;
import org.ballerinalang.model.values.BInteger;
import org.ballerinalang.model.values.BMap;
import org.ballerinalang.model.values.BString;
import org.ballerinalang.model.values.BValue;
import org.ballerinalang.model.values.BValueArray;
import org.ballerinalang.runtime.threadpool.BLangThreadFactory;
import org.ballerinalang.stdlib.socket.exceptions.SelectorInitializeException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.CancelledKeyException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.DatagramChannel;
import java.nio.channels.NotYetConnectedException;
import java.nio.channels.SelectableChannel;
import java.nio.channels.SelectionKey;
import java.nio.channels.Selector;
import java.nio.channels.ServerSocketChannel;
import java.nio.channels.SocketChannel;
import java.util.Arrays;
import java.util.Iterator;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

import static java.nio.channels.SelectionKey.OP_READ;
import static org.ballerinalang.model.types.BTypes.typeByte;
import static org.ballerinalang.model.types.BTypes.typeInt;
import static org.ballerinalang.stdlib.socket.SocketConstants.DEFAULT_EXPECTED_READ_LENGTH;
import static org.ballerinalang.stdlib.socket.SocketConstants.SOCKET_PACKAGE;

/**
 * This will manage the Selector instance and handle the accept, read and write operations.
 *
 * @since 0.985.0
 */
public class SelectorManager {

    private static final Logger log = LoggerFactory.getLogger(SelectorManager.class);

    private Selector selector;
    private ThreadFactory threadFactory = new BLangThreadFactory("socket-selector");
    private ExecutorService executor = null;
    private boolean running = false;
    private boolean executing = true;
    private ConcurrentLinkedQueue<ChannelRegisterCallback> registerPendingSockets = new ConcurrentLinkedQueue<>();
    private ConcurrentLinkedQueue<Integer> readReadySockets = new ConcurrentLinkedQueue<>();
    private final Object startStopLock = new Object();
    private static final BTupleType readTupleType = new BTupleType(Arrays.asList(new BArrayType(typeByte), typeInt));

    private SelectorManager() throws IOException {
        selector = Selector.open();
    }

    /**
     * This will use to hold the SelectorManager singleton object.
     */
    private static class SelectorManagerHolder {
        private static SelectorManager manager;
        static {
            try {
                manager = new SelectorManager();
            } catch (IOException e) {
                throw new SelectorInitializeException("Unable to initialize the selector", e);
            }
        }
    }

    /**
     * This method will return SelectorManager singleton instance.
     *
     * @return {@link SelectorManager} instance
     * @throws SelectorInitializeException when unable to open a selector
     */
    public static SelectorManager getInstance() throws SelectorInitializeException {
        return SelectorManagerHolder.manager;
    }

    /**
     * Add channel to register pending socket queue. Socket registration has to be happen in the same thread
     * that selector loop execute.
     *
     * @param callback A {@link ChannelRegisterCallback} instance which contains the resources,
     *                      packageInfo and A {@link SelectableChannel}.
     */
    public void registerChannel(ChannelRegisterCallback callback) {
        registerPendingSockets.add(callback);
        selector.wakeup();
    }

    /**
     * Unregister the given client channel from the selector instance.
     *
     * @param channel {@link SelectableChannel} that about to unregister.
     */
    public void unRegisterChannel(SelectableChannel channel) {
        final SelectionKey selectionKey = channel.keyFor(selector);
        if (selectionKey != null) {
            selectionKey.cancel();
        }
    }

    /**
     * Adding onReadReady finish notification to the queue and wakeup the selector.
     *
     * @param socketHashCode hashCode of the read ready socket.
     */
    void invokePendingReadReadyResources(int socketHashCode) {
        readReadySockets.add(socketHashCode);
        selector.wakeup();
    }

    /**
     * Start the selector loop.
     */
    public void start() {
        synchronized (startStopLock) {
            if (running) {
                return;
            }
            if (executor == null || executor.isTerminated()) {
                executor = Executors.newSingleThreadExecutor(threadFactory);
            }
            running = true;
            executing = true;
            executor.execute(this::execute);
        }
    }

    private void execute() {
        while (executing) {
            try {
                registerChannels();
                invokeReadReadyResources();
                if (selector.select() == 0) {
                    continue;
                }
                Iterator<SelectionKey> keyIterator = selector.selectedKeys().iterator();
                while (keyIterator.hasNext()) {
                    SelectionKey key = keyIterator.next();
                    keyIterator.remove();
                    performAction(key);
                }
            } catch (Throwable e) {
                log.error("An error occurred in selector loop: " + e.getMessage(), e);
            }
        }
    }

    /*
    Channel registration has to be done in the same thread that selector loops runs.
     */
    private void registerChannels() {
        ChannelRegisterCallback channelRegisterCallback;
        while ((channelRegisterCallback = registerPendingSockets.poll()) != null) {
            SocketService socketService = channelRegisterCallback.getSocketService();
            try {
                socketService.getSocketChannel()
                        .register(selector, channelRegisterCallback.getInitialInterest(), socketService);
            } catch (ClosedChannelException e) {
                channelRegisterCallback.notifyFailure("Socket already closed");
                continue;
            }
            // Notification needs to happen to the client connection in the socket server only if the client has
            // a callback service.
            boolean serviceAttached = (socketService.getResources() != null
                    && channelRegisterCallback.getInitialInterest() == OP_READ);
            channelRegisterCallback.notifyRegister(serviceAttached);
        }
    }

    private void invokeReadReadyResources() {
        final Iterator<Integer> iterator = readReadySockets.iterator();
        while (iterator.hasNext()) {
            Integer socketHashCode = iterator.next();
            // Removing an entry from the readReadySockets queue is fine. This will cleanup the last entry that add due
            // execution of TCPSocketReadCallback.
            final SocketReader socketReader = ReadReadySocketMap.getInstance().get(socketHashCode);
            // SocketReader can be null if there is no new read ready notification.
            if (socketReader == null) {
                continue;
            }
            iterator.remove();
            final SocketService socketService = socketReader.getSocketService();
            invokeReadReadyResource(socketService);
        }
    }

    private void performAction(SelectionKey key) {
        if (!key.isValid()) {
            key.cancel();
        } else if (key.isAcceptable()) {
            onAccept(key);
        } else if (key.isReadable()) {
            onReadReady(key);
        }
    }

    private void onAccept(SelectionKey key) {
        SocketService socketService = (SocketService) key.attachment();
        ServerSocketChannel server = (ServerSocketChannel) socketService.getSocketChannel();
        try {
            SocketChannel client = server.accept();
            client.configureBlocking(false);
            // Creating a new SocketService instance with the newly accepted client.
            // We don't need the ServerSocketChannel in here since we have all the necessary resources.
            SocketService clientSocketService = new SocketService(client, socketService.getResources());
            // Registering the channel against the selector directly without going through the queue,
            // since we are in same thread.
            client.register(selector, OP_READ, clientSocketService);
            SelectorDispatcher.invokeOnConnect(clientSocketService);
        } catch (ClosedByInterruptException e) {
            SelectorDispatcher.invokeOnError(new SocketService(socketService.getResources()),
                    "Client accept interrupt by another process");
        } catch (AsynchronousCloseException e) {
            SelectorDispatcher
                    .invokeOnError(new SocketService(socketService.getResources()), "Client closed by another process");
        } catch (ClosedChannelException e) {
            SelectorDispatcher
                    .invokeOnError(new SocketService(socketService.getResources()), "Client is already closed");
        } catch (IOException e) {
            log.error("An error occurred while accepting new client", e);
            SelectorDispatcher
                    .invokeOnError(new SocketService(socketService.getResources()), "Unable to accept a new client");
        }
    }

    private void onReadReady(SelectionKey key) {
        SocketService socketService = (SocketService) key.attachment();
        // Remove further interest on future read ready requests until this one is served.
        // This will prevent the busy loop.
        key.interestOps(0);
        // Add to the read ready queue. The content will be read through the caller->read action.
        ReadReadySocketMap.getInstance().add(new SocketReader(socketService, key));
        invokeRead(key.channel().hashCode(), socketService.getResources() != null);
    }

    /**
     * Perform the read operation for the given socket. This will either read data from the socket channel or dispatch
     * to the onReadReady resource if resource's lock available.
     *
     * @param socketHashId socket hash id
     * @param clientServiceAttached whether client callback service attached or not
     */
    public void invokeRead(int socketHashId, boolean clientServiceAttached) {
        // Check whether there is any caller->read pending action and read ready socket.
        ReadPendingSocketMap readPendingSocketMap = ReadPendingSocketMap.getInstance();
        if (readPendingSocketMap.isPending(socketHashId)) {
            // Lock the ReadPendingCallback instance. This will prevent duplicate invocation that happen from both
            // read action and selector manager sides.
            synchronized (readPendingSocketMap.get(socketHashId)) {
                ReadReadySocketMap readReadySocketMap = ReadReadySocketMap.getInstance();
                if (readReadySocketMap.isReadReady(socketHashId)) {
                    SocketReader socketReader = readReadySocketMap.remove(socketHashId);
                    ReadPendingCallback callback = readPendingSocketMap.remove(socketHashId);
                    // Read ready socket available.
                    SelectableChannel channel = socketReader.getSocketService().getSocketChannel();
                    if (channel instanceof SocketChannel) {
                        readTcpSocket(socketReader, callback);
                    } else if (channel instanceof DatagramChannel) {
                        readUdpSocket(socketReader, callback);
                    }
                }
            }
            // If the read pending socket not available then do nothing. Above will be invoked once read ready
            // socket is connected.
        } else if (clientServiceAttached) {
            // No caller->read pending actions hence try to dispatch to onReadReady resource if read ready available.
            final SocketReader socketReader = ReadReadySocketMap.getInstance().get(socketHashId);
            invokeReadReadyResource(socketReader.getSocketService());
        }
    }

    private void readUdpSocket(SocketReader socketReader, ReadPendingCallback callback) {
        DatagramChannel channel = (DatagramChannel) socketReader.getSocketService().getSocketChannel();
        try {
            ByteBuffer buffer = createBuffer(callback, channel);
            final InetSocketAddress remoteAddress = (InetSocketAddress) channel.receive(buffer);
            final int bufferPosition = buffer.position();
            callback.updateCurrentLength(bufferPosition);
            // Re-register for read ready events.
            socketReader.getSelectionKey().interestOps(OP_READ);
            selector.wakeup();
            if (callback.getExpectedLength() != DEFAULT_EXPECTED_READ_LENGTH) {
                if (callback.getBuffer() == null) {
                    callback.setBuffer(ByteBuffer.allocate(buffer.capacity()));
                }
                buffer.flip();
                callback.getBuffer().put(buffer);
            }
            if (callback.getExpectedLength() != DEFAULT_EXPECTED_READ_LENGTH && callback.getExpectedLength() != callback
                    .getCurrentLength()) {
                ReadPendingSocketMap.getInstance().add(channel.hashCode(), callback);
                invokeRead(channel.hashCode(), false);
                return;
            }
            byte[] bytes = SocketUtils
                    .getByteArrayFromByteBuffer(callback.getBuffer() == null ? buffer : callback.getBuffer());
            callback.getContext().setReturnValues(createUdpSocketReturnValue(callback, bytes, remoteAddress));
            callback.getCallback().notifySuccess();
        } catch (CancelledKeyException | ClosedChannelException e) {
            processError(callback, "Connection closed");
        } catch (IOException e) {
            log.error("Error while data receive.", e);
            processError(callback, e.getMessage());
        } catch (Throwable e) {
            log.error(e.getMessage(), e);
            BError socketError = SocketUtils
                    .createSocketError(callback.getContext(), "Error while on receiveFrom operation");
            callback.getContext().setReturnValues(socketError);
            callback.getCallback().notifyFailure(socketError);
        }
    }

    private void readTcpSocket(SocketReader socketReader, ReadPendingCallback callback) {
        SocketChannel socketChannel = (SocketChannel) socketReader.getSocketService().getSocketChannel();
        try {
            ByteBuffer buffer = createBuffer(callback, socketChannel);
            int read = socketChannel.read(buffer);
            if (read < 0) {
                SelectorManager.getInstance().unRegisterChannel(socketChannel);
            } else {
                callback.updateCurrentLength(read);
                // Re-register for read ready events.
                socketReader.getSelectionKey().interestOps(OP_READ);
                selector.wakeup();
                if (callback.getBuffer() == null) {
                    callback.setBuffer(ByteBuffer.allocate(buffer.capacity()));
                }
                buffer.flip();
                callback.getBuffer().put(buffer);
                if (callback.getExpectedLength() != DEFAULT_EXPECTED_READ_LENGTH
                        && callback.getExpectedLength() != callback.getCurrentLength()) {
                    ReadPendingSocketMap.getInstance().add(socketChannel.hashCode(), callback);
                    invokeRead(socketChannel.hashCode(), socketReader.getSocketService().getResources() != null);
                    return;
                }
            }
            byte[] bytes = SocketUtils
                    .getByteArrayFromByteBuffer(callback.getBuffer() == null ? buffer : callback.getBuffer());
            callback.getContext().setReturnValues(createTcpSocketReturnValue(callback, bytes));
            callback.getCallback().notifySuccess();
        } catch (NotYetConnectedException e) {
            processError(callback, "Connection not yet connected");
        } catch (CancelledKeyException | ClosedChannelException e) {
            processError(callback, "Connection closed");
        } catch (IOException e) {
            log.error("Error while read.", e);
            processError(callback, e.getMessage());
        } catch (Throwable e) {
            log.error(e.getMessage(), e);
            BError socketError = SocketUtils.createSocketError(callback.getContext(), "Error while on read operation");
            callback.getContext().setReturnValues(socketError);
            callback.getCallback().notifyFailure(socketError);
        }
    }

    private void processError(ReadPendingCallback callback, String msg) {
        BError socketError = SocketUtils.createSocketError(callback.getContext(), msg);
        callback.getContext().setReturnValues(socketError);
        callback.getCallback().notifySuccess();
    }

    private BValueArray createTcpSocketReturnValue(ReadPendingCallback callback, byte[] bytes) {
        BValueArray contentTuple = new BValueArray(readTupleType);
        contentTuple.add(0, new BValueArray(bytes));
        contentTuple.add(1, new BInteger(callback.getCurrentLength()));
        return contentTuple;
    }

    private BValueArray createUdpSocketReturnValue(ReadPendingCallback callback, byte[] bytes,
            InetSocketAddress remoteAddress) {
        BMap<String, BValue> address = BLangConnectorSPIUtil
                .createBStruct(callback.getContext(), SOCKET_PACKAGE, "Address");
        address.put("port", new BInteger(remoteAddress.getPort()));
        address.put("host", new BString(remoteAddress.getHostName()));
        BTupleType receiveFromTupleType = new BTupleType(
                Arrays.asList(new BArrayType(typeByte), typeInt, address.getType()));
        BValueArray contentTuple = new BValueArray(receiveFromTupleType);
        contentTuple.add(0, new BValueArray(bytes));
        contentTuple.add(1, new BInteger(callback.getCurrentLength()));
        contentTuple.add(2, address);
        return contentTuple;
    }

    private ByteBuffer createBuffer(ReadPendingCallback callback, int osBufferSize) {
        ByteBuffer buffer;
        // If the length is not specified in the read action then create a byte buffer to match the size of
        // the receiver buffer.
        if (callback.getExpectedLength() == DEFAULT_EXPECTED_READ_LENGTH) {
            buffer = ByteBuffer.allocate(osBufferSize);
        } else {
            int newBufferSize = callback.getExpectedLength() - callback.getCurrentLength();
            buffer = ByteBuffer.allocate(newBufferSize);
        }
        return buffer;
    }

    private ByteBuffer createBuffer(ReadPendingCallback callback, SocketChannel socketChannel) throws SocketException {
        return createBuffer(callback, socketChannel.socket().getReceiveBufferSize());
    }

    private ByteBuffer createBuffer(ReadPendingCallback callback, DatagramChannel socketChannel)
            throws SocketException {
        return createBuffer(callback, socketChannel.socket().getReceiveBufferSize());
    }

    private void invokeReadReadyResource(SocketService socketService) {
        // If lock is not available then already inside the resource.
        // If lock is available then invoke the resource dispatch.
        if (socketService.getResourceLock().tryAcquire()) {
            SelectorDispatcher.invokeReadReady(socketService);
        }
    }

    /**
     * Stop the selector loop.
     */
    public void stop() {
        synchronized (startStopLock) {
            try {
                log.debug("Stopping the selector loop.");
                executing = false;
                running = false;
                selector.wakeup();
                SocketUtils.shutdownExecutor(executor);
            } catch (Throwable e) {
                log.error("Error occurred while stopping the selector loop: " + e.getMessage(), e);
            }
        }
    }
}
