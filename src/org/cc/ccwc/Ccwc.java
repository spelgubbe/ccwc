package org.cc.ccwc;

import java.io.*;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class Ccwc {

    /**
     * Default is that wc shows counts of: lines TAB words TAB bytes.
     */
    private record Options (boolean countLines, boolean countWords,
			    boolean countBytes, boolean countChars,
			    Path filePath) {

	public static Options defaults () {
	    return new Options (true, true, true, false, null);
	}
    }

    private record WcResult (long numLines, long numWords, long numBytes, long numChars) {
    	// empty
    }

    private static class ByteCountingStream extends InputStream {

	private final InputStream delegate;
	private long count = 0;

	public ByteCountingStream (InputStream is) {
	    this.delegate = is;
	}

	@Override
	public int read () throws IOException {
	    // this relies on this method actually being called.
	    int read = delegate.read ();
	    if (read != -1) { // treat end of stream as 0 bytes read
		count++;
	    }
	    return read;
	}

	public long getByteCount () {
	    return count;
	}
    }

    static void main (String[] args) throws IOException {

	Options options = parseOptions (args);
	WcResult wcResult = readStream ();
	writeResult (options, wcResult);
    }

    private static void writeResult (Options options, WcResult result) {

	List<Long> results = new ArrayList<> ();
	if (options.countLines) {
	    results.add (result.numLines);
	}
	if (options.countWords) {
	    results.add (result.numWords);
	}
	if (options.countBytes) {
	    results.add (result.numBytes);
	}
	if (options.countChars) {
	    results.add (result.numChars);
	}
	writeTabbedResults (results, System.out);
    }

    private static void writeTabbedResults (List<Long> counts, PrintStream ps) {
	boolean hasWritten = false;
	for (Long count : counts) {
	    if (hasWritten) {
		ps.print ('\t');
	    }
	    ps.print (count);
	    hasWritten = true;
	}
	ps.println ();
    }

    private static WcResult readStream () throws IOException {
	// read stdin...
	// start with just printing the bytes...
	InputStream is = System.in;
	// count the bytes
	ByteCountingStream cis = new ByteCountingStream (is);

	// stats
	int wordsRead = 0;
	int linesRead = 0;
	// define chars read as the number of valid code points read
	int charsRead = 0;
	// trust the default charset
	// java.nio.charset.Charset.defaultCharset
	// use a reader to not reimplement bytes to unicode code points etc (for character counting)
	Reader reader = new InputStreamReader (cis);
	int codePoint;
	boolean inWord = false;
	while ((codePoint = getNextCodePoint (reader)) != -1) {
	    if (codePoint == '\n') {
		linesRead++;
	    }
	    charsRead += Character.charCount (codePoint);
	    // whitespace breaks words. Newline and all related codepoints should be caught here.
	    if (Character.isWhitespace (codePoint)) {
		inWord = false;
	    } else { // anything non-whitespace is considered a word.
		if (!inWord) {
		    wordsRead++;
		}
		inWord = true;
	    }
	}

	long bytesRead = cis.getByteCount ();
	System.out.println ("Lines: " + linesRead);
	System.out.println ("Words: " + wordsRead);
	System.out.println ("Bytes: " + bytesRead);
	System.out.println ("Chars: " + charsRead);
	return new WcResult (linesRead, wordsRead, bytesRead, charsRead);
    }

    /**
     * Parse a unicode code point from a reader. Any incomplete code point
     *
     */
    private static int getNextCodePoint (Reader r) throws IOException {
	int read1 = r.read ();
	if (read1 == -1) {
	    return -1;
	}
	char first = (char)read1;
	if (Character.isHighSurrogate (first)) {
	    int read2 = r.read ();
	    // If we reach -1 while trying to parse a unicode char,
	    // then we count that as no character at all (since it wont be valid).
	    // So just propagate -1.
	    if (read2 == -1) {
		return -1;
	    }
	    char second = (char)read2;
	    int cp = Character.toCodePoint (first, second);
	    if (Character.isValidCodePoint (cp)) {
		return cp; // if invalid, we just return the first char instead.
	    }
	    System.err.println ("Invalid code point found from bytes: %s, %s".formatted (
		    Integer.toHexString (first), Integer.toHexString (second)));
	}
	// assume low surrogate chars are valid code points.
	return first;
    }


    private static Options parseOptions (String[] args) {
	boolean countLines = anyMatch (args, new CountLines ());
	boolean countWords = anyMatch (args, new CountWords ());
	boolean countBytes = anyMatch (args, new CountBytes ());
	boolean countChars = anyMatch (args, new CountChars ());
	if (none (countLines, countWords, countBytes, countChars)) {
	    return Options.defaults ();
	}
	return new Options (countLines, countWords, countBytes, countChars, null);
    }

    private static boolean none (boolean... bools) {
	for (boolean bool : bools) {
	    if (bool) {
		return false;
	    }
	}
	return true;
    }

    private record ArgParseResult (boolean matched, List<String> remainingArgs) {
	// empty
    }

    private static boolean anyMatch (String[] args, Arg argument) {
	for (String arg : args) {
	    if (argument.matches (arg)) {
		return true;
	    }
	}
	return false;
    }

    private interface Arg {
	boolean matches (String arg);
    }

    private static class PatternArg implements Arg {
	private final Pattern pattern;
	public PatternArg (String pattern) {
	    this.pattern = Pattern.compile (pattern);

	}

	@Override
	public boolean matches (String arg) {
	    return pattern.matcher (arg).matches ();
	}
    }

    private static class CountLines extends PatternArg {
	public CountLines () {
	    super ("-l");
	}
    }

    private static class CountBytes extends PatternArg {
	public CountBytes () {
	    super ("-c");
	}
    }

    private static class CountWords extends PatternArg {
	public CountWords () {
	    super ("-w");
	}
    }

    private static class CountChars extends PatternArg {
	public CountChars () {
	    super ("-m");
	}
    }
}
