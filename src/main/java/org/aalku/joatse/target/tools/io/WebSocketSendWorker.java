package org.aalku.joatse.target.tools.io;

import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.LinkedBlockingQueue;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.socket.WebSocketMessage;
import org.springframework.web.socket.WebSocketSession;

public class WebSocketSendWorker extends Thread {

	private Logger log = LoggerFactory.getLogger(WebSocketSendWorker.class);

	private static class Item {

		private final WebSocketMessage<?> message;
		private final CompletableFuture<Void> future;

		public Item(WebSocketMessage<?> message) {
			this.message = message;
			this.future = new CompletableFuture<Void>();
		}

	}

	private BlockingQueue<Item> queue;
	private WebSocketSession session;

	public WebSocketSendWorker(WebSocketSession session) {
		this.session = session;
		this.queue = new LinkedBlockingQueue<>();
		this.setName("wssw_" + session.getId());
		this.start();
	}

	public CompletableFuture<Void> sendMessage(WebSocketMessage<?> message) {
		Item item = new Item(message);
		queue.add(item);
		return item.future;
	}
	
	@Override
	public void run() {
		while (session.isOpen()) {
			try {
				Item item = queue.take();
				try {
					session.sendMessage(item.message);
				} catch (Exception e) {
					item.future.completeExceptionally(e);
					continue;
				}
				item.future.complete(null);
			} catch (InterruptedException e) {
				log.error("{} thread {} interrupted. Closing session.", WebSocketSendWorker.class.getSimpleName(), this);
				this.close();
			}
		}
	}
	
	public void close() {
		IOTools.runFailable(()->this.session.close());
		this.interrupt();
	}

}
