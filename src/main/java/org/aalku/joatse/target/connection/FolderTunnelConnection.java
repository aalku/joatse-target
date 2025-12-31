package org.aalku.joatse.target.connection;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.aalku.joatse.target.JoatseSession;
import org.json.JSONObject;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Folder tunnel connection that handles folder operations (LIST, STAT, READ, WRITE, etc.)
 * Similar to FileTunnelConnection but supports multiple operation types.
 */
public class FolderTunnelConnection extends AbstractSocketConnection {

	private Logger log = LoggerFactory.getLogger(FolderTunnelConnection.class);
	
	private final String rootPath;
	private final boolean readOnly;
	private final FolderOpCode opCode;
	private final String requestedPath;
	private final ByteBuffer operationPayload;
	
	// For READ operation - stores the RandomAccessFile across async callbacks
	private RandomAccessFile raf;
	
	// For WRITE operation - stores state across async data reception
	private RandomAccessFile writeRaf;
	private Path writePath;
	private long writeExpectedLength;
	private long writeBytesReceived;

	/**
	 * Folder operation codes
	 */
	public enum FolderOpCode {
		LIST((byte) 0x01),
		STAT((byte) 0x02),
		READ((byte) 0x03),
		WRITE((byte) 0x04),
		MKDIR((byte) 0x05),
		DELETE((byte) 0x06),
		RMDIR((byte) 0x07),
		MOVE((byte) 0x08);
		
		private final byte code;
		
		FolderOpCode(byte code) {
			this.code = code;
		}
		
		public byte getCode() {
			return code;
		}
		
		public static FolderOpCode fromCode(byte code) throws IOException {
			for (FolderOpCode op : values()) {
				if (op.code == code) {
					return op;
				}
			}
			throw new IOException("Unknown folder operation code: 0x" + String.format("%02X", code));
		}
	}

	/**
	 * Sort field for LIST operation
	 */
	public enum SortBy {
		NONE((byte) 0x00, "none"),
		NAME((byte) 0x01, "name"),
		SIZE((byte) 0x02, "size"),
		MODIFIED((byte) 0x03, "modified");
		
		private final byte code;
		private final String jsonName;
		
		SortBy(byte code, String jsonName) {
			this.code = code;
			this.jsonName = jsonName;
		}
		
		public byte getCode() {
			return code;
		}
		
		public String getJsonName() {
			return jsonName;
		}
		
		public static SortBy fromCode(byte code) throws IOException {
			for (SortBy sort : values()) {
				if (sort.code == code) {
					return sort;
				}
			}
			throw new IOException("Unknown sort field code: 0x" + String.format("%02X", code));
		}
	}

	/**
	 * Sort order for LIST operation
	 */
	public enum SortOrder {
		ASCENDING((byte) 0x00, "ascending"),
		DESCENDING((byte) 0x01, "descending");
		
		private final byte code;
		private final String jsonName;
		
		SortOrder(byte code, String jsonName) {
			this.code = code;
			this.jsonName = jsonName;
		}
		
		public byte getCode() {
			return code;
		}
		
		public String getJsonName() {
			return jsonName;
		}
		
		public static SortOrder fromCode(byte code) throws IOException {
			for (SortOrder order : values()) {
				if (order.code == code) {
					return order;
				}
			}
			throw new IOException("Unknown sort order code: 0x" + String.format("%02X", code));
		}
	}

	public FolderTunnelConnection(JoatseSession manager, long socketId, Consumer<Throwable> closeSession,
			String rootPath, boolean readOnly, ByteBuffer payload) throws IOException {
		super(manager, socketId, closeSession);
		this.rootPath = rootPath;
		this.readOnly = readOnly;
		
		// Parse payload: opCode (1 byte) + pathLength (4 bytes) + path (UTF-8) + operation-specific data
		if (payload.remaining() < 5) { // At least opCode + pathLength
			throw new IOException("Invalid folder request payload: expected at least 5 bytes, got " + payload.remaining());
		}
		
		byte opCodeByte = payload.get();
		this.opCode = FolderOpCode.fromCode(opCodeByte);
		
		int pathLength = payload.getInt();
		if (pathLength < 0 || pathLength > payload.remaining()) {
			throw new IOException("Invalid path length: " + pathLength + " (remaining: " + payload.remaining() + ")");
		}
		
		byte[] pathBytes = new byte[pathLength];
		payload.get(pathBytes);
		this.requestedPath = new String(pathBytes, StandardCharsets.UTF_8);
		
		// Store remaining bytes as operation-specific payload
		this.operationPayload = payload.slice();
		
		log.debug("FolderTunnelConnection created: op={}, path={}, readOnly={}", opCode, requestedPath, readOnly);
	}

