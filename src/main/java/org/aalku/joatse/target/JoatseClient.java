package org.aalku.joatse.target;

import java.io.Console;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.Collection;
import java.util.Optional;
import java.util.Random;
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
import org.springframework.web.socket.TextMessage;
import org.springframework.web.socket.WebSocketHandler;
import org.springframework.web.socket.WebSocketHttpHeaders;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;
import org.springframework.web.socket.client.standard.StandardWebSocketClient;

public class JoatseClient implements WebSocketHandler {

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
		if (message instanceof TextMessage) {
			handleTextMessage(session, (TextMessage) message);
		} else if (message instanceof BinaryMessage) {
			handleBinaryMessage(session, (BinaryMessage) message);
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
						throw new IllegalStateException("Invalid response " + response + " when state != WAITING_CONFIRM");
					}
					System.out.println("Tunnel registered succesfuly.");
					if (js.has("tcpListenPorts")) {
						JSONArray tcp = js.getJSONArray("tcpListenPorts");
						if (!tcp.isEmpty()) {
							System.out.println("TCP Tunnels:");
							for (int i = 0; i < tcp.length(); i++) {
								JSONObject p = tcp.getJSONObject(i);
								System.out.println(String.format("  %s:%s --> %s:%s", p.getString("listenHost"), p.getInt("listenPort"), p.getString("targetHostname"), p.getInt("targetPort")));
							}
						}
					}
					setState(ClientState.TUNNEL_CONNECTED);
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
	
	public static class TunnelRequestItem {
		public long targetId = new Random().nextLong() & Long.MAX_VALUE;
		public final String targetHostname;
		public final int targetPort;
		public final String targetDescription;

		public TunnelRequestItem(String targetHostname, int targetPort, String targetDescription) {
			this.targetHostname = targetHostname;
			this.targetPort = targetPort;
			this.targetDescription = targetDescription;
		}
	}

	/**
	 * Requested TCP tunnel connections
	 */
	public void createTunnel(Collection<TunnelRequestItem> tcpTunnels) {
		if (state.get() != ClientState.WS_CONNECTED) {
			throw new IllegalStateException("Invalid call to createTunnel when state != WS_CONNECTED");
		}
		jSession.createTunnel(tcpTunnels);
		setState(ClientState.WAITING_RESPONSE);
	}

}
