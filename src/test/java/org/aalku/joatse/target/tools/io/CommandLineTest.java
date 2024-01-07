package org.aalku.joatse.target.tools.io;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.aalku.joatse.target.tools.io.CommandLineParser.parseCommandLine;

import org.junit.jupiter.api.Test;

class CommandLineTest {
	@Test
	void testBasic()  {
		assertArrayEquals(new String[] { "" },
				parseCommandLine(""));
		assertArrayEquals(new String[] { "hola", "a", "todos" },
				parseCommandLine("hola a todos"));
	}

	@Test
	void testSpaces()  {
		assertArrayEquals(new String[] { "hola", "a", "todos" },
				parseCommandLine(" hola  a   todos "));
		
		assertArrayEquals(new String[] { " hola", " a ", " todos " },
				parseCommandLine("' hola' ' a ' ' todos ' "));
		
		assertArrayEquals(new String[] { " hola", " a ", " todos " },
				parseCommandLine("\" hola\"   \" a \"   \" todos \" "));
	}

	@Test
	void testQuotes()  {
		assertArrayEquals(new String[] { "" },
				parseCommandLine("''"));
		
		assertArrayEquals(new String[] { "" },
				parseCommandLine("\"\""));
		
		assertArrayEquals(new String[] { "x", "x" },
				parseCommandLine("'x' 'x'"));

		assertArrayEquals(new String[] { "", "x" },
				parseCommandLine("'' 'x'"));

		assertArrayEquals(new String[] { "", "", "x" },
				parseCommandLine("'' \"\" 'x'"));

		assertArrayEquals(new String[] { "\"", "''", "x" },
				parseCommandLine("'\"' \"''\" 'x'"));

		assertArrayEquals(new String[] { "", "" },
				parseCommandLine("'' ''"));
		
		assertArrayEquals(new String[] { "", "" },
				parseCommandLine("\"\" \"\""));
		
		assertArrayEquals(new String[] { "hola", "a", "todos", "" },
				parseCommandLine(" \"hola\"'' 'a'\"\" \"todos\"'' ''"));
	}

	@Test
	void testEscape()  {
		assertArrayEquals(new String[] { "'" },
				parseCommandLine("'\\''"));

		assertArrayEquals(new String[] { "\"" },
				parseCommandLine("\"\\\"\""));

		assertArrayEquals(new String[] { "  x ''" },
				parseCommandLine("\\ \\ 'x'\\ \\'\\'"));
	}


}
