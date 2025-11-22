package org.aalku.joatse.target;

import java.io.Console;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.URL;
import java.util.Collection;
import java.util.Optional;
import java.util.Random;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

import org.aalku.joatse.target.tools.QrGenerator;
import org.aalku.joatse.target.tools.QrGenerator.QrMode;
import org.aalku.joatse.target.tools.io.IOTools;
import org.json.JSONArray;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.util.concurrent.ListenableFutureCallback;
import org.springframework.web.socket.BinaryMessage;
import org.springframework.web.socket.CloseStatus;
import org.springframework.web.socket.PingMessage;
import org.springframework.web.socket.PongMessage;
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

public class JoatseClient implements WebSocketHandler {

	private static final int TIME_BETWEEN_PING_MS = 5000;
	private static final int TIMEOUT_PONG_MS = 60000;
	private static final int MESSAGE_SIZE_LIMIT = 1024*64;

	private enum ClientState { BOOT, WS_CONNECTED, WAITING_RESPONSE, WAITING_CONFIRM, TUNNEL_CONNECTED, FINISHED };

	private Logger log = LoggerFactory.getLogger(JoatseClient.class);
	private String cloudUrl;
	private QrMode qrMode;
	
	private Lock lock = new ReentrantLock();
	/** app state change condition. Use with lock */
	private Condition stateChange = lock.newCondition();
	/** app state. Use with lock */
	private AtomicReference<ClientState> state = new AtomicReference<>(ClientState.BOOT);
	
    private JoatseSession jSession = null;
    		
    private AtomicLong lastMsgReceivedNanotime = new AtomicLong(System.nanoTime());
    
	public JoatseClient(String cloudUrl, QrMode qrMode) {
		this.cloudUrl = cloudUrl;
		this.qrMode = qrMode;
	}

