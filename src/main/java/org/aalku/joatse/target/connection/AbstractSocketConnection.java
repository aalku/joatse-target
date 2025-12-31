package org.aalku.joatse.target.connection;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import java.util.zip.CRC32;

import org.aalku.joatse.target.JoatseSession;
import org.slf4j.Logger;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketMessage;

public abstract class AbstractSocketConnection implements TunnelConnection {
	
	static final byte PROTOCOL_VERSION = 1;
	
	protected static final int MAX_HEADER_SIZE_BYTES = 50;
	protected static final int DATA_BUFFER_SIZE = 1024 * 63;

	private final JoatseSession jSession;
	private final long socketId;
	private final CRC32 dataCRCW2T = new CRC32();
	private final CRC32 dataCRCT2W = new CRC32();
	private final CompletableFuture<Boolean> closeStatus = new CompletableFuture<>();
//	private final Consumer<Throwable> closeSession;
		
	public final ByteBuffer sendToCloudRawBuffer = allocateHeaderAndDataBuffer(); 
	
	private final Queue<Runnable> sendQueue = new LinkedBlockingDeque<>();
	private final AtomicBoolean sending = new AtomicBoolean(false);
	private final ReentrantLock sendLock = new ReentrantLock(true);

	public AbstractSocketConnection(JoatseSession manager, long socketId, Consumer<Throwable> closeSession) {
		this.jSession = manager;
		this.socketId = socketId;
//		this.closeSession = closeSession;
		this.closeStatus.whenComplete((r,e)->manager.remove(this));
	}

	protected abstract Logger getLog();

	private final CompletableFuture<Void> sendMessage(WebSocketMessage<?> message) {
		return jSession.sendMessage(message);
	}
	
	public final void receivedWsTcpClose() {
		this.close(null, true);
	}

	private void receivedWsTcpMessage(ByteBuffer buffer, long crc32Field) throws IOException {
		buffer.mark();
		dataCRCW2T.update(buffer);
		if (dataCRCW2T.getValue() != crc32Field) {
			throw new IOException("CRC32 error. Expected " + Long.toHexString(crc32Field) + " but calc was " + Long.toHexString(dataCRCW2T.getValue()));
		}
		// log.info("crc is OK: {}", Long.toHexString(crc32Field));
		buffer.reset();
		receivedBytesFromCloud(buffer);
	}
	
	private final int writeSocketHeader(ByteBuffer buffer, byte type) {
		buffer.clear();
		buffer.put(PROTOCOL_VERSION);
		buffer.put(type);
		buffer.putLong(getSocketId());
		return buffer.position();
	}

	/**
	 * 
	 * @param payload ready to be read
	 * @return
	 */
	protected CompletableFuture<Void> sendDataMessageToCloud(ByteBuffer payload) {
		sendLock.lock();
		try {
			int len = payload.remaining();
			int crcPos = writeSocketHeader(sendToCloudRawBuffer, MESSAGE_SOCKET_DATA);
			sendToCloudRawBuffer.putInt(updatedataCRCT2W(payload.array(), payload.arrayOffset() + payload.position(), payload.remaining()));
			sendToCloudRawBuffer.put(payload);
			if (sendToCloudRawBuffer.position() != crcPos + 4 + len) {
				getLog().error("Assertion error. {} != {}", sendToCloudRawBuffer.position(), crcPos + 4 + len);
				AssertionError e = new AssertionError("Assertion error of msg len and buffer pos");
				close(e, false);
				throw e;
			}
			sendToCloudRawBuffer.flip();
			return sendRawMessageToCloud(sendToCloudRawBuffer);
		} finally {
			sendLock.unlock();
		}
	}
	
