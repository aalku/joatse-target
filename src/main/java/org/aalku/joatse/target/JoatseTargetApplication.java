	package org.aalku.joatse.target;

import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.aalku.joatse.target.JoatseClient.TunnelRequestItemHttp;
import org.aalku.joatse.target.JoatseClient.TunnelRequestItemSocks5;
import org.aalku.joatse.target.JoatseClient.TunnelRequestItemTcp;
import org.aalku.joatse.target.tools.QrGenerator.QrMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"org.aalku.joatse.target.cloud"})
public class JoatseTargetApplication implements ApplicationRunner {
	
	private static final long SLEEP_BETWEEN_CONNECTION_TRIES = 2000L;

	private static final int DEFAULT_RETRY_COUNT = 5;

	@Value("${qr-mode:AUTO}")
	private QrMode qrMode;
	
	@Value("${cloud.url:ws://localhost:9011/connection}")
	private String cloudUrl;
	
	@Value("${retryCount:}")
	private Integer retryCount = null;
	
	@Value("${daemonMode:false}")
	private boolean daemonMode = false;
	
	private static class CommandLineException extends RuntimeException {

		private static final long serialVersionUID = 1L;

		public CommandLineException(String string) {
			super(string);
		}
		
	}

	public static void main(String[] args) {
//		WebSocketContainer wsc = ContainerProvider.getWebSocketContainer();
//		wsc.setDefaultMaxBinaryMessageBufferSize();

		SpringApplication app = new SpringApplication(JoatseTargetApplication.class);
		app.setWebApplicationType(WebApplicationType.NONE);
		app.run(args).close();
	}

	@Override
	public void run(ApplicationArguments args) throws Exception {
		Collection<TunnelRequestItemTcp> tcpTunnels = Optional.ofNullable(args.getOptionValues("shareTcp"))
				.orElseGet(() -> Collections.emptyList()).stream().map((String x) -> prepareTcpConfig(x))
				.collect(Collectors.toList());
		
		Collection<TunnelRequestItemHttp> httpTunnels = Optional.ofNullable(args.getOptionValues("shareHttp"))
				.orElseGet(() -> Collections.emptyList()).stream().map((String x) -> prepareHttpConfig(x, false))
				.collect(Collectors.toList());
		
		Optional.ofNullable(args.getOptionValues("shareHttpUnsafe"))
				.orElseGet(() -> Collections.emptyList()).stream().map((String x) -> prepareHttpConfig(x, true))
				.forEachOrdered(x->httpTunnels.add(x));

		Optional<TunnelRequestItemSocks5> socks5Tunnel = prepareSocks5Config(Optional.ofNullable(args.getOptionValues("shareSocks5"))
				.orElseGet(() -> Collections.emptyList()).stream().map((String x) -> prepareSocks5Config(x))
				.collect(Collectors.toList()));

		if (tcpTunnels.isEmpty() && httpTunnels.isEmpty() && !socks5Tunnel.isPresent()) {
			throw new CommandLineException("Expected at least one resource to share");
		}

		Collection<String> preconfirmed = Optional
				.ofNullable(args.getOptionValues("preconfirmed")).orElse(Collections.emptyList());		
		if (preconfirmed.size() > 1) {
			throw new CommandLineException("You can't use more than one --preconfirmed=xxx");
		}		
		Optional<UUID> preconfirmUuid;
		try {
			preconfirmUuid = preconfirmed.stream().map(x->UUID.fromString(x)).findFirst();
		} catch (IllegalArgumentException e) {
			throw new CommandLineException("Invalid preconfirmed value: " + preconfirmed.stream().findFirst().get());
		}

		run(tcpTunnels, httpTunnels, socks5Tunnel, preconfirmUuid);
	}

	private void run(Collection<TunnelRequestItemTcp> tcpTunnels, Collection<TunnelRequestItemHttp> httpTunnels,
			Optional<TunnelRequestItemSocks5> socks5Tunnel, Optional<UUID> preconfirmUuid) throws URISyntaxException {
		while (true) {
			runAndWaitToFinish(tcpTunnels, httpTunnels, socks5Tunnel, preconfirmUuid);
			if (!daemonMode) {
				break;
			} else {
				try {
					Thread.sleep(SLEEP_BETWEEN_CONNECTION_TRIES);
				} catch (InterruptedException e) {
					throw new RuntimeException("Interrupted");
				}
			}
		}
	}

