package org.aalku.joatse.target;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.locks.ReentrantLock;

import org.aalku.joatse.target.JoatseClient.TunnelRequestItemHttp;
import org.aalku.joatse.target.JoatseClient.TunnelRequestItemTcp;
import org.aalku.joatse.target.tools.io.WebSocketSendWorker;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.TextMessage;
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
	 * Requested tcp connections
	 */
	private Map<Long, TunnelRequestItemTcp> tcpConnectionTargets = new LinkedHashMap<>();

	/**
	 * Stablished TCP tunnel connections
	 */
	private Map<Long, TunnelTcpConnection> tcpConnectionMap = new LinkedHashMap<>();
	
	private WebSocketSendWorker wsSendWorker;

	private WebSocketSession session;

	
	public JoatseSession(WebSocketSession session) {
		this.session = session;
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
			long targetId = buffer.getLong();
			TunnelRequestItemTcp target = tcpConnectionTargets.get(targetId);
			InetSocketAddress targetAddress = new InetSocketAddress(InetAddress.getByName(target.targetHostname), target.targetPort);
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
					log.warn("TunnelTcpConnection is not open: " + socketId);
					return; // Abort withouut closing the session
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

	public void createTunnel(Collection<TunnelRequestItemTcp> tcpTunnels, Collection<TunnelRequestItemHttp> httpTunnels) {
		// TODO udp ports
		JSONObject js = new JSONObject();
		js.put("request", "CONNECTION");
		if (!tcpTunnels.isEmpty()) {
			JSONArray tcpJs = new JSONArray();
			for (TunnelRequestItemTcp i: tcpTunnels) {
				tcpConnectionTargets.put(i.targetId, i);
				JSONObject o = new JSONObject();
				o.put("targetId", i.targetId);
				o.put("targetDescription", i.targetDescription);
				o.put("targetHostName", i.targetHostname);
				o.put("targetPort", i.targetPort);
				tcpJs.put(o);
			}
			js.put("tcpTunnels", tcpJs);
		}
		if (!httpTunnels.isEmpty()) {
			JSONArray httpJs = new JSONArray();
			for (TunnelRequestItemHttp i: httpTunnels) {
				tcpConnectionTargets.put(i.targetId, i); // It's tcp too
				JSONObject o = new JSONObject();
				o.put("targetId", i.targetId);
				o.put("targetDescription", i.targetDescription);
				o.put("targetUrl", i.targetUrl.toString());
				o.put("unsafe", Boolean.toString(i.unsafe));
				httpJs.put(o);
			}
			js.put("httpTunnels", httpJs);
		}
		TextMessage message = new TextMessage(js.toString());
		log.info("sending request: {}", message.getPayload());
		sendMessage(message);
	}
	
}