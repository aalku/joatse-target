package org.aalku.joatse.target.tools.io;


import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.Arrays;
import java.util.HashSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public interface IOTools {
	public interface IOTask<E> {
		E call() throws IOException;
	}
	public interface FailableTask {
		void run() throws Exception;
	}

	public static <X> X runUnchecked(IOTask<X> task) {
		try {
			return task.call();
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	public static <X> boolean runFailable(FailableTask task) {
		try {
			task.run();
			return true;
		} catch (Exception e) {
			return false;
		}
	}

	public static void closeChannel(AsynchronousSocketChannel channel) {
		runFailable(()->channel.shutdownInput());
		runFailable(()->channel.shutdownOutput());
		runFailable(()->channel.close());
	}
	
	public static String toString(ByteBuffer data, int position, int length) {
		StringBuilder sb = new StringBuilder();
		byte[] a = data.array();
		int aStart = data.arrayOffset() + position;
		int aEnd = aStart + length;
		for (int i = aStart; i < aEnd; i++) {
			sb.append(':');
			byte c = a[i];
			sb.append(String.format("%02x", c & 0xFF));
		}
		return sb.toString().substring(1);
	}

	public static String toString(ByteBuffer data) {
		return toString(data, data.position(), data.limit() - data.position());
	}

	public static Pattern globToRegex(String allowedAddress, boolean caseSensitive) {
		StringBuffer sb = new StringBuffer();
		int flags = caseSensitive ? 0 : Pattern.CASE_INSENSITIVE;
		Pattern p = Pattern.compile("[*]|[^\\w*]+", flags);
		Matcher m = p.matcher(allowedAddress);
		while (m.find()) {
			String x = m.group();
			m.appendReplacement(sb, x.equals("*") ? ".*" : Matcher.quoteReplacement(Pattern.quote(x)));
		}
		m.appendTail(sb);
		return Pattern.compile(sb.toString());
	}

	static boolean testInetAddressPatternMatch(String allowedAddress, InetSocketAddress target) {
		// TODO IPv6 support
		final String allowedHost;
		final Integer allowedPort;
		String[] split = allowedAddress.split(":", 2);
		allowedHost = split[0];
		allowedPort = split.length > 1 ? split[1].equals("*") ? null : Integer.parseInt(split[1]) : null;
		
		if (allowedPort != null && !allowedPort.equals(target.getPort())) {
			// Ports don't match
			return false;
		}		
		// Ports do match
		
		if (allowedHost.equals("*")) {
			return true;
		}
		
		String targetHost = target.getHostString();
		Pattern p = IOTools.globToRegex(allowedHost, false);
		boolean matches = p.matcher(targetHost).matches();
		
		if (!matches) {
			/* Let's see if any is a host and can ve resolver to addresses and match that way */
			try {
				HashSet<InetAddress> t = new HashSet<>(Arrays.asList(InetAddress.getAllByName(targetHost)));
				matches = (allowedHost.contains("*") ? Arrays.asList(allowedHost)
						: Arrays.asList(InetAddress.getAllByName(allowedHost))).stream().filter(x -> t.contains(x))
						.findAny().isPresent();
			} catch (UnknownHostException e) {
				return false;
			}
		}
		
		return matches;
	}
	
}