	public JoatseClient connect() throws URISyntaxException {
        WebSocketHttpHeaders headers = new WebSocketHttpHeaders();
        final StandardWebSocketClient client = new StandardWebSocketClient();
		client.doHandshake(this, headers, new URI(cloudUrl)).addCallback(new ListenableFutureCallback<WebSocketSession>() {
			@Override
			public void onSuccess(WebSocketSession session) {
				Thread t = new Thread("ws_heartbeat_" + session.getId()) {
					public void run() {
						try {
							while (session.isOpen()) {
								session.sendMessage(new PingMessage());
								Thread.sleep(TIME_BETWEEN_PING_MS);
								long msWithoutMessages = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - JoatseClient.this.lastMsgReceivedNanotime.get());
								if (msWithoutMessages > TIMEOUT_PONG_MS) {
									throw new IOException("Pong timeout");
								}
							}
						} catch (Exception e) {
							log.warn("Exception on heartbeat sender thread. Closing session.", e);
							IOTools.runFailable(()->session.close(CloseStatus.SESSION_NOT_RELIABLE));
						}
					}
				};
				t.start();
				// We will use the other handler
			}
			@Override
			public void onFailure(Throwable ex) {
				log.error("Exception", ex);
				setState(ClientState.FINISHED);
			}
		});
		return this;
	}
	
	public boolean isConnected() {
		return state.get() == ClientState.WS_CONNECTED;
	}
	
	JoatseClient waitUntilConnected() {
		lock.lock();
		try {
			ClientState aux;
			while ((aux = state.get()) != ClientState.WS_CONNECTED && aux != ClientState.FINISHED) {
				if (aux != ClientState.BOOT) {
					throw new IllegalStateException("Invalid state " + state.get() + " when waiting for " + ClientState.WS_CONNECTED);
				}
				log.info("waiting for ClientState {} or {}; it is {}", ClientState.WS_CONNECTED, ClientState.FINISHED, state.get());
				stateChange.await();
			}
		} catch (InterruptedException e1) {
			throw new RuntimeException(e1);
		} finally {
			lock.unlock();
		}
		return this;
	}
	
	void waitUntilFinished() {
		lock.lock();
		try {
			while (state.get() != ClientState.FINISHED) {
				log.info("waiting for ClientState {}; it is {}", ClientState.FINISHED, state.get());
				stateChange.await();
			}
		} catch (InterruptedException e1) {
			return;
		} finally {
			lock.unlock();
		}
	}

	private void setState(ClientState newState) {
		lock.lock();
		try {
			log.info("ClientState change {} -> {}", state.get(), newState);
			state.set(newState);
			stateChange.signalAll();
		} finally {
			lock.unlock();
		}
	}

	@Override
	public void afterConnectionEstablished(WebSocketSession session) throws Exception {
		session.setBinaryMessageSizeLimit(MESSAGE_SIZE_LIMIT);
		log.info("connected: {}", session.getId());
		jSession = new JoatseSession(session);
		setState(ClientState.WS_CONNECTED);
	}

	@Override
	public void handleMessage(WebSocketSession session, WebSocketMessage<?> message) throws Exception {
		lastMsgReceivedNanotime.set(System.nanoTime());
		if (message instanceof TextMessage) {
			handleTextMessage(session, (TextMessage) message);
		} else if (message instanceof BinaryMessage) {
			handleBinaryMessage(session, (BinaryMessage) message);
		} else if (message instanceof PingMessage) {
			log.info("Ping");
		} else if (message instanceof PongMessage) {
//			log.info("Pong");
		}
	}
	
	private void handleTextMessage(WebSocketSession session, TextMessage message) throws Exception {
		log.info("handleTextMessage: {}", message.getPayload());
		try {
			JSONObject js = new JSONObject(message.getPayload());
			if ("CONNECTION".equals(js.optString("request")) && js.has("response")) {
				String response = js.getString("response");
				if (response.equals("RUNNING")) {
					if (state.get() != ClientState.WAITING_CONFIRM) {
						throw new IllegalStateException("Invalid response " + response + " when state != WAITING_CONFIRM (it is " + state.get() + ")");
					}
					System.out.println("Tunnel registered succesfuly.");
					if (js.has("tcpTunnels")) {
						JSONArray tcpTunnels = js.getJSONArray("tcpTunnels");
						if (!tcpTunnels.isEmpty()) {
							System.out.println("TCP Tunnels:");
							try {
								for (int i = 0; i < tcpTunnels.length(); i++) {
									JSONObject p = tcpTunnels.getJSONObject(i);
									int listenPort = p.getInt("listenPort");
									System.out.println(String.format("  %s:%s --> %s:%s", p.getString("listenHost"), listenPort, p.getString("targetHostname"), p.getInt("targetPort")));
								}
							} catch (Exception e) {
								System.err.println("Error printing tcp tunnels");
								System.err.println("tcpTunnels element is: " + tcpTunnels.toString());
								e.printStackTrace();
							}
						}
					}
					if (js.has("httpTunnels")) {
						JSONArray httpTunnels = js.getJSONArray("httpTunnels");
						if (!httpTunnels.isEmpty()) {
							System.out.println("Http Tunnels:");
							try {
								for (int i = 0; i < httpTunnels.length(); i++) {
									JSONObject p = httpTunnels.getJSONObject(i);
									String listenUrl = p.getString("listenUrl");
									System.out.println(String.format("  %s --> %s", listenUrl, p.getString("targetUrl")));
								}
							} catch (Exception e) {
								System.err.println("Error printing http tunnels");
								System.err.println("httpTunnels element is: " + httpTunnels.toString());
								e.printStackTrace();
							}
						}
					}
					if (js.has("fileTunnels")) {
						JSONArray fileTunnels = js.getJSONArray("fileTunnels");
						if (!fileTunnels.isEmpty()) {
							System.out.println("File Tunnels:");
							try {
								for (int i = 0; i < fileTunnels.length(); i++) {
									JSONObject p = fileTunnels.getJSONObject(i);
									String listenUrl = p.getString("listenUrl");
									String targetPath = p.getString("targetPath");
									String targetDescription = p.optString("targetDescription", "");
									if (targetDescription.isEmpty()) {
										System.out.println(String.format("  %s --> %s", listenUrl, targetPath));
									} else {
										System.out.println(String.format("  %s --> %s (%s)", listenUrl, targetPath, targetDescription));
									}
								}
							} catch (Exception e) {
								System.err.println("Error printing file tunnels");
								System.err.println("fileTunnels element is: " + fileTunnels.toString());
								e.printStackTrace();
							}
						}
					}
					setState(ClientState.TUNNEL_CONNECTED);
					jSession.handleConnected();
    				return;
				} else if (response.equals("CONFIRM")){
					if (state.get() != ClientState.WAITING_RESPONSE) {
						throw new IllegalStateException("Invalid response " + response + " when state != WAITING_RESPONSE");
					}
					Console console = System.console();
					String confirmationUri = js.getString("confirmationUri");
					openUrl(confirmationUri);
					String prompt1 = "Please open this URL in your browser in order to confirm the connection: %n%s";
					String promptQr = "Or scan the next QR code.";
					String qr = QrGenerator.getQr(qrMode, confirmationUri);
					if (console != null) {
						console.format(prompt1, confirmationUri);
						if (qr != null) {
							console.format("%s%n", promptQr);
							console.format("%s%n%s%n", qr, confirmationUri);
						}
						console.flush();
					} else {
						System.out.println(String.format(prompt1, confirmationUri));
						if (qr != null) {
							System.out.println(promptQr);
							System.out.println(qr);
							System.out.println(confirmationUri);
						}
					}
					setState(ClientState.WAITING_CONFIRM);
					return;
				} else if (response.equals("REJECTED")){
					log.error("Connection rejected. Cause: {}", js.getString("rejectionCause"));
				} else {
    				throw new IllegalStateException("Unexpected response format or type");
				}
			} else {
				throw new IllegalStateException("Unexpected response format or type");
			}
		} catch (Exception e) {
			log.error("Exception processing text message: " + e, e);
		}
		session.close();
		setState(ClientState.FINISHED);
	}

	private void openUrl(String confirmationUri) throws URISyntaxException {
		URI uri = new URI(confirmationUri);
		if ((uri.getScheme().equals("http") || uri.getScheme().equals("https"))
				&& uri.getHost().equalsIgnoreCase(new URI(cloudUrl).getHost())) {
			try {
				if (java.awt.Desktop.isDesktopSupported()) {
					java.awt.Desktop.getDesktop().browse(uri);
				}
			} catch (Exception e) {
				log.warn("Can't open confirmation URI: " + e, e);
			}
		}
	}
	
	private void handleBinaryMessage(WebSocketSession session, BinaryMessage message) throws Exception {
		// log.info("handleBinaryMessage: {}", IOTools.toString(message.getPayload()));
		try {
			if (jSession == null) {
				throw new IOException("Unexpected binary message before tunnel creation");
			} else {
				jSession.handleBinaryMessage(message);
			}
		} catch (Exception e) {
			log.error("Exception processing binary message: " + e, e);
			closeSession(session, e);
		}
	}
	
	private void closeSession(WebSocketSession session, Throwable e) {
		IOTools.runFailable(()->session.close());
		log.error("{}", e.toString(), e);
	}

	@Override
	public void handleTransportError(WebSocketSession session, Throwable e) throws Exception {
		log.error("Transportation error: {}", e, e);
		setState(ClientState.FINISHED);
	}

	@Override
	public void afterConnectionClosed(WebSocketSession session, org.springframework.web.socket.CloseStatus status) throws Exception {
		log.info("disconnected: {} - {}", session.getId(), status);
		setState(ClientState.FINISHED);
		Optional.ofNullable(jSession).ifPresent(s->IOTools.runFailable(()->s.close()));
	}

	@Override
	public boolean supportsPartialMessages() {
		return false;
	}
	
	public static class TunnelRequestItemTcp {
		public long targetId = new Random().nextLong() & Long.MAX_VALUE;
		public final String targetHostname;
		public final int targetPort;
		public final String targetDescription;

		public TunnelRequestItemTcp(String targetHostname, int targetPort, String targetDescription) {
			this.targetHostname = targetHostname;
			this.targetPort = targetPort;
			this.targetDescription = targetDescription;
		}
	}

	public static class TunnelRequestItemHttp extends TunnelRequestItemTcp {
		/**
		 * we store the url to pass it to the cloud but we will not use it anymore. For
		 * this end this is TCP
		 */
		public final URL targetUrl;
		public final boolean unsafe;
		public final boolean hideProxy;

		public TunnelRequestItemHttp(URL url, String targetDescription, boolean unsafe, boolean hideProxy) {
			super(url.getHost(), Optional.of(url.getPort()).filter(p -> p > 0)
					.orElseGet(() -> url.getDefaultPort()), targetDescription);
			this.targetUrl = url;
			this.unsafe = unsafe;
			this.hideProxy = hideProxy;
		}
	}
	
	public static class TunnelRequestItemSocks5 {
		public final long targetId = new Random().nextLong() & Long.MAX_VALUE;
		private final Collection<String> authorizedTargets;

		public TunnelRequestItemSocks5(Collection<String> authorizedTargets) {
			this.authorizedTargets = authorizedTargets;
		}

		public Collection<String> getAuthorizedTargets() {
			return authorizedTargets;
		}
	}

	public static class TunnelRequestItemCommand {
		public final long targetId = new Random().nextLong() & Long.MAX_VALUE;
		private final String[] command;
		private final String targetDescription;
		private final String targetUser;
		private final String targetHostname;
		private final int targetPort;

		public TunnelRequestItemCommand(String[] command, String targetUser, String targetHost, int targetPort, String targetDescription) {
			this.command = command;
			this.targetUser = targetUser;
			this.targetHostname = targetHost;
			this.targetPort = targetPort;
			this.targetDescription = targetDescription;
		}
		public String[] getCommand() {
			return command;
		}
		public String getTargetDescription() {
			return targetDescription;
		}
		public String getTargetUser() {
			return targetUser;
		}
		public String getTargetHostname() {
			return targetHostname;
		}
		public int getTargetPort() {
			return targetPort;
		}
	}
	
	public static class TunnelRequestItemFile {
		public final long targetId = new Random().nextLong() & Long.MAX_VALUE;
		public final String targetPath;
		public final String targetDescription;

		public TunnelRequestItemFile(String targetPath, String targetDescription) {
			this.targetPath = targetPath;
			this.targetDescription = targetDescription;
		}

		public long getTargetId() {
			return targetId;
		}

		public String getTargetPath() {
			return targetPath;
		}

		public String getTargetDescription() {
			return targetDescription;
		}
	}
	
	/**
	 * @param commandTunnels 
	 * @param fileTunnels 
	 * @param preconfirmUuid 
	 * @param tcpTunnels: Requested TCP tunnel connections
	 * @param httpTunnels: Requested HTTP tunnel connections
	 * @param socks5Tunnel: Requested Socks5 tunnel connection 
	 */
	public void createTunnel(Collection<TunnelRequestItemTcp> tcpTunnels, Collection<TunnelRequestItemHttp> httpTunnels,
			Optional<TunnelRequestItemSocks5> socks5Tunnel, Collection<TunnelRequestItemCommand> commandTunnels,
			Collection<TunnelRequestItemFile> fileTunnels,
			Optional<UUID> preconfirmUuid, boolean autoAuthorizeByHttpUrl) {
		if (state.get() != ClientState.WS_CONNECTED) {
			throw new IllegalStateException("Invalid call to createTunnel when state != WS_CONNECTED");
		}
		jSession.createTunnel(tcpTunnels, httpTunnels, socks5Tunnel, commandTunnels, fileTunnels, preconfirmUuid, autoAuthorizeByHttpUrl);
		if (!preconfirmUuid.isPresent()) {
			setState(ClientState.WAITING_RESPONSE);
		} else {
			setState(ClientState.WAITING_CONFIRM);
		}
	}

	/**
	 * The session is supossed to be shutdown but let's make sure of it.
	 */
	public void ensureShutdown() {
		ClientState state = this.state.get();
		if (state != ClientState.FINISHED) {
			log.warn("Client is supossed to be finished: " + state);
		}
		if (jSession != null) {
			IOTools.runFailable(()->jSession.close(new RuntimeException("ensureShutdown")));
		}
	}

	public void shutdown() {
		if (jSession != null) {
			jSession.close(new InterruptedException("Shutting down app"));
		}
	}

}
