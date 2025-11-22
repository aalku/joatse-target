package org.aalku.joatse.target;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

import org.aalku.joatse.target.JoatseClient.TunnelRequestItemCommand;
import org.aalku.joatse.target.JoatseClient.TunnelRequestItemFile;
import org.aalku.joatse.target.JoatseClient.TunnelRequestItemHttp;
import org.aalku.joatse.target.JoatseClient.TunnelRequestItemSocks5;
import org.aalku.joatse.target.JoatseClient.TunnelRequestItemTcp;
import org.aalku.joatse.target.connection.BasicTunnelTcpConnection;
import org.aalku.joatse.target.connection.CommandConnection;
import org.aalku.joatse.target.connection.FileTunnelConnection;
import org.aalku.joatse.target.connection.Socks5TunnelTcpConnection;
import org.aalku.joatse.target.connection.TunnelConnection;
import org.aalku.joatse.target.tools.cipher.JoatseCipher;
import org.aalku.joatse.target.tools.cipher.JoatseCipher.KeyExchange;
import org.aalku.joatse.target.tools.cipher.JoatseCipher.Paired;
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
	 * Map<Long targetId, TunnelRequestItemTcp> for requested tcp and http connections
	 */
	private Map<Long, TunnelRequestItemTcp> tcpRequestTargets = new LinkedHashMap<>();
	
	/**
	 * AtomicReference<TunnelRequestItemSocks5> for requested socks5 connections
	 */
	private AtomicReference<TunnelRequestItemSocks5> socks5RequestTarget = new AtomicReference<JoatseClient.TunnelRequestItemSocks5>(null);
	
	/**
	 * Map<Long targetId, TunnelRequestItemCommand> for requested command connections
	 */
	private Map<Long, TunnelRequestItemCommand> commandRequestTargets = new LinkedHashMap<>();
	
	/**
	 * Map<Long targetId, TunnelRequestItemFile> for requested file connections
	 */
	private Map<Long, TunnelRequestItemFile> fileRequestTargets = new LinkedHashMap<>();
	
	/**
	 * Map<Long socketId, TunnelConnection> for established tunnel connections
	 */
	private Map<Long, TunnelConnection> connectionMap = new LinkedHashMap<>();

	private WebSocketSendWorker wsSendWorker;

	private WebSocketSession session;

	private KeyExchange end2endCipher;

	
	public JoatseSession(WebSocketSession session) {
		this.session = session;
		this.wsSendWorker = new WebSocketSendWorker(this.session);
	}
	
	void add(TunnelConnection c) {
		lock.lock();
		try {
			connectionMap.put(c.getSocketId(), (TunnelConnection) c);
		} finally {
			lock.unlock();
		}
	}
	
	public void remove(TunnelConnection c) {
		lock.lock();
		try {
			connectionMap.remove(c.getSocketId(), c);
			c.assertClosed();
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
		if (type == TunnelConnection.MESSAGE_PUBLIC_KEY) {
			sendPublicKeyToCloud();
		} else if (type == TunnelConnection.MESSAGE_TYPE_NEW_SOCKET) {
			long socketId = buffer.getLong();
			long targetId = buffer.getLong();
			
			// Check for TCP/HTTP tunnel
			TunnelRequestItemTcp target = tcpRequestTargets.get(targetId);
			if (target != null) {
				newConnectionTcp(socketId, target);
				return;
			}
			
			// Check for SOCKS5 tunnel
			TunnelRequestItemSocks5 socks5 = Optional.ofNullable(socks5RequestTarget.get()).filter(x->x.targetId == targetId).orElse(null);
			if (socks5 != null) {
				newConnectionSocks5(socketId, socks5);
				return;
			}
			
			// Check for command tunnel (has encrypted session key payload)
			TunnelRequestItemCommand command = commandRequestTargets.get(targetId);
			if (command != null) {
				byte[] cipheredSessionKey = new byte[buffer.remaining()];
				buffer.get(cipheredSessionKey);
				Paired sessionCipher;
				try {
					sessionCipher = end2endCipher.pair(cipheredSessionKey);
				} catch (Exception e) {
					throw new IOException("Error pairing e2e cipher", e);
				}
				newConnectionCommand(socketId, command, sessionCipher);
				return;
			}
			
			// Check for file request (has 16-byte payload: offset + length)
			TunnelRequestItemFile fileTarget = fileRequestTargets.get(targetId);
			if (fileTarget != null) {
				if (buffer.remaining() >= 16) {
					long offset = buffer.getLong();
					long length = buffer.getLong();
					handleFileReadRequest(socketId, targetId, offset, length);
					return;
				} else {
					log.error("File request for targetId {} missing offset/length payload", targetId);
					return;
				}
			}
			
			log.warn("Received new socket for unknown target id: " + targetId);
			return;
		} else if (TunnelConnection.supportedMessages.contains(type)) {
			long socketId = buffer.getLong();
			Runnable runWithoutLock = null; 
			TunnelConnection c = null;
			lock.lock();
			try {
				c = connectionMap.get(socketId);
				if (c != null) {
					runWithoutLock = c.receivedTunnelMessage(buffer, type); // blocking with lock is ok
				} else {
					log.warn("TunnelConnection is not open: " + socketId);
					return; // Abort without closing the session
				}
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

	private void sendPublicKeyToCloud() {
		byte[] pk = this.end2endCipher.getPublicKey();
		ByteBuffer bytes = ByteBuffer.allocate(pk.length + 2);
		bytes.put(PROTOCOL_VERSION);
		bytes.put(TunnelConnection.MESSAGE_PUBLIC_KEY);
		bytes.put(pk);
		bytes.flip();
		sendMessage(new BinaryMessage(bytes));
	}

	private void newConnectionSocks5(long socketId, TunnelRequestItemSocks5 socks5) {
		Socks5TunnelTcpConnection c = new Socks5TunnelTcpConnection(this, socketId, (e)->this.close(e), socks5);
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
	}

	private void newConnectionTcp(long socketId, TunnelRequestItemTcp target) throws UnknownHostException {
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
	}
	
	private void newConnectionCommand(long socketId, TunnelRequestItemCommand target, Paired sessionCipher) {
		CommandConnection c = new CommandConnection(this, transformCommand(target.getCommand(), target.getTargetHostname(), target.getTargetPort(), target.getTargetUser()), socketId, (e)->this.close(e), sessionCipher);
		if (c.startCommand()) {
			add(c);
			c.getCloseStatus().thenAccept(remote->{
				// Connection closed ok
				if (remote == null) {
					log.info("Command tunnel closed");
				} else if (remote) {
					log.info("Command tunnel closed by target side");
				} else {
					log.info("Command tunnel closed by this side");
				}
			}).exceptionally(e->{
				log.error("Command tunnel closed because of error: {}", e, e);
				return null;
			});
		}
	}

	private String[] transformCommand(String[] command, String host, int port, String user) {
		// TODO Make it better
		
		List<String> inputCommand = Arrays.asList(command);
		List<String> outputCommand = new ArrayList<>();
		
		boolean isShell = inputCommand.size() == 0 // 
				|| (inputCommand.size() == 1 && inputCommand.get(0).trim().isEmpty()) // 
				|| (inputCommand.size() == 1 && inputCommand.get(0).trim().toLowerCase().equals("shell"));
		
		String nullfile = System.getProperty("os.name").toLowerCase().contains("win") ? "NUL" : "/dev/null";			
		List<String> sshCommand = Arrays.asList("ssh", "-t", "-q", "-e", "none", "-o", "PubkeyAuthentication=no", "-o", "PreferredAuthentications=password", "-o", "GSSAPIAuthentication=no", "-o", "UserKnownHostsFile=" + nullfile, "-o", "StrictHostKeyChecking=no", "-F", nullfile, "-l", user, "-p", String.valueOf(port), host.split("[ ;']+", 2)[0]);
		
		outputCommand.addAll(sshCommand);
		if (!isShell) {
			outputCommand.add("SHELL=/dev/null");
			outputCommand.addAll(inputCommand);
		}
		return outputCommand.toArray(new String[outputCommand.size()]);
	}

	private void handleFileReadRequest(long socketId, long targetId, long offset, long length) {
		TunnelRequestItemFile fileTarget = fileRequestTargets.get(targetId);
		if (fileTarget == null) {
			log.warn("File read request for unknown targetId: {}", targetId);
			// Create a connection just to send error and close
			FileTunnelConnection errorConn = new FileTunnelConnection(this, socketId, (e)->this.close(e), 
					"", 0, 0);
			add(errorConn);
			// The connection will handle sending error
			return;
		}
		
		// Create file tunnel connection
		FileTunnelConnection conn = new FileTunnelConnection(this, socketId, (e)->this.close(e),
				fileTarget.targetPath, offset, length);
		add(conn);
		
		// Start streaming (async via CompletableFuture chain)
		conn.startStreaming();
		
		// Set up completion handler
		conn.getCloseStatus().thenAccept(remote -> {
			if (remote == null) {
				log.info("File tunnel closed");
			} else if (remote) {
				log.info("File tunnel closed by cloud side");
			} else {
				log.info("File tunnel closed by this side");
			}
		}).exceptionally(e -> {
			log.error("File tunnel closed because of error: {}", e, e);
			return null;
		});
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
			ArrayList<TunnelConnection> copy = new ArrayList<>(connectionMap.values());
			for (TunnelConnection c: copy) {
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

	public CompletableFuture<Void> sendMessage(WebSocketMessage<?> message) {
		return wsSendWorker.sendMessage(message);
	}

	public void createTunnel(Collection<TunnelRequestItemTcp> tcpTunnels, Collection<TunnelRequestItemHttp> httpTunnels,
			Optional<TunnelRequestItemSocks5> socks5Tunnel, Collection<TunnelRequestItemCommand> commandTunnels,
			Collection<TunnelRequestItemFile> fileTunnels,
			Optional<UUID> preconfirmUuid, boolean autoAuthorizeByHttpUrl) {
		if (!commandTunnels.isEmpty()) {
			// Prepare e2e cypher
			try {
				this.end2endCipher = JoatseCipher.forRSAKeyExchange();
			} catch (NoSuchAlgorithmException e) {
				throw new RuntimeException("Can't activate E2E cipher: " + e, e);
			}
		}
		// TODO udp ports
		JSONObject js = new JSONObject();
		js.put("request", "CONNECTION");
		if (!tcpTunnels.isEmpty()) {
			JSONArray tcpJs = new JSONArray();
			for (TunnelRequestItemTcp i: tcpTunnels) {
				tcpRequestTargets.put(i.targetId, i);
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
				tcpRequestTargets.put(i.targetId, i); // It's tcp too
				JSONObject o = new JSONObject();
				o.put("targetId", i.targetId);
				o.put("targetDescription", i.targetDescription);
				o.put("targetUrl", i.targetUrl.toString());
				o.put("unsafe", Boolean.toString(i.unsafe));
				o.put("hideProxy", Boolean.toString(i.hideProxy));
				httpJs.put(o);
			}
			js.put("httpTunnels", httpJs);
		}
		socks5Tunnel.ifPresent(t->{
			JSONArray s5J = new JSONArray();
			this.socks5RequestTarget.set(t);
			JSONObject o = new JSONObject();
			o.put("targetId", t.targetId);
			s5J.put(o);
			js.put("socks5Tunnel", s5J);
		});
		if (!commandTunnels.isEmpty()) {
			JSONArray commandJs = new JSONArray();
			for (TunnelRequestItemCommand i: commandTunnels) {
				commandRequestTargets.put(i.targetId, i);
				JSONObject o = new JSONObject();
				o.put("targetId", i.targetId);
				o.put("targetDescription", i.getTargetDescription());
				o.put("targetHostname", i.getTargetHostname());
				o.put("targetPort", i.getTargetPort());
				o.put("targetUser", i.getTargetUser());
				o.put("command", new JSONArray(Arrays.asList(i.getCommand())));
				commandJs.put(o);
			}
			js.put("commandTunnels", commandJs);
		}
		if (!fileTunnels.isEmpty()) {
			JSONArray fileJs = new JSONArray();
			for (TunnelRequestItemFile i: fileTunnels) {
				fileRequestTargets.put(i.targetId, i);
				JSONObject o = new JSONObject();
				o.put("targetId", i.targetId);
				o.put("targetDescription", i.targetDescription);
				o.put("targetPath", i.targetPath);
				fileJs.put(o);
			}
			js.put("fileTunnels", fileJs);
		}
		preconfirmUuid.ifPresent(uuid->{
			js.put("preconfirmed", uuid.toString());
		});
		js.put("autoAuthorizeByHttpUrl", autoAuthorizeByHttpUrl);
		TextMessage message = new TextMessage(js.toString());
		log.info("sending request: {}", message.getPayload());
		sendMessage(message);
	}

	public void handleConnected() {
		Optional.ofNullable(end2endCipher).ifPresent(e2ec->{
			try {
				System.out.println(
						"Your should check this public key hash before entering your password when running commands in order to detect man-in-the-middle attacks: "
								+ end2endCipher.getPublicKeyHash());
			} catch (NoSuchAlgorithmException e) {
				throw new RuntimeException("Unable to print public key hash: " + e, e);
			}
		});
	}

	public byte[] getPublicKey() {
		return end2endCipher.getPublicKey();
	}
	
}