package org.aalku.joatse;

import java.net.InetSocketAddress;
import java.util.regex.Pattern;

import org.aalku.joatse.target.tools.io.IOTools;
import org.junit.jupiter.api.Assertions;

import org.junit.jupiter.api.Test;

class IOToolsTest {

	@Test
	final void globToRegex() {
		Pattern regex = IOTools.globToRegex("^no.se.(que).es\testo*", false);
		System.out.println(regex.pattern());
		Assertions.assertEquals("\\Q^\\Eno\\Q.\\Ese\\Q.(\\Eque\\Q).\\Ees\\Q\t\\Eesto.*", regex.pattern());
	}

	@Test
	final void testInetAddressPatternMatch() {
		Assertions.assertTrue(IOTools.testInetAddressPatternMatch("1.1.1.*", InetSocketAddress.createUnresolved("1.1.1.1", 1234)));
		Assertions.assertTrue(IOTools.testInetAddressPatternMatch("1.1.1.1", InetSocketAddress.createUnresolved("1.1.1.1", 1234)));
		Assertions.assertTrue(IOTools.testInetAddressPatternMatch("1.1.1.*:*", InetSocketAddress.createUnresolved("1.1.1.1", 1234)));
		Assertions.assertTrue(IOTools.testInetAddressPatternMatch("1.1.1.*:1234", InetSocketAddress.createUnresolved("1.1.1.1", 1234)));
		Assertions.assertTrue(IOTools.testInetAddressPatternMatch("*.myhost.*:1234", InetSocketAddress.createUnresolved("www.myhost.abc", 1234)));
		
		Assertions.assertFalse(IOTools.testInetAddressPatternMatch("1.1.1.*:123", InetSocketAddress.createUnresolved("1.1.1.1", 1234)));
		Assertions.assertFalse(IOTools.testInetAddressPatternMatch("1.1.1.1.*:1234", InetSocketAddress.createUnresolved("1.1.1.1", 1234)));
		Assertions.assertFalse(IOTools.testInetAddressPatternMatch("myhost:1234", InetSocketAddress.createUnresolved("www.myhost.abc", 1234)));
	}

	
}
