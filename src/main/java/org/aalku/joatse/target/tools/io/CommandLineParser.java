package org.aalku.joatse.target.tools.io;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.StringTokenizer;
import java.util.stream.Collectors;

public class CommandLineParser {

	public static String[] parseCommandLine(String command) {
		String spaces = " \t\r\n";
		char q1 = '"';
		char q2 = '\'';
		String quotes = "" + q1 + q2;
		char escape = '\\';
		
		StringTokenizer st = new StringTokenizer(command, spaces + escape + quotes, true);
	
		Character cq = null; // CurrentQuote
		boolean escapedNext = false;
		List<String> res = new ArrayList<>();
		StringBuilder current = new StringBuilder();
		boolean argStarted = false; // This means "current" is a real token even if it's empty
		boolean justClosedArg = true;
	
		while (st.hasMoreTokens()) {
			boolean escaped = escapedNext; // Escaped this one?
			escapedNext = false; // Next isn't anymore
			boolean justClosedToken2 = justClosedArg; // Closed token in the previous loop
			justClosedArg = false; // Not in this one, yet
			
			String t = st.nextToken();
			if (t.length() == 1 && !escaped) {
				// Maybe special
				char c = t.charAt(0);
				if (c == escape) {
					escapedNext = true;
					continue;
				} else if (c == q1 || c == q2) {
					// it's a quote
					if (cq != null) {
						// We are in quotes
						if (cq == c) {
							// Close quotes
							cq = null;
							continue;
						}
						// As normal string (different quotes, so not closed)
					} else {
						// We were not in quotes
						cq = c; // Now we are
						argStarted = true;
						continue;
					}
				} else if (spaces.contains(t)) {
					if (cq == null) {
						// end of arg
						if (!justClosedToken2) {
							res.add(current.toString());
							current.setLength(0);
							argStarted = false;
						}
						justClosedArg = true;
						continue;
					}
					// As normal string (space inside quotes)
				}
				// Else normal 1 char string
			}
			// Normal string or as normal string (inside quotes or escaped)
			current.append(t);
			argStarted = true;
		}
		if (current.length() > 0 || res.size() == 0 || argStarted) {
			res.add(current.toString());
		}
		if (cq != null) {
			throw new IllegalArgumentException("Unclosed quote: " + command);
		}
		if (escapedNext) {
			throw new IllegalArgumentException("Slash at the end: " + command);
		}
		return res.toArray(new String[res.size()]);
	}

	public static String formatCommandLine(String[] command) {
		String res = Arrays.asList(command).stream().map(c -> {
			if (c.contains("'") || c.contains("\"") || c.contains(" ") || c.contains("\t") || c.contains("\r")
					|| c.contains("\n")) {
				return "'" + (c.replaceAll("\\", "\\\\").replaceAll("'", "\\'")) + "'";
			} else {
				return c;
			}
		}).collect(Collectors.joining(" "));
		return res;
	}

}
