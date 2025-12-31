package org.aalku.joatse.target.connection;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.function.Consumer;

import org.aalku.joatse.target.JoatseSession;
import org.aalku.joatse.target.tools.cipher.JoatseCipher.Paired;
import org.aalku.joatse.target.tools.io.CommandLineParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.pty4j.PtyProcess;
import com.pty4j.PtyProcessBuilder;
import com.pty4j.WinSize;

/**
 * Connection to a command.
 */
public class CommandConnection extends AbstractSocketConnection {
	
	private static final byte TERM_PROTOCOL_VERSION = 1;

	public enum Stream { STDOUT((byte) 1), STDERR((byte) 2);

	public byte code;

	Stream(byte code) {
		this.code = code;
	} };
	
	private static final byte CODE_TYPE = 1;
	private static final byte CODE_RESIZE = 2;
	
	
	private final String[] command;
	
	private PtyProcess process = null;
	
	private final Paired sessionCipher;

	private static final Logger log = LoggerFactory.getLogger(CommandConnection.class);


	public CommandConnection(JoatseSession manager, String[] command, long socketId, Consumer<Throwable> closeSession, Paired sessionCipher) {
		super(manager, socketId, closeSession);
		this.sessionCipher = sessionCipher;
		this.command = command;
		log.info("New command connection {}", socketId);
		printToTerminal(String.format("Running command %s\r\n\r\n", CommandLineParser.formatCommandLine(command)),
				Stream.STDERR);
	}

	private void printToTerminal(String str, Stream stream) {
		try {
			sendStreamData(StandardCharsets.UTF_8.encode(str), stream).get();
		} catch (InterruptedException | ExecutionException e) {
			throw new RuntimeException(e);
		}
	}

	public boolean startCommand() {
		// TODO right command
		Map<String, String> sessionEnv = new HashMap<>(System.getenv());
		// sessionEnv.putAll(env);
		sessionEnv.put("TERM", "xterm");
		try {
			this.process = new PtyProcessBuilder().setCommand(command).setRedirectErrorStream(false)
					.setInitialColumns(80).setInitialRows(30)
					.setEnvironment(sessionEnv)
					.start();
		} catch (IOException e) {
			printToTerminal(String.format("Error running command!!!\r\n\r\n"), Stream.STDERR);
			log.error("Error running command {}", getSocketId());
			return false;
		}
		stdOutThread(process.getInputStream(), Stream.STDOUT).start();
		stdOutThread(process.getErrorStream(), Stream.STDERR).start();
		return true;
	}

	private CompletableFuture<Void> sendStreamData(ByteBuffer data, Stream stream) {
		if (data.limit() == 0) {
			throw new IllegalArgumentException("0 length would mean EoF");
		}
		ByteBuffer buff = ByteBuffer.allocate(DATA_BUFFER_SIZE + MAX_HEADER_SIZE_BYTES);
		buff.put(TERM_PROTOCOL_VERSION);
		buff.put(stream.code);
		buff.put(encrypt(data));
		buff.flip();
		return sendDataMessageToCloud(buff);
	}

	private void sendStreamEof(Stream stream) {
		ByteBuffer buff = ByteBuffer.allocate(2);
		buff.put(TERM_PROTOCOL_VERSION);
		buff.put(stream.code);
		buff.flip();
		try {
			sendDataMessageToCloud(buff).get();
		} catch (InterruptedException | ExecutionException e) {
			// Nothing to do
		}
	}
	
	public void write(String string) throws IOException {
		byte[] bytes = string.getBytes(StandardCharsets.UTF_8);
		// log.info("Bytes to pty ({}): {}", debugBytes(bytes), string);
		process.getOutputStream().write(bytes);
		process.getOutputStream().flush();
	}

	public void resized(int cols, int rows) {
		process.setWinSize(new WinSize(cols - 1, rows));
	}

	@Override
	public void receivedBytesFromCloud(ByteBuffer buffer) {	
		byte version = buffer.get();
		if (version != TERM_PROTOCOL_VERSION) {
			close(new RuntimeException("Unsupported version: " + (version & 0xFF)), false);
			return;
		}
		byte cmd = buffer.get();
		if (cmd == CODE_TYPE) {
			int len = buffer.getInt();
			if (len != buffer.remaining()) {
				close(new RuntimeException("Do you think this is a real socket or what?: " + len + ", " + buffer.remaining()), false);
				return;
			}
			try {
				String text = StandardCharsets.UTF_8.decode(decrypt(buffer)).toString();
				write(text);
			} catch (IOException e) {
				throw new RuntimeException("Error typing text", e);
			}
		} else if (cmd == CODE_RESIZE) {
			if (8 != buffer.remaining()) {
				close(new RuntimeException("Do you think this is a real socket or what?: " + buffer.remaining()), false);
				return;
			}
			int rows = buffer.getInt();
			int cols = buffer.getInt();
			resized(cols, rows);
		} else {
			close(new RuntimeException("Unsupported code: " + cmd), false);
			return;
		}
		// TODO
	}

	@Override
	protected Logger getLog() {
		return log;
	}

	@Override
	protected void destroy() {
		log.info("stop");
		PtyProcess p = this.process;
		if (p != null) {
			p.destroyForcibly();
		}
	}
	
	private Thread stdOutThread(InputStream out, Stream stream) {
		return new Thread(stream.toString() + "_Handler") {
			public void run() {
				byte[] buff = new byte[1024];
				try {
					try {
						while (true) {
							int n = out.read(buff);
							if (n < 0) {
								break;
							} else if (n > 0){
								synchronized (CommandConnection.this) {
									sendStreamData(ByteBuffer.wrap(copyBuff(buff, n)), stream).get();
								}
							}
						}
					} catch (Exception e) {
						e.printStackTrace();
					} finally {
						synchronized (CommandConnection.this) {
							sendStreamEof(stream);
						}
					}
				} catch (Exception e) {
					e.printStackTrace();
				}
			}

			private byte[] copyBuff(byte[] buff, int n) {
				byte[] b = new byte[n];
				System.arraycopy(buff, 0, b, 0, n);
				return b;
			};
		};
	}

	private ByteBuffer decrypt(ByteBuffer buffer) throws IOException {
		if (sessionCipher == null) {
			return buffer;
		}
		ByteBuffer res = ByteBuffer.allocate(DATA_BUFFER_SIZE);
		sessionCipher.decipher(buffer, res);
		res.flip();
		return res;
	}
	
	private ByteBuffer encrypt(ByteBuffer buffer) {
		if (sessionCipher == null) {
			return buffer;
		}
		ByteBuffer res = ByteBuffer.allocate(DATA_BUFFER_SIZE + MAX_HEADER_SIZE_BYTES);
		sessionCipher.cipher(buffer, res);
		res.flip();
		return res;
	}

}