	/**
	 * Start executing the folder operation.
	 * Must be called after connection is registered with JoatseSession.
	 */
	public void start() {
		log.debug("FolderTunnelConnection.start(): op={}, path={}, socketId={}", opCode, requestedPath, getSocketId());
		try {
			executeOperation();
		} catch (Exception e) {
			log.error("Error executing folder operation {} on path {}: {}", opCode, requestedPath, e.getMessage(), e);
			sendErrorAndClose("Error executing operation: " + e.getMessage());
		}
	}

	@Override
	protected Logger getLog() {
		return log;
	}

	@Override
	protected void receivedBytesFromCloud(ByteBuffer buffer) throws IOException {
		// For WRITE operations, we need to accept data from cloud
		if (opCode == FolderOpCode.WRITE) {
			handleWriteData(buffer);
		} else {
			// For other operations (LIST, STAT, READ, etc.), we don't expect data from cloud
			log.error("Protocol violation: Received unexpected data from cloud for {} operation. Closing connection.", opCode);
			close(new IOException("Protocol violation: unexpected data from cloud for " + opCode), false);
		}
	}

	private void executeOperation() {
		log.debug("executeOperation: op={}, path={}, socketId={}", opCode, requestedPath, getSocketId());
		// Validate path is within root before executing any operation
		Path resolvedPath;
		try {
			resolvedPath = resolveSafePath(requestedPath);
		} catch (SecurityException e) {
			log.warn("Path traversal attempt detected: {}", requestedPath);
			sendErrorAndClose("PATH_OUTSIDE_ROOT", "Path traversal attempt detected");
			return;
		} catch (IOException e) {
			log.error("Error resolving path: {}", e.getMessage());
			sendErrorAndClose("IO_ERROR", "Error resolving path: " + e.getMessage());
			return;
		}
		
		// Route to appropriate handler
		switch (opCode) {
			case LIST:
				handleList(resolvedPath);
				break;
			case STAT:
				handleStat(resolvedPath);
				break;
			case READ:
				handleRead(resolvedPath);
				break;
			case WRITE:
				handleWrite(resolvedPath);
				break;
			case MKDIR:
				handleMkdir(resolvedPath);
				break;
			case DELETE:
				handleDelete(resolvedPath);
				break;
			case RMDIR:
				handleRmdir(resolvedPath);
				break;
			case MOVE:
				handleMove(resolvedPath);
				break;
			default:
				sendErrorAndClose("INVALID_OPERATION", "Unsupported folder operation: " + opCode);
				break;
		}
	}

	/**
	 * Validates that the requested path is within the shared root directory.
	 * Prevents path traversal attacks.
	 * 
	 * Protocol requirement: Paths must use forward slashes (/) as separators,
	 * regardless of the target OS. Java's Path API handles conversion to native format.
	 */
	private Path resolveSafePath(String requestedPath) throws IOException, SecurityException {
		Path root = Paths.get(rootPath).toRealPath();
		
		// Remove leading slash to make path relative
		String normalized = requestedPath.startsWith("/") ? requestedPath.substring(1) : requestedPath;
		
		Path resolved = root.resolve(normalized).normalize();
		
		// Verify the resolved path is still within root
		// Note: toRealPath() will throw if path doesn't exist, so we check before
		if (resolved.toFile().exists()) {
			Path realResolved = resolved.toRealPath();
			if (!realResolved.startsWith(root)) {
				throw new SecurityException("Path traversal attempt: " + requestedPath);
			}
			return realResolved;
		} else {
			// For non-existent paths (e.g., WRITE, MKDIR), just verify parent exists and is within root
			Path parent = resolved.getParent();
			if (parent != null && parent.toFile().exists()) {
				Path realParent = parent.toRealPath();
				if (!realParent.startsWith(root)) {
					throw new SecurityException("Path traversal attempt: " + requestedPath);
				}
			}
			return resolved;
		}
	}

