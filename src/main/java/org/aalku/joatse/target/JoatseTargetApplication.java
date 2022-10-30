	package org.aalku.joatse.target;

import java.net.InetAddress;
import java.net.UnknownHostException;

import org.aalku.joatse.target.tools.QrGenerator.QrMode;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication(scanBasePackages = {"org.aalku.joatse.target.cloud"})
public class JoatseTargetApplication implements CommandLineRunner {

	@Value("${qr-mode:AUTO}")
	private QrMode qrMode;
	
	@Value("${cloud.url:ws://localhost:9011/connection}")
	private String cloudUrl;
	
	@Value("${target.host.id:}")
	private String targetHostId;
	
	@Value("${target.host.name:localhost}")
	private String targetHostname;
	
	private InetAddress targetInetAddress;

	@Value("${target.port:}")
	private Integer targetPortNumber;
	
	@Value("${target.port.description:}")
	private String targetPortDescription;

	private static class CommandLineException extends Exception {

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
	public void run(String... args) throws Exception {
		
		prepareConfig();
		
		JoatseClient jc = new JoatseClient(targetHostId, targetHostname, targetInetAddress, cloudUrl, qrMode)
				.connect()
				.waitUntilConnected();
		if (targetPortNumber != null) {
			jc.shareTcpPort(targetPortDescription, targetPortNumber);
		}
		
		jc.waitUntilFinished();
	}

	private void prepareConfig() throws CommandLineException, UnknownHostException {
		if (targetHostId == null || targetHostId.trim().isEmpty()) {
			targetHostId = System.getenv("HOSTNAME");
		}
		if (targetHostId == null || targetHostId.trim().isEmpty()) {
			targetHostId = System.getenv("HOST");
		}
		if (targetHostId == null || targetHostId.trim().isEmpty()) {
			targetHostId = null;
		}
		if (targetPortNumber == null) {
			throw new CommandLineException("You have to define a target port. Use: --target.port=<port>");
		}
		targetInetAddress = InetAddress.getByName(targetHostname);
	}

}
