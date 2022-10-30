package org.aalku.joatse.target.tools.cipher;

import java.nio.ByteBuffer;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;

import javax.crypto.BadPaddingException;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyAgreement;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.SecretKeySpec;

public class JoatseCipher {
	
	public static class CipherException extends RuntimeException {
		private static final long serialVersionUID = 1L;
		public CipherException(GeneralSecurityException e) {
			super(e);
		}
	}

	private static final String KEY_EXCHANGE_ALGORITHM = "DH";
	private static final int KEY_EXCHANGE_KEY_SIZE = 1024;
	private static final String HASH_ALGORITHM = "SHA-256";
	private static final String CIPHER_ALGORITHM = "AES";
	private static final int CIPHER_KEY_SIZE = 128;
	private KeyPair myKeys;
	private byte[] publicKey;

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
		public void cipher(ByteBuffer in, ByteBuffer out) throws ShortBufferException {
			synchronized (cipherC) {
				try {
					cipherC.init(javax.crypto.Cipher.ENCRYPT_MODE, secret);
					cipherC.doFinal(in, out);
				} catch (InvalidKeyException | IllegalBlockSizeException | BadPaddingException e) {
					throw new CipherException(e);
				}
			}
		}
		public void decipher(ByteBuffer in, ByteBuffer out) throws InvalidKeyException, ShortBufferException, IllegalBlockSizeException, BadPaddingException {
			synchronized (cipherD) {
				cipherD.init(javax.crypto.Cipher.DECRYPT_MODE, secret);
				cipherD.doFinal(in, out);
			}
		}
	}
	
	public JoatseCipher() throws NoSuchAlgorithmException {
        KeyPairGenerator kpg = KeyPairGenerator.getInstance(KEY_EXCHANGE_ALGORITHM);
		kpg.initialize(KEY_EXCHANGE_KEY_SIZE);
	    this.myKeys = kpg.genKeyPair();
	    this.publicKey = myKeys.getPublic().getEncoded();
	}
	
	public byte[] getPublicKey() {
		return publicKey;
	}
	
	public Paired pair(byte[] remotePublicKey) throws InvalidKeyException, NoSuchAlgorithmException, InvalidKeySpecException, NoSuchPaddingException {
		SecretKey secret = keyAgreement(CIPHER_KEY_SIZE, myKeys.getPrivate(),
				decodePublicKey(remotePublicKey, KEY_EXCHANGE_ALGORITHM), KEY_EXCHANGE_ALGORITHM, HASH_ALGORITHM,
				CIPHER_ALGORITHM);
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
