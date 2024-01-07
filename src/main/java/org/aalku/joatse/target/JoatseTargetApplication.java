	package org.aalku.joatse.target;

import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.aalku.joatse.target.JoatseClient.TunnelRequestItemCommand;
import org.aalku.joatse.target.JoatseClient.TunnelRequestItemHttp;
import org.aalku.joatse.target.JoatseClient.TunnelRequestItemSocks5;
import org.aalku.joatse.target.JoatseClient.TunnelRequestItemTcp;
import org.aalku.joatse.target.tools.QrGenerator.QrMode;
import org.aalku.joatse.target.tools.io.CommandLineParser;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"org.aalku.joatse.target.cloud"})
public class JoatseTargetApplication implements ApplicationRunner, DisposableBean {
	
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

	private volatile JoatseClient jc;

	private volatile boolean closed = false;
	
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
		Collection<TunnelRequestItemTcp> tcpTunnels = parseTcpShareArgs(args);
		
		Collection<TunnelRequestItemHttp> httpTunnels = parseHttpShareArgs(args);

		Optional<TunnelRequestItemSocks5> socks5Tunnel = parseSocks5ShareArgs(args);

		Collection<TunnelRequestItemCommand> commandTunnels = parseCommandShareArgs(args);

		if (tcpTunnels.isEmpty() && httpTunnels.isEmpty() && !socks5Tunnel.isPresent() && commandTunnels.isEmpty()) {
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
		boolean autoAuthorizeByHttpUrl = Optional.ofNullable(args.getOptionValues("autoAuthorizeByHttpUrl"))
				.map(x -> x.isEmpty() || Boolean.parseBoolean(x.get(0))).orElse(false);
		
		run(tcpTunnels, httpTunnels, socks5Tunnel, commandTunnels, preconfirmUuid, autoAuthorizeByHttpUrl);
	}

	private Optional<TunnelRequestItemSocks5> parseSocks5ShareArgs(ApplicationArguments args) {
		Optional<TunnelRequestItemSocks5> socks5Tunnel = prepareSocks5Config(Optional.ofNullable(args.getOptionValues("shareSocks5"))
				.orElseGet(() -> Collections.emptyList()).stream().map((String x) -> prepareSocks5Config(x))
				.collect(Collectors.toList()));
		return socks5Tunnel;
	}

	private Collection<TunnelRequestItemHttp> parseHttpShareArgs(ApplicationArguments args) {
		Collection<TunnelRequestItemHttp> httpTunnels = new ArrayList<>();
		List<String> shareHttpKeys = args.getOptionNames().stream().filter(n -> n.startsWith("shareHttp"))
				.collect(Collectors.toList());
		for (String k: shareHttpKeys) {
			boolean unsafe = k.contains("Unsafe");
			boolean hideProxy = k.contains("HideProxy");
			for (String value: args.getOptionValues(k)) {
				TunnelRequestItemHttp config = prepareHttpConfig(value, unsafe, hideProxy);
				httpTunnels.add(config);
			}
		}
		return httpTunnels;
	}

	private Collection<TunnelRequestItemTcp> parseTcpShareArgs(ApplicationArguments args) {
		Collection<TunnelRequestItemTcp> tcpTunnels = Optional.ofNullable(args.getOptionValues("shareTcp"))
				.orElseGet(() -> Collections.emptyList()).stream().map((String x) -> prepareTcpConfig(x))
				.collect(Collectors.toList());
		return tcpTunnels;
	}

	private Collection<TunnelRequestItemCommand> parseCommandShareArgs(ApplicationArguments args) {
		Collection<TunnelRequestItemCommand> commandTunnels = Optional.ofNullable(args.getOptionValues("shareCommand"))
				.orElseGet(() -> Collections.emptyList()).stream().map((String x) -> prepareCommandConfig(x))
				.collect(Collectors.toList());
		return commandTunnels;
	}

