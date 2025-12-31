	package org.aalku.joatse.target;

import java.io.File;
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
import org.aalku.joatse.target.JoatseClient.TunnelRequestItemFile;
import org.aalku.joatse.target.JoatseClient.TunnelRequestItemFolder;
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

		Collection<TunnelRequestItemFile> fileTunnels = parseFileShareArgs(args);

		Collection<TunnelRequestItemFolder> folderTunnels = parseFolderShareArgs(args);

		if (tcpTunnels.isEmpty() && httpTunnels.isEmpty() && !socks5Tunnel.isPresent() && commandTunnels.isEmpty() && fileTunnels.isEmpty() && folderTunnels.isEmpty()) {
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
		
		run(tcpTunnels, httpTunnels, socks5Tunnel, commandTunnels, fileTunnels, folderTunnels, preconfirmUuid, autoAuthorizeByHttpUrl);
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

	private Collection<TunnelRequestItemFile> parseFileShareArgs(ApplicationArguments args) {
		Collection<TunnelRequestItemFile> fileTunnels = new ArrayList<>();
		List<String> shareFileKeys = args.getOptionNames().stream().filter(n -> n.startsWith("shareFile"))
				.collect(Collectors.toList());
		for (String k: shareFileKeys) {
			List<String> values = args.getOptionValues(k);
			for (String value: values) {
				TunnelRequestItemFile config = prepareFileConfig(value);
				fileTunnels.add(config);
			}
		}
		return fileTunnels;
	}

	private Collection<TunnelRequestItemFolder> parseFolderShareArgs(ApplicationArguments args) {
		Collection<TunnelRequestItemFolder> folderTunnels = new ArrayList<>();
		// shareFolder for read-only, shareFolderRW for read-write
		List<String> shareFolderKeys = args.getOptionNames().stream()
				.filter(n -> n.equals("shareFolder") || n.equals("shareFolderRW"))
				.collect(Collectors.toList());
		for (String k: shareFolderKeys) {
			boolean readOnly = k.equals("shareFolder");
			List<String> values = args.getOptionValues(k);
			for (String value: values) {
				TunnelRequestItemFolder config = prepareFolderConfig(value, readOnly);
				folderTunnels.add(config);
			}
		}
		return folderTunnels;
	}

	private void run(Collection<TunnelRequestItemTcp> tcpTunnels, Collection<TunnelRequestItemHttp> httpTunnels,
			Optional<TunnelRequestItemSocks5> socks5Tunnel, Collection<TunnelRequestItemCommand> commandTunnels,
			Collection<TunnelRequestItemFile> fileTunnels, Collection<TunnelRequestItemFolder> folderTunnels,
			Optional<UUID> preconfirmUuid,
			boolean autoAuthorizeByHttpUrl) throws URISyntaxException {
		while (true) {
			runAndWaitToFinish(tcpTunnels, httpTunnels, socks5Tunnel, commandTunnels, fileTunnels, folderTunnels, preconfirmUuid, autoAuthorizeByHttpUrl);
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
			Collection<TunnelRequestItemCommand> commandTunnels, Collection<TunnelRequestItemFile> fileTunnels,
			Collection<TunnelRequestItemFolder> folderTunnels,
			Optional<UUID> preconfirmUuid, boolean autoAuthorizeByHttpUrl) throws URISyntaxException {
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
					jc.createTunnel(tcpTunnels, httpTunnels, socks5Tunnel, commandTunnels, fileTunnels, folderTunnels, preconfirmUuid, autoAuthorizeByHttpUrl);
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
			String description = m.group(2); // Don't filter empty here, let utility handle it
			try {
				InetAddress.getByName(host); // Fail fast
			} catch (UnknownHostException e) {
				throw new CommandLineException("Unknown host: " + host); 
			}
			// Use utility to ensure consistent description logic
			String finalDescription = getDefaultTcpDescription(description, host, port);
			return new TunnelRequestItemTcp(host, port, finalDescription);
		} else {
			throw new CommandLineException("shareTcp must be description#targetHost:port or targetHost:port");
		}
	}
	
	private TunnelRequestItemCommand prepareCommandConfig(String arg) throws CommandLineException {
		Pattern pattern = Pattern.compile("^((.*)#)?([^@]*)@([^:@]+)(:([1-9][0-9]*))?@(.+)$"); // desc=2, user=3, host=4, port=6, cmd=7
		Matcher m = pattern.matcher(arg);
		if (m.matches()) {
			String[] commandArray = CommandLineParser.parseCommandLine(m.group(7));
			String description = m.group(2); // Don't filter empty here, let utility handle it
			String user = m.group(3);
			String host = Optional.ofNullable(m.group(4)).orElse("localhost");
			int port = Optional.ofNullable(m.group(6)).map(x->Integer.parseInt(x)).orElse(22);
			// Use utility to ensure consistent description logic
			String finalDescription = getDefaultCommandDescription(description, commandArray);
			return new TunnelRequestItemCommand(commandArray, user, host, port, finalDescription);
		} else {
			throw new CommandLineException("shareCommand must be description#user@[sshHost][:sshPort]@commandLine or user@[sshHost][:sshPort]@commandLine. The commandLines is a single string with escaped or quoted spaces between arguments. Eg: --shareCommand=root@@/bin/bash or  '--shareCommand=MyCommand#root@localhost@/bin/myCommand \"arg1 with spaces\" arg2'");
		}
	}

	
	private TunnelRequestItemHttp prepareHttpConfig(String arg, boolean unsafe, boolean hideProxy) throws CommandLineException {
		Pattern pattern = Pattern.compile("^((.*)#)?(.*)$"); // Organization beats optimization here
		Matcher m = pattern.matcher(arg);
		if (m.matches()) {
			String url = m.group(3);
			String description = m.group(2); // Don't filter empty here, let utility handle it
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
			// Use utility to ensure consistent description logic
			String finalDescription = getDefaultHttpDescription(description, oUrl);
			return new TunnelRequestItemHttp(oUrl, finalDescription, unsafe, hideProxy);
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
	
	/**
	 * Generate default description for HTTP tunnel if not provided.
	 * This matches the logic used in the cloud service for consistency.
	 */
	private static String getDefaultHttpDescription(String description, URL targetUrl) {
		return Optional.ofNullable(description)
				.filter(s -> !s.isEmpty())
				.orElse(targetUrl.toString());
	}
	
	/**
	 * Generate default description for TCP tunnel if not provided.
	 * This matches the logic used in the cloud service for consistency.
	 */
	private static String getDefaultTcpDescription(String description, String targetHostname, int targetPort) {
		return Optional.ofNullable(description)
				.filter(s -> !s.isEmpty())
				.orElse(targetHostname + ":" + targetPort);
	}
	
	/**
	 * Generate default description for command tunnel if not provided.
	 * This matches the logic used in the cloud service for consistency.
	 */
	private static String getDefaultCommandDescription(String description, String[] commandArray) {
		return Optional.ofNullable(description)
				.filter(s -> !s.isEmpty())
				.orElseGet(() -> {
					if (commandArray != null && commandArray.length > 0) {
						String desc = commandArray[0].replaceAll("\\s+", "").replaceAll("[^a-zA-Z0-9]+", "_")
								.replaceAll("__+", "_").replaceAll("^_+|_+$", "");
						return desc.substring(0, Math.min(desc.length(), 8));
					}
					return "command";
				});
	}
	
	private TunnelRequestItemFile prepareFileConfig(String arg) throws CommandLineException {
		Pattern pattern = Pattern.compile("^((.*)#)?(.+)$");
		Matcher m = pattern.matcher(arg);
		if (m.matches()) {
			String description = m.group(2);
			String path = m.group(3);
			
			// Convert to absolute path
			File file = new File(path);
			String absolutePath;
			try {
				absolutePath = file.getAbsolutePath();
			} catch (SecurityException e) {
				throw new CommandLineException("Cannot access file: " + path);
			}
			
			// Validate file
			if (!file.exists()) {
				throw new CommandLineException("File does not exist: " + absolutePath);
			}
			
			// Accept regular files and symlinks
			if (!file.isFile()) {
				throw new CommandLineException("Not a regular file or symlink: " + absolutePath);
			}
			
			if (!file.canRead()) {
				throw new CommandLineException("File is not readable: " + absolutePath);
			}
			
			String fileName = file.getName();
			String finalDescription = getDefaultFileDescription(description, absolutePath);
			return new TunnelRequestItemFile(absolutePath, finalDescription, fileName);
		} else {
			throw new CommandLineException("shareFile must be description#path or path");
		}
	}
	
	/**
	 * Generate default description for file tunnel if not provided.
	 * This matches the logic used in the cloud service for consistency.
	 */
	private static String getDefaultFileDescription(String description, String path) {
		return Optional.ofNullable(description)
				.filter(s -> !s.isEmpty())
				.orElse(new File(path).getName());
	}
	
	private TunnelRequestItemFolder prepareFolderConfig(String arg, boolean readOnly) throws CommandLineException {
		Pattern pattern = Pattern.compile("^((.*)#)?(.+)$");
		Matcher m = pattern.matcher(arg);
		if (m.matches()) {
			String description = m.group(2);
			String path = m.group(3);
			
			// Convert to absolute path
			File folder = new File(path);
			String absolutePath;
			try {
				absolutePath = folder.getAbsolutePath();
			} catch (SecurityException e) {
				throw new CommandLineException("Cannot access folder: " + path);
			}
			
			// Validate folder
			if (!folder.exists()) {
				throw new CommandLineException("Folder does not exist: " + absolutePath);
			}
			
			if (!folder.isDirectory()) {
				throw new CommandLineException("Not a directory: " + absolutePath);
			}
			
			if (!folder.canRead()) {
				throw new CommandLineException("Folder is not readable: " + absolutePath);
			}
			
			String finalDescription = getDefaultFolderDescription(description, absolutePath);
			return new TunnelRequestItemFolder(absolutePath, finalDescription, readOnly);
		} else {
			throw new CommandLineException("shareFolder must be description#path or path");
		}
	}
	
	/**
	 * Generate default description for folder tunnel if not provided.
	 * This matches the logic used in the cloud service for consistency.
	 */
	private static String getDefaultFolderDescription(String description, String path) {
		return Optional.ofNullable(description)
				.filter(s -> !s.isEmpty())
				.orElse(new File(path).getName());
	}
	
}