	private void handleList(Path path) {
		// Parse operationPayload: offset (8B) + length (8B) + sortBy (1B) + sortOrder (1B)
		if (operationPayload.remaining() < 18) {
			sendErrorAndClose("INVALID_PAYLOAD", "LIST operation requires 18 bytes of payload, got " + operationPayload.remaining());
			return;
		}
		
		long offset = operationPayload.getLong();
		int length = (int) operationPayload.getLong();
		SortBy sortBy;
		SortOrder sortOrder;
		
		try {
			sortBy = SortBy.fromCode(operationPayload.get());
			sortOrder = SortOrder.fromCode(operationPayload.get());
		} catch (IOException e) {
			sendErrorAndClose("INVALID_PAYLOAD", "Invalid sort parameters: " + e.getMessage());
			return;
		}
		
		log.debug("LIST: path={}, offset={}, length={}, sortBy={}, sortOrder={}", path, offset, length, sortBy, sortOrder);
		
		List<File> page = new ArrayList<>(length > 0 ? length : 100);
		long totalCount = 0;
		
		try {
			if (sortBy == SortBy.NAME) {
				// Sort by name first, then apply pagination, then convert to File (most efficient)
				// Need to count total first for "more" field
				List<Path> allPaths = Files.list(path).sorted(getPathNameComparator(sortOrder)).collect(Collectors.toList());
				totalCount = allPaths.size();
				allPaths.stream()
					.skip(offset)
					.limit(length == -1 ? Long.MAX_VALUE : length)
					.map(Path::toFile)
					.forEach(page::add);
			} else if (sortBy == SortBy.NONE) {
				// No sorting, just apply pagination (fastest)
				// Need to count total first for "more" field
				List<Path> allPaths = Files.list(path).collect(Collectors.toList());
				totalCount = allPaths.size();
				allPaths.stream()
					.skip(offset)
					.limit(length == -1 ? Long.MAX_VALUE : length)
					.map(Path::toFile)
					.forEach(page::add);
			} else {
				// Hydrate all entries with metadata, sort, then apply pagination
				List<File> allFiles = Files.list(path).map(Path::toFile).collect(Collectors.toList());
				totalCount = allFiles.size();
				allFiles.stream()
					.sorted(getMetadataComparator(sortBy, sortOrder))
					.skip(offset)
					.limit(length == -1 ? Long.MAX_VALUE : length)
					.forEach(page::add);
			}
		} catch (IOException e) {
			log.error("Error listing directory '{}': {}", path, e.getMessage(), e);
			sendErrorAndClose("IO_ERROR", "Error listing directory " + path); // No details
			return;
		}
		
		// Check if we're at root (to determine if ".." should be included)
		Path root;
		try {
			root = Paths.get(rootPath).toRealPath();
		} catch (IOException e) {
			log.error("Error resolving root path '{}': {}", rootPath, e.getMessage(), e);
			sendErrorAndClose("IO_ERROR", "Error resolving root path");
			return;
		}
		boolean isRoot = path.equals(root);
		
		// Adjust totalCount to include ".." entry if not at root
		long totalCountWithParent = isRoot ? totalCount : totalCount + 1;
		
		// Calculate if there are more items beyond this page
		boolean more = (offset + page.size()) < totalCountWithParent;
		
		// Build JSON response
		try {
			JSONObject response = new JSONObject();
			
			// The requestedPath is already in the correct format from the client (e.g., "/", "/subdir")
			// Protocol requirement: paths are relative to shared root, "/" represents the root itself
			response.put("path", requestedPath);
			
			// Add pagination metadata
			JSONObject pagination = new JSONObject();
			pagination.put("offset", offset);
			pagination.put("length", length);
			pagination.put("more", more);
			response.put("pagination", pagination);
			
			// Add sorting metadata
			JSONObject sorting = new JSONObject();
			sorting.put("sortBy", sortBy.getJsonName());
			sorting.put("sortOrder", sortOrder.getJsonName());
			response.put("sorting", sorting);
			
			// Build items array
			List<JSONObject> items = new ArrayList<>();
			
			// Add ".." entry if not at root and within pagination window
			if (!isRoot && offset == 0) {
				JSONObject parentEntry = new JSONObject();
				parentEntry.put("name", "..");
				parentEntry.put("type", "directory");
				File parentFile = path.getParent().toFile();
				parentEntry.put("lastModified", parentFile.lastModified());
				parentEntry.put("readable", parentFile.canRead());
				parentEntry.put("writable", parentFile.canWrite());
				items.add(parentEntry);
			}
			
			// Add regular entries
			for (File file : page) {
				JSONObject item = new JSONObject();
				item.put("name", file.getName());
				
				// Determine type
				String type;
				if (Files.isSymbolicLink(file.toPath())) {
					type = "symlink";
					// Add symlink target
					try {
						Path target = Files.readSymbolicLink(file.toPath());
						item.put("target", target.toString());
					} catch (IOException e) {
						log.warn("Could not read symlink target for {}: {}", file, e.getMessage());
					}
				} else if (file.isDirectory()) {
					type = "directory";
				} else {
					type = "file";
				}
				item.put("type", type);
				
				// Add size for files and symlinks
				if (!file.isDirectory()) {
					item.put("size", file.length());
				}
				
				item.put("lastModified", file.lastModified());
				item.put("readable", file.canRead());
				item.put("writable", file.canWrite());
				
				// Add executable for files and symlinks
				if (!file.isDirectory()) {
					item.put("executable", file.canExecute());
				}
				
				items.add(item);
			}
			
			response.put("items", items);
			
			// Convert to bytes
			byte[] jsonBytes = response.toString().getBytes(StandardCharsets.UTF_8);
			
			// Send: status byte (0x01 = success) + JSON length + JSON
			ByteBuffer buffer = ByteBuffer.allocate(1 + 4 + jsonBytes.length);
			buffer.put((byte) 0x01); // success status
			buffer.putInt(jsonBytes.length);
			buffer.put(jsonBytes);
			buffer.flip();
			
			sendDataMessageToCloud(buffer).thenRun(() -> {
				close(null, false);
			}).exceptionally(e -> {
				log.error("Error sending LIST response", e);
				close(e, false);
				return null;
			});
			
		} catch (SecurityException e) {
			log.error("Error building LIST response: {}", e.getMessage(), e);
			sendErrorAndClose("IO_ERROR", "Security error building LIST response"); // No details
		}
	}

