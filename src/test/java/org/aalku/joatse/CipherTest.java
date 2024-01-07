package org.aalku.joatse;

import java.nio.ByteBuffer;
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
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Arrays;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.crypto.BadPaddingException;
import javax.crypto.Cipher;
import javax.crypto.IllegalBlockSizeException;
import javax.crypto.KeyAgreement;
import javax.crypto.KeyGenerator;
import javax.crypto.NoSuchPaddingException;
import javax.crypto.SecretKey;
import javax.crypto.ShortBufferException;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.OAEPParameterSpec;
import javax.crypto.spec.PSource;
import javax.crypto.spec.SecretKeySpec;

import org.aalku.joatse.target.tools.cipher.JoatseCipher;
import org.aalku.joatse.target.tools.cipher.JoatseCipher.KeyExchange;
import org.aalku.joatse.target.tools.cipher.JoatseCipher.Paired;
import org.junit.jupiter.api.Assertions;
import org.junit.jupiter.api.Test;

class CipherTest {

	@Test
	void testRsaKeyExchange() throws NoSuchAlgorithmException, InvalidKeyException, NoSuchPaddingException,
			IllegalBlockSizeException, BadPaddingException, InvalidKeySpecException, ShortBufferException, InvalidAlgorithmParameterException {
		String keyExchangeAlgorithm = "RSA/ECB/OAEPPadding";
        String cipherAlgorithm = "AES/CBC/PKCS5Padding";
        int aesKeySize = 128;

        // Me
		KeyExchange myCipher = JoatseCipher.forRSAKeyExchange();
		byte[] pubKey = myCipher.getPublicKey();
		
		// Someone
		// Generate AES key
		KeyGenerator kg = KeyGenerator.getInstance(cipherAlgorithm.split("/", 2)[0]);
		kg.init(aesKeySize);
        SecretKey aesKey = kg.generateKey();
        // Cipher AES key with our pub key
		Cipher keyExchangeCipher = Cipher.getInstance(keyExchangeAlgorithm);
		PublicKey pubKeyObject = 
			    KeyFactory.getInstance(keyExchangeAlgorithm.split("/", 2)[0]).generatePublic(new X509EncodedKeySpec(pubKey));
		OAEPParameterSpec oaepParams = new OAEPParameterSpec("SHA-256", "MGF1", new MGF1ParameterSpec("SHA-256"), PSource.PSpecified.DEFAULT);
		keyExchangeCipher.init(Cipher.ENCRYPT_MODE, pubKeyObject, oaepParams);
		byte[] cipheredAeskey = keyExchangeCipher.doFinal(aesKey.getEncoded());
		// Cipher secret message with AES key
		String secretMessage = "Hola";
		Cipher cipher1 = Cipher.getInstance(cipherAlgorithm);
		cipher1.init(Cipher.ENCRYPT_MODE, aesKey);
		byte[] cipheredSecret = cipher1.doFinal(secretMessage.getBytes());
		
		// Me
		// Init with ciphered AES key (decipher with private key internally)
		Paired myPairedCipher = myCipher.pair(cipheredAeskey);
		// Decipher secret message with deciphered AES key
		ByteBuffer in = ByteBuffer.allocate(1024); // I don't know. Whatever
		byte[] iv = cipher1.getIV();
		in.put((byte)iv.length);
		in.put(iv);
		in.put(cipheredSecret);
		in.flip();
		ByteBuffer out = ByteBuffer.allocate(1024); // I don't know. Whatever
		myPairedCipher.decipher(in, out);
		
		Assertions.assertEquals(ByteBuffer.wrap(secretMessage.getBytes()), out.flip());
	}
	

	@Test
	void testDecipher() throws NoSuchAlgorithmException, InvalidKeyException, InvalidKeySpecException, NoSuchPaddingException,
			IllegalBlockSizeException, BadPaddingException, ShortBufferException, InvalidAlgorithmParameterException {
				
		/**
		 * Our system
		 */
		KeyExchange myCipher = JoatseCipher.forDHKeyExchange();

		// Same parameters JoatseCipher internally use
		String hashAlgorithm = "SHA-256";
        String cipherAlgorithm = "AES/CBC/PKCS5Padding";
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
		byte[] iv = cipher1.getIV();
		ByteBuffer ciphered2 = ByteBuffer.allocate(1024); // I don't know. Whatever
		ciphered2.put((byte) iv.length);
		ciphered2.put(iv);
		ciphered2.put(ciphered);
		ciphered2.flip();
		
		printArray(ciphered);

		// Me
		ByteBuffer out = ByteBuffer.allocate(1024); // I don't know. Whatever
		myPairedCipher.decipher(ciphered2, out);
		
		Assertions.assertEquals(ByteBuffer.wrap(secretMessage.getBytes()), out.flip());
        
	}

	@Test
	void testCipher() throws NoSuchAlgorithmException, InvalidKeyException, InvalidKeySpecException, NoSuchPaddingException,
			IllegalBlockSizeException, BadPaddingException, ShortBufferException, InvalidAlgorithmParameterException {
				
		/**
		 * Our system
		 */
		KeyExchange myCipher = JoatseCipher.forDHKeyExchange();

		// Same parameters JoatseCipher internally use
		String hashAlgorithm = "SHA-256";
        String cipherAlgorithm = "AES/CBC/PKCS5Padding";
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
		out1.flip();
		
		// Someone
		Cipher cipher1 = Cipher.getInstance(cipherAlgorithm);
		int ivLen = out1.get() & 0xFF;
		byte[] iv = new byte[ivLen];
		out1.get(iv);
		cipher1.init(Cipher.DECRYPT_MODE, key1, new IvParameterSpec(iv));
		ByteBuffer out2 = ByteBuffer.allocate(1024); // I don't know. Whatever
		cipher1.doFinal(out1, out2);
		
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
				cipherAlgorithm.split("/", 2)[0]);
		return desSpec;
	}

}