	private void runAndWaitToFinish(Collection<TunnelRequestItemTcp> tcpTunnels,
			Collection<TunnelRequestItemHttp> httpTunnels, Optional<TunnelRequestItemSocks5> socks5Tunnel,
			Optional<UUID> preconfirmUuid) throws URISyntaxException {
		Integer maxTries = Optional.ofNullable(getRetryCount()).map(n -> n + 1).orElse(null);
		int tryNumber = 0;
		while (true) {
			tryNumber++;
			System.out.println("Connection try " + tryNumber + "/"
					+ Optional.ofNullable(maxTries).map(n -> n.toString()).orElse("inf"));
			JoatseClient jc = new JoatseClient(cloudUrl, qrMode);
			try {
				jc.connect().waitUntilConnected();		
				if (jc.isConnected()) {	
					jc.createTunnel(tcpTunnels, httpTunnels, socks5Tunnel, preconfirmUuid);
					jc.waitUntilFinished();
					break;
				} else {
					if (maxTries != null && tryNumber < maxTries) {
						try {
							Thread.sleep(SLEEP_BETWEEN_CONNECTION_TRIES);
						} catch (InterruptedException e) {
							throw new RuntimeException("Interrupted");
						}
					} else {
						break;
					}
				}
			} finally {
				jc.ensureShutdown();
			}
		}
	}
	
	private TunnelRequestItemTcp prepareTcpConfig(String arg) throws CommandLineException {
		Pattern pattern = Pattern.compile("^((.*)#)?([^:#]+):([1-9][0-9]*)$"); // Organization beats optimization here
		Matcher m = pattern.matcher(arg);
		if (m.matches()) {
			String host = m.group(3);
			int port = Integer.parseInt(m.group(4));
			String description = Optional.of(m.group(2)).filter(s->!s.isEmpty()).orElseGet(()->(host + ":" + port));
			try {
				InetAddress.getByName(host); // Fail fast
			} catch (UnknownHostException e) {
				throw new CommandLineException("Unknown host: " + host); 
			}
			return new TunnelRequestItemTcp(host, port, description);
		} else {
			throw new CommandLineException("shareTcp must be description#targetHost:port or targetHost:port");
		}
	}
	
	private TunnelRequestItemHttp prepareHttpConfig(String arg, boolean unsafe) throws CommandLineException {
		Pattern pattern = Pattern.compile("^((.*)#)?(.*)$"); // Organization beats optimization here
		Matcher m = pattern.matcher(arg);
		if (m.matches()) {
			String url = m.group(3);
			String description = Optional.of(m.group(2)).filter(s->!s.isEmpty()).orElseGet(()->(url));
			URL oUrl;
			try {
				oUrl = new URL(url);
			} catch (MalformedURLException e1) {
				throw new CommandLineException("Malformed URL: " + url); 
			}
			try {
				InetAddress.getByName(oUrl.getHost()); // Fail fast
			} catch (UnknownHostException e) {
				throw new CommandLineException("Unknown host: " + oUrl.getHost()); 
			}
			return new TunnelRequestItemHttp(oUrl, description, unsafe);
		} else {
			throw new CommandLineException("shareTcp must be description#targetHost:port or targetHost:port");
		}
	}

	private String prepareSocks5Config(String arg) throws CommandLineException {
		Pattern pattern = Pattern.compile("^([^, :]+(:[0-9]+)?|[*])$");
		Matcher m = pattern.matcher(arg);
		if (m.matches()) {
			return m.group(1);
		} else {
			throw new CommandLineException("shareSocks5 must be targetHost[:port] or *");
		}
	}
	
	private Optional<TunnelRequestItemSocks5> prepareSocks5Config(List<String> items) {
		return items.isEmpty() ? Optional.empty() : Optional.of(new TunnelRequestItemSocks5(items));
	}

	private Integer getRetryCount() {
		return daemonMode ? null : Optional.ofNullable(retryCount).orElse(DEFAULT_RETRY_COUNT);
	}
	
}