	private Comparator<Path> getPathNameComparator(SortOrder sortOrder) {
		if (sortOrder == SortOrder.ASCENDING) {
			return Comparator.comparing(p -> p.getFileName().toString().toLowerCase());
		} else {
			return Comparator.comparing((Path p) -> p.getFileName().toString().toLowerCase()).reversed();
		}
	}

	private Comparator<File> getMetadataComparator(SortBy sortBy, SortOrder sortOrder) {
		Comparator<File> comparator;
		
		switch (sortBy) {
			case SIZE:
				comparator = Comparator.comparing(File::length);
				break;
			case MODIFIED:
				comparator = Comparator.comparing(File::lastModified);
				break;
			case NAME:
				throw new IllegalArgumentException("Use getPathNameComparator for NAME sorting");
			case NONE:
			default:
				throw new IllegalArgumentException("Cannot sort by NONE here");
		}
		
		if (sortOrder == SortOrder.DESCENDING) {
			comparator = comparator.reversed();
		}
		
		return comparator;
	}

	private void handleStat(Path path) {
		log.debug("STAT: path={}", path);
		
		File file = path.toFile();
		
		try {
			// Validate file exists
			if (!file.exists()) {
				sendErrorAndClose("NOT_FOUND", "File or directory not found");
				return;
			}
			
			// Build JSON response
			JSONObject response = new JSONObject();
			
			// Path (as requested)
			response.put("path", requestedPath);
			response.put("name", file.getName());
			
			// Determine type
			String type;
			if (Files.isSymbolicLink(file.toPath())) {
				type = "symlink";
				// Add symlink target
				try {
					Path target = Files.readSymbolicLink(file.toPath());
					response.put("target", target.toString());
				} catch (IOException e) {
					log.warn("Could not read symlink target for {}: {}", file, e.getMessage());
				}
			} else if (file.isDirectory()) {
				type = "directory";
			} else {
				type = "file";
			}
			response.put("type", type);
			
			// Add size for files and symlinks
			if (!file.isDirectory()) {
				response.put("size", file.length());
			}
			
			response.put("lastModified", file.lastModified());
			response.put("readable", file.canRead());
			response.put("writable", file.canWrite());
			
			// Add executable for files and symlinks
			if (!file.isDirectory()) {
				response.put("executable", file.canExecute());
			}
			
			// Convert to bytes
			byte[] jsonBytes = response.toString().getBytes(StandardCharsets.UTF_8);
			
			// Send: status byte (0x01 = success) + JSON length + JSON
			ByteBuffer buffer = ByteBuffer.allocate(1 + 4 + jsonBytes.length);
			buffer.put((byte) 0x01); // success status
			buffer.putInt(jsonBytes.length);
			buffer.put(jsonBytes);
			buffer.flip();
			
			sendDataMessageToCloud(buffer).thenRun(() -> {
				close(null, false);
			}).exceptionally(e -> {
				log.error("Error sending STAT response", e);
				close(e, false);
				return null;
			});
			
		} catch (Exception e) {
			log.error("Error building STAT response: {}", e.getMessage(), e);
			sendErrorAndClose("IO_ERROR", "Error getting metadata: " + e.getMessage());
		}
	}

