package org.aalku.joatse.target.connection;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import org.aalku.joatse.target.JoatseSession;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * File tunnel connection that streams file content with metadata.
 * Unlike other tunnel types, this is a one-way stream from target to cloud.
 */
public class FileTunnelConnection extends AbstractSocketConnection {

	private Logger log = LoggerFactory.getLogger(FileTunnelConnection.class);
	
	private final String filePath;
	private final long offset;
	private final long length;
	private RandomAccessFile raf;

	public FileTunnelConnection(JoatseSession manager, long socketId, Consumer<Throwable> closeSession,
			String filePath, long offset, long length) {
		super(manager, socketId, closeSession);
		this.filePath = filePath;
		this.offset = offset;
		this.length = length;
	}

	@Override
	protected Logger getLog() {
		return log;
	}

	@Override
	protected void receivedBytesFromCloud(ByteBuffer buffer) throws IOException {
		// File tunnels are unidirectional (target -> cloud), so we don't expect data from cloud
		// This is a serious protocol violation
		log.error("Protocol violation: Received unexpected data from cloud for file tunnel. Closing connection.");
		close(new IOException("Protocol violation: unexpected data from cloud"), false);
	}

	/**
	 * Start streaming the file content
	 */
	public void startStreaming() {
		File file = new File(filePath);
		
		log.info("Starting file stream for {} (offset={}, length={})", filePath, offset, length);
		
		try {
			// Validate file
			if (!file.exists()) {
				sendErrorAndClose("Error reading the file: File not found");
				return;
			}
			if (!file.isFile()) {
				sendErrorAndClose("Error reading the file: Not a regular file");
				return;
			}
			if (!file.canRead()) {
				sendErrorAndClose("Error reading the file: Permission denied");
				return;
			}
			
			// Build and send metadata
			JSONObject metadata = new JSONObject();
			metadata.put("fileName", file.getName());
			metadata.put("fileSize", file.length());
			metadata.put("lastModified", file.lastModified());
			
			byte[] metadataBytes = metadata.toString().getBytes(StandardCharsets.UTF_8);
			
			// Create header: status byte (0x01 = success) + metadata length + metadata JSON
			ByteBuffer headerBuffer = ByteBuffer.allocate(1 + 4 + metadataBytes.length);
			headerBuffer.put((byte) 0x01); // success
			headerBuffer.putInt(metadataBytes.length);
			headerBuffer.put(metadataBytes);
			headerBuffer.flip();
			
			log.info("Sending metadata header ({} bytes), will stream content: {}", metadataBytes.length, (length != 0));
			
			// Send header with CRC32
			sendDataMessageToCloud(headerBuffer).thenRun(() -> {
				if (length != 0) {
					log.info("Streaming file content");
					streamFileContent(file);
				} else {
					log.info("Length is 0, closing without streaming content");
					close(null, false);
				}
			}).exceptionally(e -> {
				log.error("Error sending metadata", e);
				close(e, false);
				return null;
			});
			
		} catch (Exception e) {
			log.error("Error starting file stream", e);
			sendErrorAndClose("Error reading the file: " + e.getMessage());
		}
	}

	private void streamFileContent(File file) {
		try {
			raf = new RandomAccessFile(file, "r");
			
			// Validate offset
			if (offset > raf.length()) {
				// Offset beyond file size, just close (no content to send)
				close(null, false);
				return;
			}
			
			raf.seek(offset);
			
			// Calculate bytes to read
			long remaining;
			if (length == -1) {
				// Read entire file from offset
				remaining = raf.length() - offset;
			} else {
				// Read specified length, but not beyond file size
				remaining = Math.min(length, raf.length() - offset);
			}
			
			log.info("Will stream {} bytes from file", remaining);
			// Stream file in chunks
			streamNextChunk(remaining);
			
		} catch (IOException e) {
			log.error("Error streaming file content", e);
			close(e, false);
		}
	}

	private void streamNextChunk(long remaining) {
		try {
			if (remaining <= 0) {
				log.info("Streaming complete, closing connection");
				close(null, false);
				return;
			}
			
			// Use the standard buffer from AbstractSocketConnection
			ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(DATA_BUFFER_SIZE, remaining));
			int toRead = buffer.capacity();
			byte[] tempBuffer = new byte[toRead];
			int read = raf.read(tempBuffer, 0, toRead);
			
			log.info("Read {} bytes from file ({} remaining)", read, remaining);
			
			if (read <= 0) {
				log.info("No more data to read, closing connection");
				close(null, false);
				return;
			}
			
			buffer.put(tempBuffer, 0, read);
			buffer.flip();
			
			// Send with CRC32 validation
			sendDataMessageToCloud(buffer).thenRun(() -> {
				streamNextChunk(remaining - read);
			}).exceptionally(e -> {
				log.error("Error sending file chunk", e);
				close(e, false);
				return null;
			});
			
		} catch (IOException e) {
			log.error("Error reading file chunk", e);
			close(e, false);
		}
	}

	private void sendErrorAndClose(String errorMessage) {
		try {
			JSONObject metadata = new JSONObject();
			metadata.put("error", errorMessage);
			metadata.put("fileName", "");
			metadata.put("fileSize", 0);
			metadata.put("lastModified", 0);
			
			byte[] jsonBytes = metadata.toString().getBytes(StandardCharsets.UTF_8);
			
			// Create header: status byte (0x00 = error) + metadata length + metadata JSON
			ByteBuffer headerBuffer = ByteBuffer.allocate(1 + 4 + jsonBytes.length);
			headerBuffer.put((byte) 0x00); // error
			headerBuffer.putInt(jsonBytes.length);
			headerBuffer.put(jsonBytes);
			headerBuffer.flip();
			
			// Send with CRC32 validation
			sendDataMessageToCloud(headerBuffer).thenRun(() -> {
				close(null, false);
			}).exceptionally(e -> {
				log.error("Error sending error response", e);
				close(e, false);
				return null;
			});
			
		} catch (Exception e) {
			log.error("Error preparing error response", e);
			close(e, false);
		}
	}

	@Override
	protected void destroy() {
		// Close the RandomAccessFile if it's still open
		if (raf != null) {
			try {
				raf.close();
			} catch (IOException e) {
				log.warn("Error closing RandomAccessFile", e);
			}
		}
	}

	@Override
	protected void copyFromTargetToCloudForever() {
		// For file tunnels, we start streaming when requested
		// This method is called when the connection is established
		startStreaming();
	}
}
