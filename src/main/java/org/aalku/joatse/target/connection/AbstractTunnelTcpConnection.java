package org.aalku.joatse.target.connection;

import java.net.SocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.AsynchronousSocketChannel;
import java.nio.channels.CompletionHandler;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;

import org.aalku.joatse.target.JoatseSession;
import org.aalku.joatse.target.tools.io.IOTools;

abstract class AbstractTunnelTcpConnection extends AbstractSocketConnection {

	protected final AtomicReference<AsynchronousSocketChannel> tcpRef;


	public AbstractTunnelTcpConnection(JoatseSession manager, long socketId,
			Consumer<Throwable> closeSession) {
		super(manager, socketId, closeSession);
		// TODO use closeSession
		this.tcpRef = new AtomicReference<AsynchronousSocketChannel>();
	}

	/**
	 * Recursively writes all the buffer to tcp.
	 */
	protected CompletableFuture<Integer> tcpWrite(ByteBuffer buffer) {
		CompletableFuture<Integer> res = new CompletableFuture<Integer>();
		AsynchronousSocketChannel channel = tcpRef.get();
		channel.write(buffer, null, new CompletionHandler<Integer, Void>() {
			@Override
			public void completed(Integer result, Void attachment) {
				// log.info("written to tcp: {}", IOTools.toString(buffer, p, result));
				if (buffer.hasRemaining()) {
					tcpWrite(buffer) // Recursively write the rest
					.thenAccept(n -> res.complete(n + result)) // Then complete with all the written
					.exceptionally(e -> { // Or fail
						res.completeExceptionally(e);
						return null;
					});
				} else {
					res.complete(result);
				}
			}
			@Override
			public void failed(Throwable exc, Void attachment) {
				if (exc instanceof AsynchronousCloseException) {
					getLog().error("tcp write fail because the socket was closed");
					close(null, true);
					res.complete(0);
				} else {
					res.completeExceptionally(exc);
				}
			}
		});
		return res;
	}
	
	/**
	 *
	 * @param buffer The buffer has no data. We have to clear it and use it.
	 */
	private void tcpToWs(ByteBuffer buffer) {
		tcpRead(buffer).thenAccept(bytesRead->{
			if (bytesRead < 0) {
				close();
				return;
			}
			buffer.flip();
			sendDataMessageToCloud(buffer).whenCompleteAsync((x, e)->{
				if (e != null) {
					close(e, false);
				} else {
					// log.info("CRC32T2W = {}", Integer.toHexString((int)dataCRCT2W.getValue()) );
					tcpToWs(buffer);
				}
			});
		}).exceptionally(e->{
			close(e, false);
			return null;
		});
	}


	private CompletableFuture<Integer> tcpRead(ByteBuffer readBuffer) {
		readBuffer.clear();
		AsynchronousSocketChannel channel = this.tcpRef.get();
		CompletableFuture<Integer> res = new CompletableFuture<Integer>();
		channel.read(readBuffer, null, new CompletionHandler<Integer, Void>() {
			@Override
			public void completed(Integer result, Void attachment) {
				res.complete(result);
			}
			@Override
			public void failed(Throwable exc, Void attachment) {
				if (exc instanceof AsynchronousCloseException) {
					getLog().error("tcp read fail because the socket was closed");
					close(null, true);
					res.complete(0);
				} else {
					res.completeExceptionally(exc);
				}
			}
		});
		return res;
	}
	
	protected static CompletableFuture<AsynchronousSocketChannel> tcpConnectToTarget(SocketAddress targetAddress) {
		CompletableFuture<AsynchronousSocketChannel> res = new CompletableFuture<AsynchronousSocketChannel>();
		try {
			AsynchronousSocketChannel cs = AsynchronousSocketChannel.open();
			cs.connect(targetAddress, null, new CompletionHandler<Void, Void>() {
				public void completed(Void result, Void a) {
					res.complete(cs);
				}
				public void failed(Throwable e, Void a) {
					// Cleanup only the resource that will not return, then tell the caller
					IOTools.runFailable(()->cs.close());
					res.completeExceptionally(e);
				}
			});
		} catch (Exception e) {
			res.completeExceptionally(e);
		}
		return res;
	}

	@Override
	protected void destroy() {
		IOTools.runFailable(()->tcpRef.get().close());
	}

	@Override
	public void assertClosed() {
		if (tcpRef.get().isOpen()) { // Must be closed
			AssertionError e = new AssertionError("Assertion error. Socket should be closed");
			close(e, false);
			throw e;
		}
		super.assertClosed();
	}

	@Override
	public void copyFromTargetToCloudForever() {
		tcpToWs(allocateDataBuffer()); // start copying from WS to TCP
	}



}
