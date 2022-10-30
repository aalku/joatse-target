package org.aalku.joatse.target.tools.io;


import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;

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

}