	private void handleRead(Path path) {
		// Parse operationPayload: offset (8B) + length (8B)
		if (operationPayload.remaining() < 16) {
			sendErrorAndClose("INVALID_PAYLOAD", "READ operation requires 16 bytes of payload, got " + operationPayload.remaining());
			return;
		}
		
		long offset = operationPayload.getLong();
		long length = operationPayload.getLong();
		
		log.debug("READ: path={}, offset={}, length={}", path, offset, length);
		
		File file = path.toFile();
		
		try {
			// Validate file
			if (!file.exists()) {
				sendReadErrorAndClose("File not found");
				return;
			}
			if (!file.isFile()) {
				sendReadErrorAndClose("Not a regular file");
				return;
			}
			if (!file.canRead()) {
				sendReadErrorAndClose("Permission denied");
				return;
			}
			
			// Calculate actual bytes to read
			RandomAccessFile tempRaf = new RandomAccessFile(file, "r");
			long fileLength = tempRaf.length();
			tempRaf.close();
			
			// Validate offset
			if (offset > fileLength) {
				// Offset beyond file size - send success with 0 length
				ByteBuffer responseBuffer = ByteBuffer.allocate(1 + 8);
				responseBuffer.put((byte) 0x01); // success
				responseBuffer.putLong(0L); // actual length = 0
				responseBuffer.flip();
				
				sendDataMessageToCloud(responseBuffer).thenRun(() -> {
					close(null, false);
				}).exceptionally(e -> {
					log.error("Error sending response", e);
					close(e, false);
					return null;
				});
				return;
			}
			
			// Calculate actual bytes to read
			long actualLength;
			if (length == -1) {
				// Read entire file from offset
				actualLength = fileLength - offset;
			} else {
				// Read specified length, but not beyond file size
				actualLength = Math.min(length, fileLength - offset);
			}
			
			// Send success status byte + actual length
			ByteBuffer statusBuffer = ByteBuffer.allocate(1 + 8);
			statusBuffer.put((byte) 0x01); // success
			statusBuffer.putLong(actualLength); // actual length being sent
			statusBuffer.flip();
			
			log.debug("Sending success status + length ({}), will stream content: {}", actualLength, (actualLength > 0));
			
			sendDataMessageToCloud(statusBuffer).thenRun(() -> {
				if (actualLength > 0) {
					log.debug("Streaming file content");
					streamFileContent(file, offset, actualLength);
				} else {
					log.debug("Length is 0, closing without streaming content");
					close(null, false);
				}
			}).exceptionally(e -> {
				log.error("Error sending status", e);
				close(e, false);
				return null;
			});
			
		} catch (Exception e) {
			log.error("Error starting file read", e);
			sendReadErrorAndClose("Error reading file: " + e.getMessage());
		}
	}

	private void handleWrite(Path path) {
		// Check readOnly flag before writing
		if (readOnly) {
			sendErrorAndClose("READ_ONLY", "Write operation not allowed on read-only share");
			return;
		}
		
		// Parse operationPayload: offset (8B) + length (8B)
		if (operationPayload.remaining() < 16) {
			sendErrorAndClose("INVALID_PAYLOAD", "WRITE operation requires 16 bytes of payload, got " + operationPayload.remaining());
			return;
		}
		
		long offset = operationPayload.getLong();
		long length = operationPayload.getLong();
		
		log.debug("WRITE: path={}, offset={}, length={}", path, offset, length);
		
		try {
			// Validate parent directory exists
			Path parentDir = path.getParent();
			if (parentDir != null && !Files.exists(parentDir)) {
				sendErrorAndClose("NOT_FOUND", "Parent directory does not exist: " + parentDir);
				return;
			}
			
			// Check if file exists and is writable (if it exists)
			File file = path.toFile();
			if (file.exists() && !file.canWrite()) {
				sendErrorAndClose("PERMISSION_DENIED", "File is not writable");
				return;
			}
			
			// Validate offset for existing files
			if (file.exists() && offset > file.length() && offset != -1) {
				sendErrorAndClose("INVALID_OFFSET", "Offset " + offset + " is beyond file size " + file.length());
				return;
			}
			
			// Open file for writing
			writeRaf = new RandomAccessFile(file, "rw");
			writePath = path;
			writeExpectedLength = length;
			writeBytesReceived = 0;
			
			// Handle offset
			if (offset == -1) {
				// Append mode - seek to end
				writeRaf.seek(writeRaf.length());
			} else if (offset > 0) {
				writeRaf.seek(offset);
			}
			// offset == 0 means start from beginning (default position)
			
			log.debug("WRITE: File opened for writing, expecting {} bytes at offset {}", length, offset == -1 ? "end" : offset);
			
			// If length is 0, we're done - send success immediately
			if (length == 0) {
				sendWriteSuccessAndClose();
			}
			// Otherwise, wait for data via handleWriteData()
			
		} catch (Exception e) {
			log.error("Error starting file write", e);
			cleanupWriteResources();
			sendErrorAndClose("IO_ERROR", "Error opening file for write: " + e.getMessage());
		}
	}

