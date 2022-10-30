package org.aalku.joatse.target.tools;

import java.util.ArrayList;

import org.aalku.joatse.target.tools.ansi.AnsiBackground;
import org.aalku.joatse.target.tools.ansi.AnsiColor;
import org.aalku.joatse.target.tools.ansi.AnsiOutput;

import com.google.zxing.WriterException;
import com.google.zxing.common.BitMatrix;

public abstract class QrGenerator {
	public static String getQr(QrMode qrMode, String confirmationUri) {				
		String lf = String.format("%n");
		BitMatrix bitMatrix;
		try {
			bitMatrix = net.glxn.qrgen.javase.QRCode.from(confirmationUri).withSize(50, 50).createMatrix(confirmationUri);
		} catch (WriterException e) {
			return null;
		}
		int[] rect = bitMatrix.getEnclosingRectangle();
		int margin = 2;
		if (qrMode == QrMode.AUTO) {
			boolean unicode = false;
			boolean ansi = AnsiOutput.isEnabled();
			if (ansi && unicode) {
				qrMode = QrMode.UNICODE_ANSI;
			} else if (ansi && !unicode) {
				qrMode = QrMode.ANSI;
			} else if (!ansi) {
				qrMode = QrMode.UNICODE;
			} else {
				qrMode = QrMode.NONE; // ASCII does not work very well
			}
		}
		if (qrMode == QrMode.ANSI) {
			ArrayList<Object> ansiElements = new ArrayList<>(); 
			for (int y = rect[1] - margin; y < rect[1] + rect[3] + margin; y++) {
				Boolean lastBit = null;
				for (int x = rect[0] - margin; x < rect[0] + rect[2] + margin; x++) {
					boolean bit = bitMatrix.get(x, y);
					if (lastBit == null || !lastBit.equals(bit)) {
						lastBit = bit;
						ansiElements.add(bit ? AnsiBackground.BLACK : AnsiBackground.WHITE);
						ansiElements.add(bit ? AnsiColor.BLACK : AnsiColor.WHITE);
					}
					ansiElements.add(bit ? "##" : "  ");
				}
				ansiElements.add(AnsiBackground.DEFAULT);
				ansiElements.add(AnsiColor.DEFAULT);
				ansiElements.add(lf);
			}
			return AnsiOutput.toString(ansiElements.toArray());
		} else if (qrMode == QrMode.UNICODE_ANSI || qrMode == QrMode.UNICODE || qrMode == QrMode.UNICODE_REV) {
			StringBuilder qrsb = new StringBuilder();
			boolean ansi = QrMode.UNICODE_ANSI == qrMode;
			boolean reversed = qrMode == QrMode.UNICODE_REV;
			if (ansi) {
				qrsb.append(AnsiOutput.toString(AnsiColor.WHITE, AnsiBackground.BLACK));
			}
			int y1 = (rect[1] - margin) / 2 * 2;
			int y2 = (rect[1] + rect[3] + margin) / 2 * 2;
			for (int y = y1; y < y2; y+=2) {
				for (int x = rect[0] - margin; x < rect[0] + rect[2] + margin; x++) {
					boolean bit1 = bitMatrix.get(x, y) ^ reversed;
					boolean bit2 = bitMatrix.get(x, y+1) ^ reversed;
					char c;
					if (bit1 && bit2) {
						c = ' ';
					} else if (!bit1 && bit2) {
						c = '\u2580';
					} else if (bit1 && !bit2) {
						c = '\u2584';
					} else {
						c = '\u2588';
					}
					qrsb.append(c);
				}
				qrsb.append(lf);
			}
			if (ansi) {
				qrsb.append(AnsiOutput.toString(AnsiColor.DEFAULT, AnsiBackground.DEFAULT));
			}
			return qrsb.toString();
		} else if (qrMode == QrMode.ASCII) {
			StringBuilder qrsb = new StringBuilder();
			for (int y = rect[1] - margin; y < rect[1] + rect[3] + margin; y++) {
				for (int x = rect[0] - margin; x < rect[0] + rect[2] + margin; x++) {
					boolean bit = bitMatrix.get(x, y);
					qrsb.append(bit ? "  " : "##");
				}
				qrsb.append(lf);
			}
			return qrsb.toString();
		} else {
			return null;
		}
	}

	public static enum QrMode { AUTO, ASCII, ANSI, UNICODE, UNICODE_REV, UNICODE_ANSI, NONE}

}
