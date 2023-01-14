package org.aalku.joatse.target.connection;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.SocketTimeoutException;
import java.net.StandardSocketOptions;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.aalku.joatse.target.tools.io.IOTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Socks5Proxy {
	
	private static final byte REP_CONNECTION_REFUSED = 5;

	private static final byte REP_TARGET_NOT_ALLOWED = 2;

	private static final byte REP_SUCCESS = 0;

	private static final int BUFFER_CAPACITY = 1024*64;
	
	private enum State { NEW, CONNECTED, CLOSED, IDLE, CONNECTING };
	
	private AtomicReference<Socks5Proxy.State> state = new AtomicReference<Socks5Proxy.State>(State.NEW);
	
	/**
	 * For exclusive use of receivedWsBytes() on connect phase
	 */
	private AtomicReference<ByteBuffer> receivedWsBytesBuffer = new AtomicReference<>(ByteBuffer.allocate(BUFFER_CAPACITY));


	private static Logger log = LoggerFactory.getLogger(Socks5Proxy.class);

	private final Collection<String> allowedAddressess;
	
	private final Consumer<String> closedEvent;

	private final Consumer<ByteBuffer> senderToWs;
	
	private final CompletableFuture<AsynchronousSocketChannel> result = new CompletableFuture<>();
	
	private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1);

	private long tcpConnectTimeoutSeconds = 20;

	private long socks5TimeoutSeconds = 30;

	private ScheduledFuture<?> socks5timeoutTask;

	public Socks5Proxy(Collection<String> allowedAddressess, Consumer<String> closedEvent, Consumer<ByteBuffer> senderToWs) {
		this.allowedAddressess = allowedAddressess;
		this.closedEvent = closedEvent;
		this.senderToWs = senderToWs;
		// handle timeout
		this.socks5timeoutTask = scheduler.schedule(()->{
			State state = this.state.get();
			if (state != State.CONNECTED) {
				close("Socks5 timeout");
			}
		}, this.socks5TimeoutSeconds, TimeUnit.SECONDS);
	}

	public void receivedWsBytes(ByteBuffer buffer) {
		/*
		 * Implementation notes:
		 * 
		 * First we need to know if we have a whole message or we need to wait more, and
		 * we only fully decode when we know we do, so decoding doesn't have to
		 * handle incomplete messages.
		 * 
		 * Decoding depends on what we expect and that is stored in "state".
		 */
		State state = this.state.get();
		if (state == State.CLOSED) {
			log.warn("Received bytes for a closed socks5 tunnel connection: " + buffer.remaining());
		} else if (state == State.CONNECTED) {
			throw new IllegalStateException("Don't pass data messagess to proxy once the socket is connected");
		} else {
			// Connecting
			ByteBuffer receivedWsBytesBuffer = this.receivedWsBytesBuffer.get();
			receivedWsBytesBuffer.put(buffer); // It must fit. While connecting messages are small.
			if (!checkVersion()) {
				return; // Already closed
			}
			int len = receivedWsBytesBuffer.position();
			if (state == State.NEW) {
				if (len < 2) {
					return; // Not enough bytes
				}
				int mCount = receivedWsBytesBuffer.get(1) & 0xFF;
				if (len < 2 + mCount) {
					return; // Not enough bytes
				}
				List<Integer> methods = new ArrayList<>(mCount);
				for (int i = 0; i < mCount; i++) {
					methods.add(receivedWsBytesBuffer.get(2 + i) & 0xFF);
				}
				ByteBuffer response = ByteBuffer.allocate(2);
				response.put((byte) 5); // Version
				if (!methods.contains(0)) {
					response.put((byte) 0xFF); // Refused
					senderToWs.accept(response);
					close("Unauthenticated method not supported by client");
					return;
				}
				response.put((byte) 0); // Unauthenticated
				answerAndUpdateState(response, 2 + mCount, State.IDLE);
				return;
			} else if (state == State.IDLE) {
				if (len < 5) {
					return; // Not enough bytes
				}
				int aType = receivedWsBytesBuffer.get(3) & 0xFF;
				final int messageLen;
				if (aType == 1) { // IPV4
					messageLen = 4 + 4 + 2;
				} else if (aType == 3) { // DOMAIN
					messageLen = 4 + 1 + (receivedWsBytesBuffer.get(4) & 0xFF) + 2;
				} else if (aType == 4) { // IPV6
					messageLen = 4 + 16 + 2;
				} else {
					sendCommandResponse((byte) 0x08);
					close("Unknown address type: " + aType);
					return;
				}
				if (len < messageLen) {
					return; // Not enough bytes
				}
				int cmd = receivedWsBytesBuffer.get(1) & 0xFF;
				int rsv = receivedWsBytesBuffer.get(2) & 0xFF;
				if (rsv != 0) {
					sendCommandResponse((byte) 0x01);
					close("Protocol error: RSV != 0 : " + rsv);
					return;
				}
				final InetSocketAddress target;
				if (aType == 1) { // IPV4
					byte[] addr = new byte[4];
					for (int i = 0; i < addr.length; i++) {
						addr[i] = receivedWsBytesBuffer.get(4 + i);
					}
					target = new InetSocketAddress(IOTools.runUnchecked(() -> Inet4Address.getByAddress(addr)),
							(receivedWsBytesBuffer.get(4 + addr.length) & 0xFF) * 256
									+ (receivedWsBytesBuffer.get(4 + addr.length + 1) & 0xFF));
				} else if (aType == 3) { // DOMAIN
					int clen = receivedWsBytesBuffer.get(4) & 0xFF;
					StringBuilder sb = new StringBuilder();
					for (int i = 0; i < clen; i++) {
						sb.append((char)receivedWsBytesBuffer.get(4 + 1 + i));
					}
					target = new InetSocketAddress(sb.toString(), (receivedWsBytesBuffer.get(4 + 1 + clen) & 0xFF) * 256
							+ (receivedWsBytesBuffer.get(4 + 1 + clen + 1) & 0xFF));
				} else if (aType == 4) { // IPV6
					byte[] addr = new byte[16];
					for (int i = 0; i < addr.length; i++) {
						addr[i] = receivedWsBytesBuffer.get(4 + i);
					}
					target = new InetSocketAddress(IOTools.runUnchecked(() -> Inet6Address.getByAddress(addr)),
							(receivedWsBytesBuffer.get(4 + addr.length) & 0xFF) * 256
									+ (receivedWsBytesBuffer.get(4 + addr.length + 1) & 0xFF));
				} else {
					sendCommandResponse((byte) 0x08);
					close("Unknown address type: " + aType);
					return;
				}
				if (cmd != 1) {
					sendCommandResponse((byte) 0x07);
					close("Unsupported CMD: " + cmd);
					return;
				}
				// Check target against allowed
				
				boolean allowed = false;
				for (String allowedAddress: allowedAddressess) {
					if (IOTools.testInetAddressPatternMatch(allowedAddress, target)) {
						allowed = true;
						break;
					}
				}
				if (!allowed) {
					sendCommandResponse(REP_TARGET_NOT_ALLOWED);
					close("Target now allowed: " + target);
					return;
				}
				
				// Connect
				updateBufferState(messageLen);
				if (receivedWsBytesBuffer.position() > 0) {
					log.warn("Received bytes after command (discarded): " + receivedWsBytesBuffer.position());
					receivedWsBytesBuffer.clear();
				}
				this.state.set(State.CONNECTING);
				tcpConnectToTarget(target).handle((AsynchronousSocketChannel s,Throwable e)->{
					State state2 = this.state.get();
					if (state2 != State.CONNECTING) {
						String msg = "Illegal state. Expected " + State.CONNECTING + " but it is " + state2;
						log.error(msg);
						close(msg);
						if (s != null) {
							IOTools.runFailable(()->s.close());
						}
					}
					if (receivedWsBytesBuffer.position() != 0) {
						log.warn("Received bytes during connection (discarded): " + receivedWsBytesBuffer.position());
					}
					this.receivedWsBytesBuffer.set(null);
					if (e != null) {
						sendCommandResponse(REP_CONNECTION_REFUSED); // TODO more detailed
						close("Error connecting to target: " + e.getMessage());
					} else {
						sendCommandResponse(REP_SUCCESS);
						this.state.set(State.CONNECTED);
						success();
						this.result.complete(s);
					}
					return null;
				});
			} else {
				log.error("Unexpected message on current state " + state);
			}
		}
	}
	
	protected CompletableFuture<AsynchronousSocketChannel> tcpConnectToTarget(SocketAddress targetAddress) {
		CompletableFuture<AsynchronousSocketChannel> res = new CompletableFuture<AsynchronousSocketChannel>();
		try {
			AsynchronousSocketChannel cs = AsynchronousSocketChannel.open();
			cs.setOption(StandardSocketOptions.SO_KEEPALIVE, true);
			ScheduledFuture<?> timeoutTask = scheduler.schedule(()->{
				// Connection timeout
				res.completeExceptionally(new SocketTimeoutException("Timeout connecting to " + targetAddress));
				IOTools.runFailable(()->cs.close());
			}, this.tcpConnectTimeoutSeconds, TimeUnit.SECONDS);
			cs.connect(targetAddress, null, new CompletionHandler<Void, Void>() {
				public void completed(Void result, Void a) {
					timeoutTask.cancel(true);
					res.complete(cs);
				}
				public void failed(Throwable e, Void a) {
					timeoutTask.cancel(true);
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


	private void answerAndUpdateState(ByteBuffer response, int processed, State newState) {
		updateBufferState(processed);
		this.state.set(newState);
		senderToWs.accept(response);
	}

	private void updateBufferState(int processed) {
		ByteBuffer buffer = receivedWsBytesBuffer.get();
		buffer.position(buffer.position() - processed);
		byte[] a = buffer.array();
		System.arraycopy(a, processed, a, 0, buffer.position());
	}

	private boolean checkVersion() {
		ByteBuffer receivedWsBytesBuffer = this.receivedWsBytesBuffer.get();
		if (receivedWsBytesBuffer.position() > 0) {
			int version = receivedWsBytesBuffer.get(0) & 0xFF;
			if (version != 5) {
				close("Unsupported protocol version " + version);
				return false;
			}
		}
		return true;
	}

	private void sendCommandResponse(byte rep) {
		// |VER | REP |  RSV  | ATYP | BND.ADDR | BND.PORT |
		ByteBuffer bb = ByteBuffer.allocate(10);
		bb.put((byte) 5);
		bb.put(rep);
		bb.put((byte) 0);
		bb.put((byte) 1);
		bb.put((byte) 0);
		bb.put((byte) 0);
		bb.put((byte) 0);
		bb.put((byte) 0);
		bb.put((byte) 0);
		bb.put((byte) 0);
		bb.flip();
		senderToWs.accept(bb);
	}

	public CompletionStage<AsynchronousSocketChannel> getResult() {
		return result;
	}

	public void close(String msg) {
		this.socks5timeoutTask.cancel(true);
		this.state.set(State.CLOSED);
		this.result.complete(null);
		this.closedEvent.accept(msg);
		this.scheduler.shutdown();
	}

	private void success() {
		this.socks5timeoutTask.cancel(true);
	}
}
