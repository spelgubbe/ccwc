package org.cc.ccwc;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

public class Ccwc {

    private static final List<Arg> DASH_ARGS = List.of (new CountWords (), new CountBytes (),
							new CountLines (), new CountChars ());

    /**
     * Default is that wc shows counts of: lines TAB words TAB bytes.
     */
    private record Options (boolean countLines, boolean countWords,
			    boolean countBytes, boolean countChars,
			    List<String> filePaths) {

	public static Options defaults () {
	    return new Options (true, true, true, false, null);
	}

	public Options withFilePaths (List<String> paths) {
	    return new Options (countLines, countWords, countBytes, countChars, paths);
	}

	public boolean hasFilePaths () {
	    return filePaths != null && !filePaths.isEmpty ();
	}
    }

    private record WcResult (long numLines, long numWords, long numBytes, long numChars) {
    	public static WcResult sum (WcResult a, WcResult b) {
	    long numLines = a.numLines + b.numLines;
	    long numWords = a.numWords + b.numWords;
	    long numBytes = a.numBytes + b.numBytes;
	    long numChars = a.numChars + b.numChars;
	    return new WcResult (numLines, numWords, numBytes, numChars);
	}
    }

    private sealed interface FilePathResult {
	record NOT_EXISTS () implements FilePathResult { }
	record IS_DIRECTORY () implements FilePathResult {}
	record INVALID_PATH () implements FilePathResult {}
	record Valid (Path p) implements FilePathResult {}
    }

    private static FilePathResult readFilePath (String filePath) {
	try {
	    Path p = Path.of (filePath);
	    if (Files.notExists (p)) {
		return new FilePathResult.NOT_EXISTS ();
	    } else if (Files.isDirectory (p)) {
	    	return new FilePathResult.IS_DIRECTORY ();
	    }
	    return new FilePathResult.Valid (p);
	} catch (InvalidPathException e) {
	    return new FilePathResult.INVALID_PATH ();
	}
    }

    private static void handleFiles (Options options) throws IOException {
	List<String> filePaths = options.filePaths ();
	WcResult total = new WcResult (0, 0, 0, 0);
	for (String filePath : filePaths) {
	    total = handleFile (filePath, options, total);
	}
	if (filePaths.size () > 1) { // write totals if there is more than one input file given
	    writeResult (options, "total", total);
	}
    }

    private static WcResult handleFile (String filePath, Options options, WcResult totalCounter)
    throws IOException {
	FilePathResult pathResult = readFilePath (filePath);
	switch (pathResult) {
	    case FilePathResult.INVALID_PATH (), FilePathResult.NOT_EXISTS () -> {
		System.err.println ("wc: " +  filePath + ": No such file or directory");
	    }
	    case FilePathResult.IS_DIRECTORY ()-> {
		System.err.println ("wc: " + filePath +  ": Is a directory");
	    }
	    case FilePathResult.Valid (Path p) -> {
		try (InputStream fis = Files.newInputStream (p)) {
		    WcResult wcResult = readStream (fis);
		    writeResult (options, filePath, wcResult);
		    return WcResult.sum (totalCounter, wcResult);
		}
	    }
	}
	return totalCounter;
    }

    private static class ByteCountingStream extends InputStream {

	private final InputStream delegate;
	private long count = 0;

	public ByteCountingStream (InputStream is) {
	    this.delegate = is;
	}

	@Override
	public int read () throws IOException {
	    // this count relies on this method actually being called.
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

	if (!validateOrPrintUsage (args)) {
	    System.exit (-1);
	}
	Options options = parseOptions (args);
	if (options.hasFilePaths ()) {
	    handleFiles (options);
	} else {
	    WcResult wcResult = readStream (System.in);
	    writeResult (options, null, wcResult);
	}
    }

    private static void writeResult (Options options, String resultName, WcResult result) {

	List<Object> results = new ArrayList<> ();
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
	if (resultName != null) {
	    results.add (resultName);
	}
	writeTabbedStrings (results, System.out);
    }

