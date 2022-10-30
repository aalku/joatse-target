package org.aalku.joatse;

import java.nio.ByteBuffer;
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
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyAgreement;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.SecretKeySpec;

import org.aalku.joatse.target.tools.cipher.JoatseCipher;
import org.aalku.joatse.target.tools.cipher.JoatseCipher.Paired;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CipherTest {


	@Test
	void testDecipher() throws NoSuchAlgorithmException, InvalidKeyException, InvalidKeySpecException, NoSuchPaddingException,
			IllegalBlockSizeException, BadPaddingException, ShortBufferException {
				
		/**
		 * Our system
		 */
		JoatseCipher myCipher = new JoatseCipher();

		// Same parameters JoatseCipher internally use
		String hashAlgorithm = "SHA-256";
        String cipherAlgorithm = "AES";
        int aesKeySize = 128;
        int keyExchangeKeySize = 1024;
        String keyExchangeAlgorithm = "DH";
        
		KeyPairGenerator kpg = KeyPairGenerator.getInstance(keyExchangeAlgorithm);
		kpg.initialize(keyExchangeKeySize);

		// Key pair
        KeyPair kp1 = kpg.genKeyPair();
		
        // Public stuff
		byte[] pk1 = kp1.getPublic().getEncoded();

		Paired myPairedCipher = myCipher.pair(pk1);

		// Secret key, same as myPairedCipher internal keys
		SecretKey key1 = keyAgreement(aesKeySize, kp1.getPrivate(), decodePublicKey(myCipher.getPublicKey(), kpg.getAlgorithm()), kp1.getPrivate().getAlgorithm(), hashAlgorithm, cipherAlgorithm);
        
        // Let's try it
        
		String secretMessage = "Hola";

		// Someone
		Cipher cipher1 = Cipher.getInstance(cipherAlgorithm);
		cipher1.init(Cipher.ENCRYPT_MODE, key1);
		byte[] ciphered = cipher1.doFinal(secretMessage.getBytes());
		
		printArray(ciphered);

		// Me
		ByteBuffer out = ByteBuffer.allocate(1024); // I don't know. Whatever
		myPairedCipher.decipher(ByteBuffer.wrap(ciphered), out);
		
		Assertions.assertEquals(ByteBuffer.wrap(secretMessage.getBytes()), out.flip());
        
	}

	@Test
	void testCipher() throws NoSuchAlgorithmException, InvalidKeyException, InvalidKeySpecException, NoSuchPaddingException,
			IllegalBlockSizeException, BadPaddingException, ShortBufferException {
				
		/**
		 * Our system
		 */
		JoatseCipher myCipher = new JoatseCipher();

		// Same parameters JoatseCipher internally use
		String hashAlgorithm = "SHA-256";
        String cipherAlgorithm = "AES";
        int aesKeySize = 128;
        int keyExchangeKeySize = 1024;
        String keyExchangeAlgorithm = "DH";
        
		KeyPairGenerator kpg = KeyPairGenerator.getInstance(keyExchangeAlgorithm);
		kpg.initialize(keyExchangeKeySize);

		// Key pair
        KeyPair kp1 = kpg.genKeyPair();
		
        // Public stuff
		byte[] pk1 = kp1.getPublic().getEncoded();

		Paired myPairedCipher = myCipher.pair(pk1);

		// Secret key, same as myPairedCipher internal keys
		SecretKey key1 = keyAgreement(aesKeySize, kp1.getPrivate(), decodePublicKey(myCipher.getPublicKey(), kpg.getAlgorithm()), kp1.getPrivate().getAlgorithm(), hashAlgorithm, cipherAlgorithm);
        
        // Let's try it
        
		String secretMessage = "Hola";

		// Me
		ByteBuffer out1 = ByteBuffer.allocate(1024); // I don't know. Whatever
		myPairedCipher.cipher(ByteBuffer.wrap(secretMessage.getBytes()), out1);

		// Someone
		Cipher cipher1 = Cipher.getInstance(cipherAlgorithm);
		cipher1.init(Cipher.DECRYPT_MODE, key1);
		ByteBuffer out2 = ByteBuffer.allocate(1024); // I don't know. Whatever
		cipher1.doFinal((ByteBuffer)out1.flip(), out2);
		
		Assertions.assertEquals(ByteBuffer.wrap(secretMessage.getBytes()), out2.flip());
        
	}

	
	private void printArray(byte[] ciphered) {
		ByteBuffer buffer = ByteBuffer.wrap(ciphered);
		System.out.println(Stream.generate(() -> buffer.get()).limit(buffer.capacity())
				.map(b -> String.format("%02x", b & 0xFF)).collect(Collectors.joining(":")));
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
