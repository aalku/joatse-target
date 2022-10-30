package org.aalku.joatse.target;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;

import org.aalku.joatse.target.tools.io.WebSocketSendWorker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

/**
 * Single websock, several connections for each port, maybe several ports
 */
public class JoatseSession {

	static final byte PROTOCOL_VERSION = 1;

	private Logger log = LoggerFactory.getLogger(JoatseSession.class);
	ReentrantLock lock = new ReentrantLock();
	
	/**
	 * Stablished TCP tunnel connections
	 */
	private Map<Long, TunnelTcpConnection> tcpConnectionMap = new LinkedHashMap<>();
	private WebSocketSendWorker wsSendWorker;

	private WebSocketSession session;
	private InetAddress targetInetAddress;
	
	public JoatseSession(WebSocketSession session, InetAddress targetInetAddress) {
		this.session = session;
		this.targetInetAddress = targetInetAddress;
		this.wsSendWorker = new WebSocketSendWorker(this.session);
	}
	
	void add(TunnelTcpConnection c) {
		lock.lock();
		try {
			tcpConnectionMap.put(c.socketId, c);
		} finally {
			lock.unlock();
		}
	}

	public void handleBinaryMessage(BinaryMessage message) throws IOException {
		ByteBuffer buffer = message.getPayload();
		int version = buffer.get();
		if (version != PROTOCOL_VERSION) {
			throw new IOException("Unsupported BinaryMessage protocol version: " + version);
		}
		byte type = buffer.get();
		if (type == TunnelTcpConnection.MESSAGE_TYPE_NEW_TCP_SOCKET) {
			long socketId = buffer.getLong();
			int targetPort = buffer.getShort() & 0xFFFF;
			InetSocketAddress targetAddress = new InetSocketAddress(targetInetAddress, targetPort);
			TunnelTcpConnection c = new TunnelTcpConnection(this, targetAddress, socketId, (e)->this.close(e));
			c.getCloseStatus().thenAccept(remote->{
				// Connection closed ok
				if (remote == null) {
					log.info("TCP tunnel closed");
				} else if (remote) {
					log.info("TCP tunnel closed by target side");
				} else {
					log.info("TCP tunnel closed by this side");
				}
			}).exceptionally(e->{
				log.error("TCP tunnel closed because of error: {}", e, e);
				return null;
			});
		} else if (TunnelTcpConnection.messageTypesHandled.contains(type)) {
			long socketId = buffer.getLong();
			Runnable runWithoutLock = null; 
			TunnelTcpConnection c = null;
			lock.lock();
			try {
				c = tcpConnectionMap.get(socketId);
				if (c == null) {
					throw new IOException("TunnelTcpConnection is not open: " + socketId);
				}
				runWithoutLock = c.receivedMessage(buffer, type); // blocking with lock is ok
			} catch (Exception e) {
				if (c != null) {
					log.warn("Error handling tcp data: " + e, e);
					c.close();
				} else {
					throw e;
				}
			} finally {
				lock.unlock();
			}
			if (runWithoutLock != null) {
				runWithoutLock.run();
			}
		}
	}
	
	public void close() {
		close(null);
	}
	
	public void close(Throwable e) {
		lock.lock();
		try {
			/*
			 * We need a copy since c.close() will update the map and we cannot iterate the
			 * map at the same time.
			 */
			ArrayList<TunnelTcpConnection> copy = new ArrayList<TunnelTcpConnection>(tcpConnectionMap.values());
			for (TunnelTcpConnection c: copy) {
				lock.unlock(); // Unlock while closing it so we don't share the lock with anyone else
				try {
					c.close(e, false);
				} finally {
					lock.lock();
				}
			}
		} finally {
			lock.unlock();
			wsSendWorker.close();
		}
	}

	public void remove(TunnelTcpConnection c) {
		lock.lock();
		try {
			tcpConnectionMap.remove(c.socketId, c);
			c.assertClosed();
		} finally {
			lock.unlock();
		}
	}

	public CompletableFuture<Void> sendMessage(WebSocketMessage<?> message) {
		return wsSendWorker.sendMessage(message);
	}
}