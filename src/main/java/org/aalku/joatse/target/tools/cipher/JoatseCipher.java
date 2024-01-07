package org.aalku.joatse.target.tools.cipher;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.InvalidAlgorithmParameterException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.InvalidParameterSpecException;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyAgreement;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class JoatseCipher {
		
	
	@SuppressWarnings("unused")
	private static final Logger log = LoggerFactory.getLogger(JoatseCipher.class);

	/**
	 * This can be Diffie Helman or RSA. If it's DH then pair must be called with
	 * the remote public key but if it's RSA then it must be called with the session
	 * key ciphered with the public key.
	 */
	public interface KeyExchange {

		byte[] getPublicKey();
		
		Paired pair(byte[] remoteKey) throws InvalidKeyException, NoSuchAlgorithmException, InvalidKeySpecException,
				NoSuchPaddingException, IllegalBlockSizeException, BadPaddingException, InvalidAlgorithmParameterException;

		default String getPublicKeyHash() throws NoSuchAlgorithmException {
			return toHex(MessageDigest.getInstance("SHA-1").digest(getPublicKey())); 
		}
		
		static String toHex(byte[] b) {
			StringBuilder sb = new StringBuilder();
			for (int i = 0; i < b.length; i++) {
				if (i > 0) {
					sb.append(":");
				}
				sb.append(Integer.toHexString((b[i] & 0xff) + 0x100).substring(1));
			}
			return sb.toString();
		}

	}
	
	private static class RSAKeyExchange implements KeyExchange {
		
		private static final String KEY_EXCHANGE_ALGORITHM = "RSA/ECB/OAEPPadding";
		private static final int KEY_EXCHANGE_KEY_SIZE = 2048;
		private KeyPair myRSAKeyPair;

		public RSAKeyExchange() throws NoSuchAlgorithmException {
	        KeyPairGenerator kpg = KeyPairGenerator.getInstance(KEY_EXCHANGE_ALGORITHM.split("/", 2)[0]);
			kpg.initialize(KEY_EXCHANGE_KEY_SIZE);
		    this.myRSAKeyPair = kpg.genKeyPair();
		}

		@Override
		public byte[] getPublicKey() {
			return myRSAKeyPair.getPublic().getEncoded();
		}

		@Override
		public Paired pair(byte[] cipheredKey)
				throws InvalidKeyException, NoSuchAlgorithmException, InvalidKeySpecException, NoSuchPaddingException, IllegalBlockSizeException, BadPaddingException, InvalidAlgorithmParameterException {
			/*
			 * Must not mutate this so this is reusable with different AES keys
			 */
			OAEPParameterSpec oaepParams = new OAEPParameterSpec("SHA-256", "MGF1", new MGF1ParameterSpec("SHA-256"), PSource.PSpecified.DEFAULT);
			Cipher c = Cipher.getInstance(KEY_EXCHANGE_ALGORITHM);
			c.init(javax.crypto.Cipher.DECRYPT_MODE, myRSAKeyPair.getPrivate(), oaepParams);
			// log.info("cipheredKey: {}", HexUtils.toHexString(cipheredKey));
			byte[] secretKey = c.doFinal(cipheredKey);
			// log.info("secretKey: {}", HexUtils.toHexString(secretKey));
			return new Paired(new SecretKeySpec(secretKey, CIPHER_ALGORITHM.split("/", 2)[0]));
		}

	}


	private static final class DHKeyExchange extends JoatseCipher implements KeyExchange {
		private static final String KEY_EXCHANGE_ALGORITHM = "DH";
		private static final int KEY_EXCHANGE_KEY_SIZE = 1024;
		private KeyPair myDHKeyPair;

		protected DHKeyExchange() throws NoSuchAlgorithmException {
	        KeyPairGenerator kpg = KeyPairGenerator.getInstance(KEY_EXCHANGE_ALGORITHM);
			kpg.initialize(KEY_EXCHANGE_KEY_SIZE);
		    this.myDHKeyPair = kpg.genKeyPair();
		}
		
		@Override
		public byte[] getPublicKey() {
			return myDHKeyPair.getPublic().getEncoded();
		}

		@Override
		public Paired pair(byte[] remotePublicKey) throws InvalidKeyException, NoSuchAlgorithmException, InvalidKeySpecException, NoSuchPaddingException {
			SecretKey secret = keyAgreement(CIPHER_KEY_SIZE, myDHKeyPair.getPrivate(),
					decodePublicKey(remotePublicKey, KEY_EXCHANGE_ALGORITHM), KEY_EXCHANGE_ALGORITHM, HASH_ALGORITHM,
					CIPHER_ALGORITHM.split("/")[0]);
			return new Paired(secret);
		}

		private PublicKey decodePublicKey(byte[] bytes, String cipherAlgorithm) throws InvalidKeySpecException, NoSuchAlgorithmException {
			X509EncodedKeySpec keySpec = new X509EncodedKeySpec(bytes);
			return KeyFactory.getInstance(cipherAlgorithm).generatePublic(keySpec);
		}

		private SecretKey keyAgreement(int aesKeySize, PrivateKey me, PublicKey them, String keyEchangeAlgorithm,
				String hashAlgorithm, String cipherAlgorithm) throws NoSuchAlgorithmException, InvalidKeyException {
			KeyAgreement ka = KeyAgreement.getInstance(keyEchangeAlgorithm);
		    ka.init(me);
		    ka.doPhase(them, true);
			SecretKey desSpec = new SecretKeySpec(
					Arrays.copyOf(MessageDigest.getInstance(hashAlgorithm).digest(ka.generateSecret()), aesKeySize / 8),
					cipherAlgorithm);
			return desSpec;
		}
	}

	public static class CipherException extends RuntimeException {
		private static final long serialVersionUID = 1L;
		public CipherException(GeneralSecurityException e) {
			super(e);
		}
	}

	private static final String HASH_ALGORITHM = "SHA-256";
	private static final String CIPHER_ALGORITHM = "AES/CBC/PKCS5Padding";
	private static final int CIPHER_KEY_SIZE = 128;

	public static class Paired {
		private javax.crypto.Cipher cipherC;
		private javax.crypto.Cipher cipherD;
		private SecretKey secret;
		public Paired(SecretKey secret) {
			this.secret = secret;
			try {
				this.cipherC = javax.crypto.Cipher.getInstance(CIPHER_ALGORITHM);
				this.cipherD = javax.crypto.Cipher.getInstance(CIPHER_ALGORITHM);
			} catch (NoSuchAlgorithmException | NoSuchPaddingException e) {
				throw new CipherException(e);
			}
		}
		public void cipher(ByteBuffer in, ByteBuffer out) {
			synchronized (cipherC) {
				try {
					cipherC.init(javax.crypto.Cipher.ENCRYPT_MODE, secret);
					ByteBuffer temp = ByteBuffer.allocate(out.capacity());
					cipherC.doFinal(in, temp);
					byte[] iv = cipherC.getParameters().getParameterSpec(IvParameterSpec.class).getIV();
					out.put((byte) iv.length);
					out.put(iv);
					temp.flip();
					out.put(temp);
				} catch (InvalidKeyException | IllegalBlockSizeException | BadPaddingException | ShortBufferException | InvalidParameterSpecException e ) {
					throw new CipherException(e);
				}
			}
		}
		
		public void decipher(ByteBuffer in, ByteBuffer out) {
			synchronized (cipherD) {
				try {
					int ivLen = in.get() & 0xFF;
					byte[] iv = new byte[ivLen];
					in.get(iv);
					cipherD.init(javax.crypto.Cipher.DECRYPT_MODE, secret, new IvParameterSpec(iv));
					cipherD.doFinal(in, out);
				} catch (InvalidKeyException | IllegalBlockSizeException | BadPaddingException | ShortBufferException | InvalidAlgorithmParameterException e ) {
					throw new CipherException(e);
				}
			}
		}
	}

	public static KeyExchange forDHKeyExchange() throws NoSuchAlgorithmException {
		return new DHKeyExchange();
	}

	public static Paired forPredefinedSecret(byte[] key) throws NoSuchAlgorithmException {
		SecretKey secret = new SecretKeySpec(key, CIPHER_ALGORITHM);
		return new Paired(secret);
	}

	public static KeyExchange forRSAKeyExchange() throws NoSuchAlgorithmException {
		return new RSAKeyExchange();
	}

}
