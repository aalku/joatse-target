	package org.aalku.joatse.target;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.Collection;
import java.util.Collections;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import org.aalku.joatse.target.JoatseClient.TunnelRequestItem;
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
//	private String targetHostname;
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
		Collection<TunnelRequestItem> tcpTunnels = Optional.ofNullable(args.getOptionValues("shareTcp"))
				.orElseGet(() -> Collections.emptyList()).stream().map((String x) -> prepareSocketConfig(x))
				.collect(Collectors.toList());
		
		if (tcpTunnels.isEmpty()) {
			throw new CommandLineException("Expected at least one resource to share");
		}

		JoatseClient jc = new JoatseClient(cloudUrl, qrMode)
				.connect()
				.waitUntilConnected();
		
		jc.createTunnel(tcpTunnels);		
		jc.waitUntilFinished();
	}
	
	private TunnelRequestItem prepareSocketConfig(String arg) throws CommandLineException {
		Pattern pattern = Pattern.compile("^((.*)#)?([^:#]+):([1-9][0-9]*)$"); // Organization beats optimization here
		Matcher m = pattern.matcher(arg);
		if (m.matches()) {
			String host = m.group(3);
			int port = Integer.parseInt(m.group(4));
			String description = Optional.of(m.group(1)).filter(s->!s.isEmpty()).orElseGet(()->(host + ":" + port));
			try {
				InetAddress.getByName(host); // Fail fast
			} catch (UnknownHostException e) {
				throw new CommandLineException("Unknown host: " + host); 
			}
			return new TunnelRequestItem(host, port, description);
		} else {
			throw new CommandLineException("shareTcp must be description#targetHost:port or targetHost:port");
		}
	}

}
