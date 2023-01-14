package org.aalku.joatse.target.connection;

import java.io.IOException;
import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.zip.CRC32;

import org.aalku.joatse.target.JoatseSession;
import org.aalku.joatse.target.tools.io.IOTools;
import org.slf4j.Logger;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketMessage;

public abstract class AbstractTunnelTcpConnection {

	static final byte PROTOCOL_VERSION = 1;
	
	public static final byte MESSAGE_TYPE_NEW_SOCKET = 1;
	private static final byte MESSAGE_SOCKET_DATA = 2;
	private static final byte MESSAGE_SOCKET_CLOSE = 3;
	
	public static final Set<Byte> messageTypesHandled = new HashSet<>(Arrays.asList(MESSAGE_SOCKET_DATA, MESSAGE_SOCKET_CLOSE));
	
	private static final int MAX_HEADER_SIZE_BYTES = 50;
	private static final int DATA_BUFFER_SIZE = 1024 * 63;

	private static final int TCP_HEADER_LEN = 14;

	private Logger log = getLog();

	private final JoatseSession jSession;
	protected final long socketId;
	private final CRC32 dataCRCW2T = new CRC32();
	private final CRC32 dataCRCT2W = new CRC32();
	private final CompletableFuture<Boolean> closeStatus = new CompletableFuture<>();
	protected final AtomicReference<AsynchronousSocketChannel> tcpRef;

	protected final Consumer<Throwable> closeSession;

	public AbstractTunnelTcpConnection(JoatseSession manager, long socketId,
			Consumer<Throwable> closeSession) {
		// TODO use closeSession
		this.jSession = manager;
		this.socketId = socketId;
		this.closeSession = closeSession;
		this.tcpRef = new AtomicReference<AsynchronousSocketChannel>();
		this.closeStatus.whenComplete((r,e)->manager.remove(this));
	}

	protected abstract Logger getLog();

	protected CompletableFuture<Void> sendMessage(WebSocketMessage<?> message) {
		return jSession.sendMessage(message);
	}
	
	public void receivedWsTcpClose() {
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
		receivedWsBytes(buffer);
	}

	/**
	 * Recursively writes all the buffer to tcp.
	 */
	protected CompletableFuture<Integer> tcpWrite(ByteBuffer buffer) {
		CompletableFuture<Integer> res = new CompletableFuture<Integer>();
		AsynchronousSocketChannel channel = tcpRef.get();
		channel.write(buffer, null, new CompletionHandler<Integer, Void>() {
			@Override
			public void completed(Integer result, Void attachment) {
				// log.info("written to tcp: {}", IOTools.toString(buffer, p, result));
				if (buffer.hasRemaining()) {
					tcpWrite(buffer) // Recursively write the rest
					.thenAccept(n -> res.complete(n + result)) // Then complete with all the written
					.exceptionally(e -> { // Or fail
						res.completeExceptionally(e);
						return null;
					});
				} else {
					res.complete(result);
				}
			}
			@Override
			public void failed(Throwable exc, Void attachment) {
				if (exc instanceof AsynchronousCloseException) {
					log.error("tcp write fail because the socket was closed");
					close(null, true);
					res.complete(0);
				} else {
					res.completeExceptionally(exc);
				}
			}
		});
		return res;
	}
	
	private int writeSocketHeader(ByteBuffer buffer, byte type) {
		buffer.clear();
		buffer.put(PROTOCOL_VERSION);
		buffer.put(type);
		buffer.putLong(getSocketId());
		return buffer.position();
	}
	
	private void tcpToWs(ByteBuffer buffer) {
		/*
		 * We save some space for the header when reading from TCP to the buffer
		 */
		readyBufferForTcpToCloud(buffer);
		tcpRead(buffer).thenAccept(bytesRead->{
			sendSocketDataToWs(buffer, bytesRead);
		}).exceptionally(e->{
			close(e, false);
			return null;
		});
	}

	private void readyBufferForTcpToCloud(ByteBuffer buffer) {
		buffer.clear();
		buffer.position(TCP_HEADER_LEN);
	}

	/**
	 * Send a buffer of data to the cloud as part of a socket.
	 * 
	 * @param buffer is buffer with empty space for the header (TCP_HEADER_LEN) and then the data already in place. Position must point after data.
	 * @param bytes is number of data bytes (after header) 
	 */
	private void sendSocketDataToWs(ByteBuffer buffer, Integer bytes) {
		// Save buffer position (for flip)
		int pos = buffer.position();
		if (bytes < 0) {
			close();
			return;
		}
		// log.info("sendSocketDataToWs: {}", IOTools.toString(buffer, TCP_HEADER_LEN, bytesRead));
		if (pos - TCP_HEADER_LEN != bytes) {
			log.error("bytes assertion error. {} != {}", pos - TCP_HEADER_LEN, bytes);
			close(new AssertionError("bytesRead assertion error"), false);
		}
		// Write header at 0
		buffer.position(0);
		writeSocketHeader(buffer, MESSAGE_SOCKET_DATA);
		// Update CRC calc skipping the header
		dataCRCT2W.update(buffer.array(), buffer.arrayOffset() + TCP_HEADER_LEN, pos - TCP_HEADER_LEN);
		buffer.putInt((int) dataCRCT2W.getValue());
		if (buffer.position() != TCP_HEADER_LEN) {
			log.error("TCP_HEADER_LEN assertion error. {} != {}", buffer.position(), TCP_HEADER_LEN);
			close(new AssertionError("TCP_HEADER_LEN assertion error"), false);
		}
		// Back to position, and flip for reading
		buffer.position(pos);
		buffer.flip();
		sendMessage(new BinaryMessage(buffer, true)).whenCompleteAsync((x, e)->{
			if (e != null) {
				close(e, false);
			} else {
				// log.info("CRC32 = {}", Integer.toHexString((int)dataCRCT2W.getValue()) );
				tcpToWs(buffer);
			}
		});
	}
	
