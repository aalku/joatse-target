package org.aalku.joatse.target.connection;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousSocketChannel;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Function;

import org.aalku.joatse.target.JoatseSession;
import org.aalku.joatse.target.tools.io.IOTools;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BasicTunnelTcpConnection extends AbstractTunnelTcpConnection {
	
	private static Logger log = LoggerFactory.getLogger(BasicTunnelTcpConnection.class);
	private final InetSocketAddress targetAddress;

	@Override
	protected Logger getLog() {
		return log;
	}

	public BasicTunnelTcpConnection(JoatseSession manager, InetSocketAddress targetAddress, long socketId,
			Consumer<Throwable> closeSession) {
		super(manager, socketId, closeSession);
		this.targetAddress = targetAddress;
		connect();
	}

	private void connect() {
		/**
		 * Was newTcpSocketResponse(portId, true) sent?
		 */
		AtomicBoolean newTcpSocketMessageSent = new AtomicBoolean(false);
		
		tcpConnectToTarget(targetAddress).exceptionally(e->{
			throw new RuntimeException("Exception creating connection to "
					+ targetAddress.getHostString() + ":" + targetAddress.getPort(), e);
		}).thenCompose((Function<AsynchronousSocketChannel, CompletableFuture<AsynchronousSocketChannel>>)(tcp)->{
			tcpRef.set(tcp);
			CompletableFuture<AsynchronousSocketChannel> res = new CompletableFuture<AsynchronousSocketChannel>();
			notifyConnected(socketId).handle((x, e)->{
				if (e != null) {
					res.completeExceptionally(e);
				} else {
					newTcpSocketMessageSent.set(true);
					res.complete(tcp);
				}
				return null;
			});
			return res;
		}).thenCompose((Function<AsynchronousSocketChannel, CompletableFuture<Void>>)tcp->{
			super.runSocket();
			return CompletableFuture.completedFuture(null);
		}).exceptionally(e->{
			if (newTcpSocketMessageSent.get()) {
				log.warn("Aborting tcp tunnel {} just after creation: " + e, socketId);
				this.close(e, true);
				return null;
			} else {
				log.warn("Aborting tcp tunnel {} during creation: " + e, socketId);
				// Do not call this.close() as that's for stablished connections
				IOTools.runFailable(()->this.tcpRef.get().close());
				notifyCantConnect(socketId).handle((x,e2)->{
					close(e, null);
					return null;
				});
				return null;
			}
		});
	}
	
	@Override
	protected void receivedWsBytes(ByteBuffer buffer) throws IOException {
		try {
			tcpWrite(buffer).get(); // This is blocking to ensure write order. TODO prepare an async version
		} catch (InterruptedException | ExecutionException e) {
			throw new IOException("Error writting to tcp: " + e, e);
		}
	}

}