	private CompletableFuture<Void> sendRawMessageToCloud(ByteBuffer buffer) {
		if (!sendLock.isHeldByCurrentThread()) {
			throw new AssertionError("!sendLock.isHeldByCurrentThread()");
		}
		// Already sending?
		boolean wasSending = sending.getAndSet(true);

		// Put on queue
		final CompletableFuture<Void> res = new CompletableFuture<Void>();
		sendQueue.add(new Runnable() {
			@Override
			public void run() {
				try {
					jSession.sendMessage((WebSocketMessage<?>) new BinaryMessage(buffer, true)).handle((r, e) -> {
						sendLock.lock();
						try {
							if (e != null) {
								getLog().error("Error sending to cloud. Will close socket: {}", e, e);
								res.completeExceptionally(e);
								close(e, false);
							} else {
								res.complete(null);
								Runnable next = sendQueue.poll();
								if (next == null) {
									sending.set(false);
								} else {
									next.run();
								}
							}
							return (Void) null;
						} finally {
							sendLock.unlock();
						}
					});
				} catch (Exception e) {
					res.completeExceptionally(e);
				}
			}
		});
		if (!wasSending) {
			// If not sending, send
			sendQueue.remove().run();
		}
		return res;
	}
	
	private int updatedataCRCT2W(byte[] array, int off, int len) {
		dataCRCT2W.update(array, off, len);
		return (int) dataCRCT2W.getValue();
	}
	
	protected final BinaryMessage newTcpSocketResponse(long socketId, boolean result) {
		ByteBuffer buffer = ByteBuffer.allocate(11);
		buffer.clear();
		buffer.put(PROTOCOL_VERSION);
		buffer.put(MESSAGE_TYPE_NEW_SOCKET);
		buffer.putLong(socketId);
		buffer.put((byte) (result ? 1 : 0));
		buffer.flip();
		return new BinaryMessage(buffer);
	}
	
	private WebSocketMessage<?> newTcpSocketCloseMessage() {
		ByteBuffer buffer = ByteBuffer.allocate(11);
		buffer.clear();
		buffer.put(PROTOCOL_VERSION);
		buffer.put(MESSAGE_SOCKET_CLOSE);
		buffer.putLong(getSocketId());
		buffer.flip();
		return new BinaryMessage(buffer);
	}

	@Override
	public CompletableFuture<Boolean> getCloseStatus() {
		return closeStatus;
	}
	
	@Deprecated
	protected final ByteBuffer allocateStandardBuffer() {
		return ByteBuffer.allocate(MAX_HEADER_SIZE_BYTES + DATA_BUFFER_SIZE);
	}
	
	protected ByteBuffer allocateHeaderAndDataBuffer() {
		return ByteBuffer.allocate(MAX_HEADER_SIZE_BYTES + DATA_BUFFER_SIZE);
	}

	protected ByteBuffer allocateDataBuffer() {
		return ByteBuffer.allocate(DATA_BUFFER_SIZE);
	}
	
	protected final CompletableFuture<Void> notifyCantConnect() {
		return sendMessage(newTcpSocketResponse(socketId, false));
	}

	protected final CompletableFuture<Void> notifyConnected() {
		return sendMessage(newTcpSocketResponse(socketId, true));
	}

	public final long getSocketId() {
		return socketId;
	}

	protected abstract void receivedBytesFromCloud(ByteBuffer buffer) throws IOException;

	@Override
	public final Runnable receivedTunnelMessage(ByteBuffer buffer, byte type) {
		if (type == MESSAGE_SOCKET_DATA) {
			try {
				long crc32Field = buffer.getInt() & 0xFFFFFFFFL;
				receivedWsTcpMessage(buffer, crc32Field); // It's ok to call it with lock
				return null;
			} catch (IOException e) {
				getLog().warn("Error sending data to TCP: {}", e, e);
				return ()->close(e, false); // Call it without lock
			}
		} else if (type == MESSAGE_SOCKET_CLOSE) {
			getLog().warn("Received socket close: {}", socketId);
			return ()->receivedWsTcpClose(); // Can be executed with lock I guess
		} else {
			RuntimeException e = new RuntimeException("Unsupported message type: " + type);
			close(e, false);
			throw e;
		}
	}
	
	public final void close() {
		close(null, null);
	}

	public final void close(Throwable e, Boolean remote) {
		getLog().debug("Closing because of ({}, {}): {}", e, remote, socketId, e);
		destroy();
		sendMessage(newTcpSocketCloseMessage()); // Tell WS
		if (e == null) {
			closeStatus.complete(remote);
		} else {
			closeStatus.completeExceptionally(e);
		}
	}
	
	protected abstract void destroy();

	public void assertClosed() {
		if (!closeStatus.isDone()) {
			AssertionError e = new AssertionError("Assertion error. Socket should be closed");
			close(e, false);
			throw e;
		}
	}
	
}