	private void handleWriteData(ByteBuffer buffer) {
		if (writeRaf == null) {
			log.error("WRITE data received but no write operation in progress");
			close(new IOException("WRITE data received but no write operation in progress"), false);
			return;
		}
		
		try {
			int bytesToWrite = buffer.remaining();
			log.debug("WRITE: Received {} bytes, total so far: {}/{}", bytesToWrite, writeBytesReceived, writeExpectedLength);
			
			// Write data to file
			if (buffer.hasArray()) {
				writeRaf.write(buffer.array(), buffer.arrayOffset() + buffer.position(), bytesToWrite);
			} else {
				byte[] temp = new byte[bytesToWrite];
				buffer.get(temp);
				writeRaf.write(temp);
			}
			
			writeBytesReceived += bytesToWrite;
			
			// Check if we've received all expected data
			if (writeBytesReceived >= writeExpectedLength) {
				log.debug("WRITE: All {} bytes received, sending success response", writeBytesReceived);
				sendWriteSuccessAndClose();
			}
			
		} catch (Exception e) {
			log.error("Error writing file data", e);
			cleanupWriteResources();
			sendErrorAndClose("IO_ERROR", "Error writing file: " + e.getMessage());
		}
	}
	
	private void sendWriteSuccessAndClose() {
		try {
			// Close the file first
			if (writeRaf != null) {
				writeRaf.close();
				writeRaf = null;
			}
			
			// Build success response JSON
			JSONObject response = new JSONObject();
			response.put("success", true);
			response.put("bytesWritten", writeBytesReceived);
			response.put("path", requestedPath);
			
			byte[] jsonBytes = response.toString().getBytes(StandardCharsets.UTF_8);
			
			// Send: status byte (0x01 = success) + JSON length + JSON
			ByteBuffer responseBuffer = ByteBuffer.allocate(1 + 4 + jsonBytes.length);
			responseBuffer.put((byte) 0x01); // success status
			responseBuffer.putInt(jsonBytes.length);
			responseBuffer.put(jsonBytes);
			responseBuffer.flip();
			
			log.debug("WRITE: Sending success response, bytesWritten={}", writeBytesReceived);
			
			sendDataMessageToCloud(responseBuffer).thenRun(() -> {
				close(null, false);
			}).exceptionally(e -> {
				log.error("Error sending WRITE success response", e);
				close(e, false);
				return null;
			});
			
		} catch (Exception e) {
			log.error("Error preparing WRITE success response", e);
			close(e, false);
		}
	}
	
	private void cleanupWriteResources() {
		if (writeRaf != null) {
			try {
				writeRaf.close();
			} catch (IOException e) {
				log.warn("Error closing write file handle", e);
			}
			writeRaf = null;
		}
	}

	private void handleMkdir(Path path) {
		// Check readOnly flag before creating
		if (readOnly) {
			sendErrorAndClose("READ_ONLY", "Mkdir operation not allowed on read-only share");
			return;
		}
		
		log.debug("MKDIR: path={}", path);
		
		try {
			File dir = path.toFile();
			
			// Check if already exists
			if (dir.exists()) {
				if (dir.isDirectory()) {
					// Directory already exists - success (like mkdir -p)
					JSONObject response = new JSONObject();
					response.put("success", true);
					response.put("created", false);
					response.put("alreadyExists", true);
					response.put("path", requestedPath);
					sendJsonSuccessAndClose(response);
					return;
				} else {
					// Exists but is a file
					sendErrorAndClose("NOT_A_DIRECTORY", "Path exists but is not a directory");
					return;
				}
			}
			
			// Create directory (and parents like mkdir -p)
			boolean created = dir.mkdirs();
			if (!created && !dir.exists()) {
				sendErrorAndClose("MKDIR_FAILED", "Failed to create directory");
				return;
			}
			
			// Build success response
			JSONObject response = new JSONObject();
			response.put("success", true);
			response.put("created", true);
			response.put("path", requestedPath);
			
			sendJsonSuccessAndClose(response);
			
		} catch (Exception e) {
			log.error("Error creating directory", e);
			sendErrorAndClose("IO_ERROR", "Error creating directory: " + e.getMessage());
		}
	}

	private void handleDelete(Path path) {
		// Check readOnly flag before deleting
		if (readOnly) {
			sendErrorAndClose("READ_ONLY", "Delete operation not allowed on read-only share");
			return;
		}
		
		log.debug("DELETE: path={}", path);
		
		try {
			File file = path.toFile();
			
			// Check if file exists
			if (!file.exists()) {
				sendErrorAndClose("NOT_FOUND", "File not found: " + requestedPath);
				return;
			}
			
			// Fail if it's a directory
			if (file.isDirectory()) {
				sendErrorAndClose("IS_DIRECTORY", "Cannot delete directory with DELETE, use RMDIR instead");
				return;
			}
			
			// Check if file is writable (deletable)
			if (!file.canWrite()) {
				sendErrorAndClose("PERMISSION_DENIED", "Permission denied");
				return;
			}
			
			// Delete the file
			boolean deleted = file.delete();
			if (!deleted) {
				sendErrorAndClose("DELETE_FAILED", "Failed to delete file");
				return;
			}
			
			// Build success response JSON
			JSONObject response = new JSONObject();
			response.put("success", true);
			response.put("deleted", true);
			response.put("path", requestedPath);
			
			sendJsonSuccessAndClose(response);
			
		} catch (Exception e) {
			log.error("Error deleting file", e);
			sendErrorAndClose("IO_ERROR", "Error deleting file: " + e.getMessage());
		}
	}

