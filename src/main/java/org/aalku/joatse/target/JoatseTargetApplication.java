	package org.aalku.joatse.target;

import java.net.InetAddress;
import java.net.MalformedURLException;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.aalku.joatse.target.JoatseClient.TunnelRequestItemHttp;
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
	
	@Value("${qr-mode:AUTO}")
	private QrMode qrMode;
	
	@Value("${cloud.url:ws://localhost:9011/connection}")
	private String cloudUrl;
	
//	@Value("${target.host.id:}")
//	private String targetHostId;
//	
//	@Value("${target.host.name:localhost}")
//	private String targetDomain;
//
//	@Value("${target.port:}")
//	private Integer targetPort;
//	
//	@Value("${target.port.description:}")
//	private String targetPortDescription;

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


		if (tcpTunnels.isEmpty() && httpTunnels.isEmpty()) {
			throw new CommandLineException("Expected at least one resource to share");
		}
		if (Boolean.getBoolean("infinite")) { // Useful during development
			while (!Thread.interrupted()) {
				run(tcpTunnels, httpTunnels);
				Thread.sleep(1000);
			}
		} else {
			run(tcpTunnels, httpTunnels);
		}
	}

	private void run(Collection<TunnelRequestItemTcp> tcpTunnels, Collection<TunnelRequestItemHttp> httpTunnels)
			throws URISyntaxException {
		JoatseClient jc = new JoatseClient(cloudUrl, qrMode)
				.connect()
				.waitUntilConnected();
		
		jc.createTunnel(tcpTunnels, httpTunnels);
		jc.waitUntilFinished();
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


}
