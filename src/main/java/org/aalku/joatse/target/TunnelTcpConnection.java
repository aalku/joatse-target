package org.aalku.joatse.target;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.zip.CRC32;

import org.aalku.joatse.target.tools.io.IOTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketMessage;

public class TunnelTcpConnection {

	static final byte PROTOCOL_VERSION = 1;
	
	static final byte MESSAGE_TYPE_NEW_TCP_SOCKET = 1;
	private static final byte MESSAGE_TCP_DATA = 2;
	private static final byte MESSAGE_TCP_CLOSE = 3;
	
	public static final Set<Byte> messageTypesHandled = new HashSet<>(Arrays.asList(MESSAGE_TCP_DATA, MESSAGE_TCP_CLOSE));
	
	private static final int MAX_HEADER_SIZE_BYTES = 50;
	private static final int DATA_BUFFER_SIZE = 1024 * 63;

	private Logger log = LoggerFactory.getLogger(TunnelTcpConnection.class);

	private JoatseSession jSession;
	private InetSocketAddress targetAddress;
	long socketId;
	private CRC32 dataCRCW2T = new CRC32();
	private CRC32 dataCRCT2W = new CRC32();
	private CompletableFuture<Boolean> closeStatus = new CompletableFuture<>();
	private AtomicReference<AsynchronousSocketChannel> tcpRef;

	public TunnelTcpConnection(JoatseSession manager, InetSocketAddress targetAddress, long socketId,
			Consumer<Throwable> closeSession) {
		this.jSession = manager;
		this.targetAddress = targetAddress;
		this.socketId = socketId;
		this.tcpRef = new AtomicReference<AsynchronousSocketChannel>();
		this.closeStatus.whenComplete((r,e)->manager.remove(this));
		manager.add(this);
		
		/**
		 * Was newTcpSocketResponse(portId, true) sent?
		 */
		AtomicBoolean newTcpSocketMessageSent = new AtomicBoolean(false);
		
		tcpConnectToTarget().exceptionally(e->{
			throw new RuntimeException("Exception creating connection to "
					+ targetAddress.getHostString() + ":" + targetAddress.getPort(), e);
		}).thenCompose((Function<AsynchronousSocketChannel, CompletableFuture<AsynchronousSocketChannel>>)(tcp)->{
			this.tcpRef.set(tcp);
			CompletableFuture<AsynchronousSocketChannel> res = new CompletableFuture<AsynchronousSocketChannel>();
			sendMessage(newTcpSocketResponse(socketId, true)).handle((x, e)->{
				if (e != null) {
					res.completeExceptionally(e);
				} else {
					newTcpSocketMessageSent.set(true);
					res.complete(tcp);
				}
				return null;
			});
			return res;
		}).thenCompose((Function<AsynchronousSocketChannel, CompletableFuture<Void>>)tcp->{
			ByteBuffer buffer = ByteBuffer.allocate(MAX_HEADER_SIZE_BYTES + DATA_BUFFER_SIZE);
			tcpToWs(buffer); // start copying from WS to TCP
			return CompletableFuture.completedFuture(null);
		}).exceptionally(e->{
			if (newTcpSocketMessageSent.get()) {
				log.warn("Aborting tcp tunnel {} just after creation: " + e, socketId);
				this.close(e, true);
				return null;
			} else {
				log.warn("Aborting tcp tunnel {} during creation: " + e, socketId);
				// Do not call this.close() as that's for stablished connections
				IOTools.runFailable(()->this.tcpRef.get().close());
				sendMessage(newTcpSocketResponse(socketId, false)).handle((x,e2)->{
					closeStatus.completeExceptionally(e);
					return null;
				});
				return null;
			}
		});
	}
	
	private CompletableFuture<Void> sendMessage(WebSocketMessage<?> message) {
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
		try {
			tcpWrite(buffer).get(); // This is blocking to ensure write order. TODO prepare an async version
		} catch (InterruptedException | ExecutionException e) {
			throw new IOException("Error writting to tcp: " + e, e);
		}
	}
	
	/**
	 * Recursively writes all the buffer to tcp.
	 */
	private CompletableFuture<Integer> tcpWrite(ByteBuffer buffer) {
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
		buffer.putLong(socketId);
		return buffer.position();
	}
	
	private void tcpToWs(ByteBuffer buffer) {
		/*
		 * We save some space for the header when reading from TCP to the buffer
		 */
		final int headerLen = 14;
		buffer.clear();
		buffer.position(headerLen);
		tcpRead(buffer).thenAccept(bytesRead->{
			// Save buffer position (for flip)
			int pos = buffer.position();
			if (bytesRead < 0) {
				close();
				return;
			}
			// log.info("tcpToWs: {}", IOTools.toString(buffer, headerLen, bytesRead));
			if (pos - headerLen != bytesRead) {
				log.error("bytesRead assertion error. {} != {}", pos - headerLen, bytesRead);
				close(new AssertionError("bytesRead assertion error"), false);
			}
			// Write header at 0
			buffer.position(0);
			writeSocketHeader(buffer, MESSAGE_TCP_DATA);
			// Update CRC calc skipping the header
			dataCRCT2W.update(buffer.array(), buffer.arrayOffset() + headerLen, pos - headerLen);
			buffer.putInt((int) dataCRCT2W.getValue());
			if (buffer.position() != headerLen) {
				log.error("headerLen assertion error. {} != {}", buffer.position(), headerLen);
				close(new AssertionError("headerLen assertion error"), false);
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
		}).exceptionally(e->{
			close(e, false);
			return null;
		});
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

	private BinaryMessage newTcpSocketResponse(long socketId, boolean result) {
		ByteBuffer buffer = ByteBuffer.allocate(11);
		buffer.clear();
		buffer.put(PROTOCOL_VERSION);
		buffer.put(MESSAGE_TYPE_NEW_TCP_SOCKET);
		buffer.putLong(socketId);
		buffer.put((byte) (result ? 1 : 0));
		buffer.flip();
		return new BinaryMessage(buffer);
	}
	
	private CompletableFuture<AsynchronousSocketChannel> tcpConnectToTarget() {
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

	void close(Throwable e, Boolean remote) {
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
		buffer.put(MESSAGE_TCP_CLOSE);
		buffer.putLong(socketId);
		buffer.flip();
		return new BinaryMessage(buffer);
	}

	public CompletableFuture<Boolean> getCloseStatus() {
		return closeStatus;
	}

	public void assertClosed() {
		if (tcpRef.get().isOpen()) { // Must be closed
			IOTools.runFailable(()->tcpRef.get().close());
			throw new AssertionError("TunnelTcpConnection.JoatseSession.remove(c) assertion error");
		}
	}

	public Runnable receivedMessage(ByteBuffer buffer, byte type) {
		if (type == MESSAGE_TCP_DATA) {
			try {
				long crc32Field = buffer.getInt() & 0xFFFFFFFFL;
				receivedWsTcpMessage(buffer, crc32Field); // It's ok to call it with lock
				return null;
			} catch (IOException e) {
				log.warn("Error sending data to TCP: {}", e, e);
				return ()->close(e, false); // Call it without lock
			}
		} else if (type == MESSAGE_TCP_CLOSE) {
			return ()->receivedWsTcpClose(); // Can be executed with lock I guess
		} else {
			throw new RuntimeException("Unsupported message type: " + type);
		}
	}
}
