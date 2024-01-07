package org.aalku.joatse.target.connection;

import java.nio.ByteBuffer;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public interface TunnelConnection {

	public static final byte MESSAGE_TYPE_NEW_SOCKET = 1;
	static final byte MESSAGE_SOCKET_DATA = 2;
	static final byte MESSAGE_SOCKET_CLOSE = 3;
	static final byte MESSAGE_PUBLIC_KEY = 4;
	
	public static final Set<Byte> supportedMessages = new HashSet<>(Arrays.asList(MESSAGE_SOCKET_DATA, MESSAGE_SOCKET_CLOSE));

	long getSocketId();

	CompletableFuture<Boolean> getCloseStatus();

	void assertClosed();

	Runnable receivedTunnelMessage(ByteBuffer buffer, byte type);

	void close();

	void close(Throwable e, Boolean b);

}
