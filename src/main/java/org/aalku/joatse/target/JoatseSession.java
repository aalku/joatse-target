package org.aalku.joatse.target;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import org.aalku.joatse.target.JoatseClient.TunnelRequestItemHttp;
import org.aalku.joatse.target.JoatseClient.TunnelRequestItemSocks5;
import org.aalku.joatse.target.JoatseClient.TunnelRequestItemTcp;
import org.aalku.joatse.target.connection.AbstractTunnelTcpConnection;
import org.aalku.joatse.target.connection.BasicTunnelTcpConnection;
import org.aalku.joatse.target.connection.Socks5TunnelTcpConnection;
import org.aalku.joatse.target.tools.io.IOTools;
import org.aalku.joatse.target.tools.io.WebSocketSendWorker;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
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
	 * Requested tcp and http connections
	 */
	private Map<Long, TunnelRequestItemTcp> tcpConnectionRequestTargets = new LinkedHashMap<>();
	
	/** Requested socks5 connecrtion */
	private AtomicReference<TunnelRequestItemSocks5> tunnelRequestItemSocks5 = new AtomicReference<JoatseClient.TunnelRequestItemSocks5>(null);
	
	/**
	 * Stablished TCP tunnel connections
	 */
	private Map<Long, AbstractTunnelTcpConnection> tcpConnectionMap = new LinkedHashMap<>();
	
	private WebSocketSendWorker wsSendWorker;

	private WebSocketSession session;

	
	public JoatseSession(WebSocketSession session) {
		this.session = session;
		this.wsSendWorker = new WebSocketSendWorker(this.session);
	}
	
	void add(AbstractTunnelTcpConnection c) {
		lock.lock();
		try {
			tcpConnectionMap.put(c.getSocketId(), c);
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
		if (type == AbstractTunnelTcpConnection.MESSAGE_TYPE_NEW_SOCKET) {
			long socketId = buffer.getLong();
			long targetId = buffer.getLong();
			TunnelRequestItemTcp target = tcpConnectionRequestTargets.get(targetId);
			if (target != null) {
				InetSocketAddress targetAddress = new InetSocketAddress(InetAddress.getByName(target.targetHostname), target.targetPort);
				BasicTunnelTcpConnection c = new BasicTunnelTcpConnection(this, targetAddress, socketId, (e)->this.close(e));
				add(c);
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
			} else {
				Optional<TunnelRequestItemSocks5> socks5 = Optional.ofNullable(tunnelRequestItemSocks5.get()).filter(x->x.targetId == targetId);
				if (socks5.isPresent()) {
					// Socks5
					Socks5TunnelTcpConnection c = new Socks5TunnelTcpConnection(this, socketId, (e)->this.close(e), socks5.get());
					add(c);
					c.getCloseStatus().thenAccept(remote->{
						// Connection closed ok
						if (remote == null) {
							log.info("Socks5 TCP tunnel closed");
						} else if (remote) {
							log.info("Socks5 TCP tunnel closed by target side");
						} else {
							log.info("Socks5 TCP tunnel closed by this side");
						}
					}).exceptionally(e->{
						log.error("TCP tunnel closed because of error: {}", e, e);
						return null;
					});
				} else {
					log.warn("Received new socket for unknown target id: " + targetId);
				}
			}
		} else if (AbstractTunnelTcpConnection.messageTypesHandled.contains(type)) {
			long socketId = buffer.getLong();
			Runnable runWithoutLock = null; 
			AbstractTunnelTcpConnection c = null;
			lock.lock();
			try {
				c = tcpConnectionMap.get(socketId);
				if (c == null) {
					log.warn("AbstractTunnelTcpConnection is not open: " + socketId);
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
			ArrayList<AbstractTunnelTcpConnection> copy = new ArrayList<AbstractTunnelTcpConnection>(tcpConnectionMap.values());
			for (AbstractTunnelTcpConnection c: copy) {
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
			IOTools.runFailable(()->session.close(CloseStatus.NORMAL));
		}
	}

	public void remove(AbstractTunnelTcpConnection c) {
		lock.lock();
		try {
			tcpConnectionMap.remove(c.getSocketId(), c);
			c.assertClosed();
		} finally {
			lock.unlock();
		}
	}

	public CompletableFuture<Void> sendMessage(WebSocketMessage<?> message) {
		return wsSendWorker.sendMessage(message);
	}

	public void createTunnel(Collection<TunnelRequestItemTcp> tcpTunnels, Collection<TunnelRequestItemHttp> httpTunnels,
			Optional<TunnelRequestItemSocks5> socks5Tunnel, Optional<UUID> preconfirmUuid) {
		// TODO udp ports
		JSONObject js = new JSONObject();
		js.put("request", "CONNECTION");
		if (!tcpTunnels.isEmpty()) {
			JSONArray tcpJs = new JSONArray();
			for (TunnelRequestItemTcp i: tcpTunnels) {
				tcpConnectionRequestTargets.put(i.targetId, i);
				JSONObject o = new JSONObject();
				o.put("targetId", i.targetId);
				o.put("targetDescription", i.targetDescription);
				o.put("targetHostname", i.targetHostname);
				o.put("targetPort", i.targetPort);
				tcpJs.put(o);
			}
			js.put("tcpTunnels", tcpJs);
		}
		if (!httpTunnels.isEmpty()) {
			JSONArray httpJs = new JSONArray();
			for (TunnelRequestItemHttp i: httpTunnels) {
				tcpConnectionRequestTargets.put(i.targetId, i); // It's tcp too
				JSONObject o = new JSONObject();
				o.put("targetId", i.targetId);
				o.put("targetDescription", i.targetDescription);
				o.put("targetUrl", i.targetUrl.toString());
				o.put("unsafe", Boolean.toString(i.unsafe));
				httpJs.put(o);
			}
			js.put("httpTunnels", httpJs);
		}
		socks5Tunnel.ifPresent(t->{
			JSONArray s5J = new JSONArray();
			this.tunnelRequestItemSocks5.set(t);
			JSONObject o = new JSONObject();
			o.put("targetId", t.targetId);
			s5J.put(o);
			js.put("socks5Tunnel", s5J);
		});
		preconfirmUuid.ifPresent(uuid->{
			js.put("preconfirmed", uuid.toString());
		});
		TextMessage message = new TextMessage(js.toString());
		log.info("sending request: {}", message.getPayload());
		sendMessage(message);
	}
	
}