	private void handleRmdir(Path path) {
		// Check readOnly flag before deleting
		if (readOnly) {
			sendErrorAndClose("READ_ONLY", "Rmdir operation not allowed on read-only share");
			return;
		}
		
		log.debug("RMDIR: path={}", path);
		
		try {
			File dir = path.toFile();
			
			// Check if directory exists
			if (!dir.exists()) {
				sendErrorAndClose("NOT_FOUND", "Directory not found: " + requestedPath);
				return;
			}
			
			// Fail if it's not a directory
			if (!dir.isDirectory()) {
				sendErrorAndClose("NOT_A_DIRECTORY", "Cannot rmdir: path is not a directory, use DELETE instead");
				return;
			}
			
			// Check if directory is writable (deletable)
			if (!dir.canWrite()) {
				sendErrorAndClose("PERMISSION_DENIED", "Permission denied");
				return;
			}
			
			// Check if directory is empty
			String[] contents = dir.list();
			if (contents != null && contents.length > 0) {
				sendErrorAndClose("NOT_EMPTY", "Directory is not empty");
				return;
			}
			
			// Delete the directory
			boolean deleted = dir.delete();
			if (!deleted) {
				sendErrorAndClose("RMDIR_FAILED", "Failed to delete directory");
				return;
			}
			
			// Build success response JSON
			JSONObject response = new JSONObject();
			response.put("success", true);
			response.put("deleted", true);
			response.put("path", requestedPath);
			
			sendJsonSuccessAndClose(response);
			
		} catch (Exception e) {
			log.error("Error deleting directory", e);
			sendErrorAndClose("IO_ERROR", "Error deleting directory: " + e.getMessage());
		}
	}

	private void handleMove(Path oldPath) {
		// Check readOnly flag before moving
		if (readOnly) {
			sendErrorAndClose("READ_ONLY", "Move operation not allowed on read-only share");
			return;
		}
		
		// Parse operationPayload: newPathLength (4B) + newPath (UTF-8)
		if (operationPayload.remaining() < 4) {
			sendErrorAndClose("INVALID_PAYLOAD", "MOVE operation requires newPath in payload");
			return;
		}
		
		int newPathLength = operationPayload.getInt();
		if (newPathLength < 0 || newPathLength > operationPayload.remaining()) {
			sendErrorAndClose("INVALID_PAYLOAD", "Invalid newPath length: " + newPathLength);
			return;
		}
		
		byte[] newPathBytes = new byte[newPathLength];
		operationPayload.get(newPathBytes);
		String newPathStr = new String(newPathBytes, StandardCharsets.UTF_8);
		
		log.debug("MOVE: from={} to={}", oldPath, newPathStr);
		
		try {
			// Validate new path is within root
			Path newPath;
			try {
				newPath = resolveSafePath(newPathStr);
			} catch (SecurityException e) {
				log.warn("Path traversal attempt detected in MOVE newPath: {}", newPathStr);
				sendErrorAndClose("PATH_OUTSIDE_ROOT", "New path traversal attempt detected");
				return;
			}
			
			File oldFile = oldPath.toFile();
			File newFile = newPath.toFile();
			
			// Check if source exists
			if (!oldFile.exists()) {
				sendErrorAndClose("NOT_FOUND", "Source not found: " + requestedPath);
				return;
			}
			
			// If destination exists, delete it first (allow overwrite)
			if (newFile.exists()) {
				if (!newFile.delete()) {
					sendErrorAndClose("DELETE_FAILED", "Cannot overwrite existing destination: " + newPathStr);
					return;
				}
			}
			
			// Check parent of destination exists
			File newParent = newFile.getParentFile();
			if (newParent != null && !newParent.exists()) {
				sendErrorAndClose("NOT_FOUND", "Destination parent directory does not exist");
				return;
			}
			
			// Perform the move/rename
			boolean moved = oldFile.renameTo(newFile);
			if (!moved) {
				sendErrorAndClose("MOVE_FAILED", "Failed to move file");
				return;
			}
			
			// Build success response JSON
			JSONObject response = new JSONObject();
			response.put("success", true);
			response.put("oldPath", requestedPath);
			response.put("newPath", newPathStr);
			
			sendJsonSuccessAndClose(response);
			
		} catch (IOException e) {
			log.error("Error moving file", e);
			sendErrorAndClose("IO_ERROR", "Error moving file: " + e.getMessage());
		}
	}