	private void run(Collection<TunnelRequestItemTcp> tcpTunnels, Collection<TunnelRequestItemHttp> httpTunnels,
			Optional<TunnelRequestItemSocks5> socks5Tunnel, Collection<TunnelRequestItemCommand> commandTunnels, Optional<UUID> preconfirmUuid,
			boolean autoAuthorizeByHttpUrl) throws URISyntaxException {
		while (true) {
			runAndWaitToFinish(tcpTunnels, httpTunnels, socks5Tunnel, commandTunnels, preconfirmUuid, autoAuthorizeByHttpUrl);
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
			Collection<TunnelRequestItemCommand> commandTunnels, Optional<UUID> preconfirmUuid, boolean autoAuthorizeByHttpUrl) throws URISyntaxException {
		Integer maxTries = Optional.ofNullable(getRetryCount()).map(n -> n + 1).orElse(null);
		int tryNumber = 0;
		while (!closed ) {
			tryNumber++;
			System.out.println("Connection try " + tryNumber + "/"
					+ Optional.ofNullable(maxTries).map(n -> n.toString()).orElse("inf"));
			jc = new JoatseClient(cloudUrl, qrMode);
			try {
				jc.connect().waitUntilConnected();		
				if (jc.isConnected()) {	
					jc.createTunnel(tcpTunnels, httpTunnels, socks5Tunnel, commandTunnels, preconfirmUuid, autoAuthorizeByHttpUrl);
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
			String description = Optional.ofNullable(m.group(2)).filter(s->!s.isEmpty()).orElseGet(()->(host + ":" + port));
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
	
	private TunnelRequestItemCommand prepareCommandConfig(String arg) throws CommandLineException {
		Pattern pattern = Pattern.compile("^((.*)#)?([^@]*)@([^:@]+)(:([1-9][0-9]*))?@(.+)$"); // desc=2, user=3, host=4, port=6, cmd=7
		Matcher m = pattern.matcher(arg);
		if (m.matches()) {
			String[] commandArray = CommandLineParser.parseCommandLine(m.group(7));
			String description = Optional.ofNullable(m.group(2)).filter(s->!s.isEmpty()).orElseGet(()->{
				String desc = commandArray[0].replaceAll("\\s+", "").replaceAll("[^a-zA-Z0-9]+", "_")
						.replaceAll("__+", "_").replaceAll("^_+|_+$", "");
				return desc.substring(0, Math.min(desc.length(), 8));
			});
			String user = m.group(3);
			String host = Optional.ofNullable(m.group(4)).orElse("localhost");
			int port = Optional.ofNullable(m.group(6)).map(x->Integer.parseInt(x)).orElse(22);
			return new TunnelRequestItemCommand(commandArray, user, host, port, description);
		} else {
			throw new CommandLineException("shareCommand must be description#user@[sshHost][:sshPort]@commandLine or user@[sshHost][:sshPort]@commandLine. The commandLines is a single string with escaped or quoted spaces between arguments. Eg: --shareCommand=root@@/bin/bash or  '--shareCommand=MyCommand#root@localhost@/bin/myCommand \"arg1 with spaces\" arg2'");
		}
	}

	
	private TunnelRequestItemHttp prepareHttpConfig(String arg, boolean unsafe, boolean hideProxy) throws CommandLineException {
		Pattern pattern = Pattern.compile("^((.*)#)?(.*)$"); // Organization beats optimization here
		Matcher m = pattern.matcher(arg);
		if (m.matches()) {
			String url = m.group(3);
			String description = Optional.ofNullable(m.group(2)).filter(s->!s.isEmpty()).orElseGet(()->(url));
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
			return new TunnelRequestItemHttp(oUrl, description, unsafe, hideProxy);
		} else {
			throw new CommandLineException("shareHttp must be description#URL or URL");
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

	@Override
	public void destroy() throws Exception {
		closed = true;
		if (jc != null) {
			jc.shutdown();
		}
	}
	
}