    private static void writeTabbedStrings (List<Object> stats, PrintStream ps) {
	boolean hasWritten = false;
	for (Object stat : stats) {
	    if (hasWritten) {
		ps.print ('\t');
	    }
	    ps.print (stat);
	    hasWritten = true;
	}
	ps.println ();
    }

    private static WcResult readStream (InputStream is) throws IOException {
	// read stdin...
	// start with just printing the bytes...
	// count the bytes
	ByteCountingStream cis = new ByteCountingStream (is);

	// stats
	int wordsRead = 0;
	int linesRead = 0;
	// define chars read as the number of valid code points read
	int charsRead = 0;
	// trust the default charset
	// use a reader to not reimplement bytes to unicode code points etc (for character counting)
	Reader reader = new InputStreamReader (cis);
	int codePoint;
	boolean inWord = false;
	while ((codePoint = getNextCodePoint (reader)) != -1) {
	    if (codePoint == '\n') {
		linesRead++;
	    }
	    charsRead++;
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

    private static boolean validateOrPrintUsage (String[] args) {
	for (String arg : args) {
	    if (arg.equals ("--help") || arg.equals ("--h")) {
		printUsage ();
		return false;
	    } else {
		// validate arg
		if (!validateArg (arg)) {
		    System.err.println ("ccwc: unknown argument '" + arg + "'");
		    System.err.println ("Try 'wc --help' for more information.");
		    return false;
		}
	    }
	}
	return true; // passes validation
    }

    private static void printUsage () {
	String help = """
		The options below may be used to select which counts are printed, always in
		the following order: newline, word, character, and byte.
		  -c, --bytes            print the byte counts
		  -m, --chars            print the character counts
		  -l, --lines            print the newline counts
		  -w, --words            print the word counts
		      --help     display this help and exit
		""";
	System.out.println (help);
    }


    private static Options parseOptions (String[] args) {
	boolean countLines = anyMatch (args, new CountLines ());
	boolean countWords = anyMatch (args, new CountWords ());
	boolean countBytes = anyMatch (args, new CountBytes ());
	boolean countChars = anyMatch (args, new CountChars ());
	List<String> filePaths = readFilePaths (args);
	if (none (countLines, countWords, countBytes, countChars)) {
	    return Options.defaults ().withFilePaths (filePaths);
	}
	return new Options (countLines, countWords, countBytes, countChars, filePaths);
    }

    private static List<String> readFilePaths (String[] args) {
	List<String> paths = new ArrayList<> ();
	for (String arg : args) {
	    if (!isDashArg (arg)) {
		paths.add (arg);
	    }
	}
	return paths;
    }

    private static boolean none (boolean... bools) {
	for (boolean bool : bools) {
	    if (bool) {
		return false;
	    }
	}
	return true;
    }

    private static boolean isDashArg (String arg) {
	return arg.indexOf ('-') == 0;
    }

    private static boolean validateArg (String arg) {
	Arg anyDashArg = orArg (DASH_ARGS);
	if (isDashArg (arg)) {
	    return anyDashArg.matches (arg);
	}
	// non-dash args are interpreted as file paths
	return true;
    }


    private static boolean anyMatch (String[] args, Arg argument) {
	for (String arg : args) {
	    if (argument.matches (arg)) {
		return true;
	    }
	}
	return false;
    }

    private static Arg orArg (List<Arg> args) {
	return argString -> {
	    for (Arg arg1 : args) {
		if (arg1.matches (argString)) {
		    return true;
		}
	    }
	    return false;
	};
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
	    super ("-l|--lines");
	}
    }

    private static class CountBytes extends PatternArg {
	public CountBytes () {
	    super ("-c|--bytes");
	}
    }

    private static class CountWords extends PatternArg {
	public CountWords () {
	    super ("-w|--words");
	}
    }

    private static class CountChars extends PatternArg {
	public CountChars () {
	    super ("-m|--chars");
	}
    }
}
