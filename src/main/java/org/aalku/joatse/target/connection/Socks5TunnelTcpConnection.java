package org.aalku.joatse.target.connection;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import org.aalku.joatse.target.JoatseClient.TunnelRequestItemSocks5;
import org.aalku.joatse.target.JoatseSession;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Socks5TunnelTcpConnection extends AbstractTunnelTcpConnection {

	private static Logger log = LoggerFactory.getLogger(Socks5TunnelTcpConnection.class);
	
	private Socks5Proxy proxy;
	
	public Socks5TunnelTcpConnection(JoatseSession session, long socketId, Consumer<Throwable> closeSession, TunnelRequestItemSocks5 req) {
		super(session, socketId, closeSession);
		this.proxy = new Socks5Proxy(req.getAuthorizedTargets(), s->closeSocket(s), bb->{try {
			super.sendDataMessageToCloud(bb).get();
		} catch (Exception e) {
			throw new RuntimeException(e);
		}});
		super.notifyConnected();
		this.proxy.getResult().thenAccept(s->{
			super.tcpRef.set(s);
			super.copyFromTargetToCloudForever();
		});
	}

	private void closeSocket(String msg) {
		log.warn("Socket closed " + getSocketId() + " because: " + msg);
		super.close(null, null); // TODO reason or something
	}

	@Override
	protected Logger getLog() {
		return log;
	}

	@Override
	protected void receivedBytesFromCloud(ByteBuffer buffer) throws IOException {
		synchronized (this) {
			if (super.tcpRef.get() == null) {
				this.proxy.receivedWsBytes(buffer);
			} else {
				try {
					tcpWrite(buffer).get(); // This is blocking to ensure write order. TODO prepare an async version
				} catch (InterruptedException | ExecutionException e) {
					throw new IOException("Error writting to tcp: " + e, e);
				}
			}
		}
	}
	
	@Override
	protected void destroy() {
		this.proxy.close(this.getClass().getSimpleName() + " closed");
		super.destroy();
	}

}
