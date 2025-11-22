package org.aalku.joatse.target;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import org.aalku.joatse.target.JoatseClient.TunnelRequestItemFile;
import org.aalku.joatse.target.JoatseTargetApplication;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileSharingTest {

	@Test
	void testFileDescriptionGeneration(@TempDir Path tempDir) throws IOException {
		// Create a test file
		Path testFile = tempDir.resolve("testfile.txt");
		Files.write(testFile, "test content".getBytes());
		
		// Test auto-generated description (should be filename)
		String description = getDefaultFileDescription(null, testFile.toString());
		assertEquals("testfile.txt", description);
		
		// Test with empty description (should be filename)
		description = getDefaultFileDescription("", testFile.toString());
		assertEquals("testfile.txt", description);
		
		// Test with custom description (should use custom)
		description = getDefaultFileDescription("My Custom File", testFile.toString());
		assertEquals("My Custom File", description);
	}

	@Test
	void testFileDescriptionWithPath(@TempDir Path tempDir) throws IOException {
		// Create a test file in a subdirectory
		Path subDir = tempDir.resolve("subdir");
		Files.createDirectories(subDir);
		Path testFile = subDir.resolve("nested-file.log");
		Files.write(testFile, "log content".getBytes());
		
		// Description should only include filename, not path
		String description = getDefaultFileDescription(null, testFile.toString());
		assertEquals("nested-file.log", description);
	}

	@Test
	void testPathWithSpaces(@TempDir Path tempDir) throws IOException {
		// Create a file with spaces in name
		Path testFile = tempDir.resolve("file with spaces.txt");
		Files.write(testFile, "content".getBytes());
		
		// Test description generation
		String description = getDefaultFileDescription(null, testFile.toString());
		assertEquals("file with spaces.txt", description);
		
		// Verify file exists and is readable
		File file = new File(testFile.toString());
		assertTrue(file.exists());
		assertTrue(file.isFile());
		assertTrue(file.canRead());
	}

	@Test
	void testFileWithSpecialCharacters(@TempDir Path tempDir) throws IOException {
		// Create a file with special characters (that are valid on most file systems)
		Path testFile = tempDir.resolve("file-name_123.txt");
		Files.write(testFile, "content".getBytes());
		
		// Test description generation
		String description = getDefaultFileDescription(null, testFile.toString());
		assertEquals("file-name_123.txt", description);
	}

	@Test
	void testSymlinkBehavior(@TempDir Path tempDir) throws IOException {
		// Only test if symlinks are supported on this OS
		if (!System.getProperty("os.name").toLowerCase().contains("win")) {
			// Create a target file
			Path targetFile = tempDir.resolve("target.txt");
			Files.write(targetFile, "target content".getBytes());
			
			// Create a symlink
			Path linkFile = tempDir.resolve("link.txt");
			try {
				Files.createSymbolicLink(linkFile, targetFile);
				
				File link = new File(linkFile.toString());
				// Symlink should be treated as a file
				assertTrue(link.exists(), "Symlink should exist");
				assertTrue(link.isFile(), "Symlink should be identified as a file");
				
				// Description should use symlink name, not target name
				String description = getDefaultFileDescription(null, linkFile.toString());
				assertEquals("link.txt", description);
				
				// File size should be from target
				assertEquals(Files.size(targetFile), link.length());
			} catch (UnsupportedOperationException e) {
				// Symlinks not supported, skip this test
			}
		}
	}

	// Helper method matching the implementation in JoatseTargetApplication
	private static String getDefaultFileDescription(String description, String path) {
		if (description != null && !description.isEmpty()) {
			return description;
		}
		return new File(path).getName();
	}
}