	private void sendErrorAndClose(String errorCode, String errorMessage) {
		try {
			JSONObject error = new JSONObject();
			error.put("error", errorMessage);
			error.put("errorCode", errorCode);
			error.put("path", requestedPath);
			
			byte[] jsonBytes = error.toString().getBytes(StandardCharsets.UTF_8);
			
			// Send: status byte (0x00 = error) + JSON length + JSON
			ByteBuffer buffer = ByteBuffer.allocate(1 + 4 + jsonBytes.length);
			buffer.put((byte) 0x00); // error status
			buffer.putInt(jsonBytes.length);
			buffer.put(jsonBytes);
			buffer.flip();
			
			sendDataMessageToCloud(buffer).thenRun(() -> {
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

	private void sendErrorAndClose(String errorMessage) {
		sendErrorAndClose("IO_ERROR", errorMessage);
	}
	
	/**
	 * Send JSON success response and close connection.
	 * Format: status byte (0x01 = success) + JSON length + JSON
	 */
	private void sendJsonSuccessAndClose(JSONObject response) {
		try {
			byte[] jsonBytes = response.toString().getBytes(StandardCharsets.UTF_8);
			
			ByteBuffer buffer = ByteBuffer.allocate(1 + 4 + jsonBytes.length);
			buffer.put((byte) 0x01); // success status
			buffer.putInt(jsonBytes.length);
			buffer.put(jsonBytes);
			buffer.flip();
			
			sendDataMessageToCloud(buffer).thenRun(() -> {
				close(null, false);
			}).exceptionally(e -> {
				log.error("Error sending success response", e);
				close(e, false);
				return null;
			});
			
		} catch (Exception e) {
			log.error("Error preparing success response", e);
			close(e, false);
		}
	}

	/**
	 * Send error response for READ operation (status byte + error message string)
	 */
	private void sendReadErrorAndClose(String errorMessage) {
		try {
			byte[] errorBytes = errorMessage.getBytes(StandardCharsets.UTF_8);
			
			// Send: status byte (0x00 = error) + error message (UTF-8)
			ByteBuffer buffer = ByteBuffer.allocate(1 + errorBytes.length);
			buffer.put((byte) 0x00); // error status
			buffer.put(errorBytes);
			buffer.flip();
			
			sendDataMessageToCloud(buffer).thenRun(() -> {
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

	/**
	 * Stream file content in chunks
	 * @param file File to read from
	 * @param offset Starting position in file
	 * @param length Exact number of bytes to read (already calculated)
	 */
	private void streamFileContent(File file, long offset, long length) {
		try {
			raf = new RandomAccessFile(file, "r");
			raf.seek(offset);
			
			log.debug("Will stream {} bytes from file", length);
			// Stream file in chunks
			streamNextChunk(length);
			
		} catch (IOException e) {
			log.error("Error streaming file content", e);
			close(e, false);
		}
	}

	/**
	 * Stream next chunk of file content.
	 * Uses the instance field 'raf' which is closed by destroy().
	 */
	private void streamNextChunk(long remaining) {
		try {
			if (remaining <= 0) {
				log.debug("Streaming complete, closing connection");
				close(null, false);
				return;
			}
			
			if (raf == null) {
				log.error("RandomAccessFile is null during streaming");
				close(new IOException("RandomAccessFile is null"), false);
				return;
			}
			
			// Use the standard buffer size
			ByteBuffer buffer = ByteBuffer.allocate((int) Math.min(DATA_BUFFER_SIZE, remaining));
			int toRead = buffer.capacity();
			byte[] tempBuffer = new byte[toRead];
			int read = raf.read(tempBuffer, 0, toRead);
			
			log.debug("Read {} bytes from file ({} remaining)", read, remaining);
			
			if (read <= 0) {
				log.debug("No more data to read, closing connection");
				close(null, false);
				return;
			}
			
			buffer.put(tempBuffer, 0, read);
			buffer.flip();
			
			final int bytesRead = read;
			
			// Send with CRC32 validation
			sendDataMessageToCloud(buffer).thenRun(() -> {
				streamNextChunk(remaining - bytesRead);
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

	@Override
	protected void destroy() {
		// Clean up any resources
		log.debug("FolderTunnelConnection destroyed");
		// Close the RandomAccessFile if it's still open
		if (raf != null) {
			try {
				raf.close();
			} catch (IOException e) {
				log.warn("Error closing RandomAccessFile", e);
			}
		}
	}
}