	/**
	 * Sends the whole dataBuffer to a socket in cloud. It ignores position and
	 * limit and sends from 0 to end.
	 */
	protected void sendSocketDataToWs(ByteBuffer dataBuffer) {
		ByteBuffer outBuffer = allocateStandardBuffer();
		readyBufferForTcpToCloud(outBuffer);
		outBuffer.put((ByteBuffer)(dataBuffer.duplicate().position(0).limit(dataBuffer.capacity())));
		sendSocketDataToWs(outBuffer, dataBuffer.capacity());
	}
	
	private CompletableFuture<Integer> tcpRead(ByteBuffer readBuffer) {
		AsynchronousSocketChannel channel = this.tcpRef.get();
		CompletableFuture<Integer> res = new CompletableFuture<Integer>();
		channel.read(readBuffer, null, new CompletionHandler<Integer, Void>() {
			@Override
			public void completed(Integer result, Void attachment) {
				res.complete(result);
			}
			@Override
			public void failed(Throwable exc, Void attachment) {
				if (exc instanceof AsynchronousCloseException) {
					log.error("tcp read fail because the socket was closed");
					close(null, true);
					res.complete(0);
				} else {
					res.completeExceptionally(exc);
				}
			}
		});
		return res;
	}

	protected BinaryMessage newTcpSocketResponse(long socketId, boolean result) {
		ByteBuffer buffer = ByteBuffer.allocate(11);
		buffer.clear();
		buffer.put(PROTOCOL_VERSION);
		buffer.put(MESSAGE_TYPE_NEW_SOCKET);
		buffer.putLong(socketId);
		buffer.put((byte) (result ? 1 : 0));
		buffer.flip();
		return new BinaryMessage(buffer);
	}
	
	protected static CompletableFuture<AsynchronousSocketChannel> tcpConnectToTarget(SocketAddress targetAddress) {
		CompletableFuture<AsynchronousSocketChannel> res = new CompletableFuture<AsynchronousSocketChannel>();
		try {
			AsynchronousSocketChannel cs = AsynchronousSocketChannel.open();
			cs.connect(targetAddress, null, new CompletionHandler<Void, Void>() {
				public void completed(Void result, Void a) {
					res.complete(cs);
				}
				public void failed(Throwable e, Void a) {
					// Cleanup only the resource that will not return, then tell the caller
					IOTools.runFailable(()->cs.close());
					res.completeExceptionally(e);
				}
			});
		} catch (Exception e) {
			res.completeExceptionally(e);
		}
		return res;
	}

	public void close() {
		close(null, null);
	}

	public void close(Throwable e, Boolean remote) {
		IOTools.runFailable(()->tcpRef.get().close());
		sendMessage(newTcpSocketCloseMessage()); // Tell WS
		if (e == null) {
			closeStatus.complete(remote);
		} else {
			closeStatus.completeExceptionally(e);
		}
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

	public CompletableFuture<Boolean> getCloseStatus() {
		return closeStatus;
	}

	public void assertClosed() {
		if (tcpRef.get().isOpen()) { // Must be closed
			IOTools.runFailable(()->tcpRef.get().close());
			throw new AssertionError("AbstractTunnelTcpConnection.JoatseSession.remove(c) assertion error");
		}
	}

	public Runnable receivedMessage(ByteBuffer buffer, byte type) {
		if (type == MESSAGE_SOCKET_DATA) {
			try {
				long crc32Field = buffer.getInt() & 0xFFFFFFFFL;
				receivedWsTcpMessage(buffer, crc32Field); // It's ok to call it with lock
				return null;
			} catch (IOException e) {
				log.warn("Error sending data to TCP: {}", e, e);
				return ()->close(e, false); // Call it without lock
			}
		} else if (type == MESSAGE_SOCKET_CLOSE) {
			return ()->receivedWsTcpClose(); // Can be executed with lock I guess
		} else {
			throw new RuntimeException("Unsupported message type: " + type);
		}
	}

	public void runSocket() {
		ByteBuffer buffer = allocateStandardBuffer();
		tcpToWs(buffer); // start copying from WS to TCP
	}

	protected ByteBuffer allocateStandardBuffer() {
		return ByteBuffer.allocate(MAX_HEADER_SIZE_BYTES + DATA_BUFFER_SIZE);
	}
	
	protected CompletableFuture<Void> notifyCantConnect(long socketId) {
		return sendMessage(newTcpSocketResponse(socketId, false));
	}

	protected CompletableFuture<Void> notifyConnected(long socketId) {
		return sendMessage(newTcpSocketResponse(socketId, true));
	}

	public long getSocketId() {
		return socketId;
	}

	protected abstract void receivedWsBytes(ByteBuffer buffer) throws IOException;

}
