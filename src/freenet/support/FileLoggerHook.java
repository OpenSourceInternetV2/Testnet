package freenet.support;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.Closeable;
import java.io.DataInputStream;
import java.io.EOFException;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.PrintStream;
import java.io.UnsupportedEncodingException;
import java.net.InetAddress;
import java.text.DateFormat;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.ArrayDeque;
import java.util.Calendar;
import java.util.Date;
import java.util.Deque;
import java.util.GregorianCalendar;
import java.util.Iterator;
import java.util.Locale;
import java.util.StringTokenizer;
import java.util.TimeZone;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

import freenet.node.SemiOrderedShutdownHook;
import freenet.node.Version;
import freenet.support.io.Closer;
import freenet.support.io.FileUtil;

/**
 * Converted the old StandardLogger to Ian's loggerhook interface.
 * 
 * @author oskar
 */
public class FileLoggerHook extends LoggerHook implements Closeable {

	/** Verbosity types */
	public static final int DATE = 1,
		CLASS = 2,
		HASHCODE = 3,
		THREAD = 4,
		PRIORITY = 5,
		MESSAGE = 6,
		UNAME = 7;

	private volatile boolean closed = false;
	private boolean closedFinished = false;

	protected int INTERVAL = Calendar.MINUTE;
	protected int INTERVAL_MULTIPLIER = 5;
	
	private static final String ENCODING = "UTF-8";

        private static volatile boolean logMINOR;
	static {
		Logger.registerLogThresholdCallback(new LogThresholdCallback(){
			@Override
			public void shouldUpdate(){
				logMINOR = Logger.shouldLog(LogLevel.MINOR, this);
			}
		});
	}

	/** Name of the local host (called uname in Unix-like operating systems). */
	private static String uname;
	static {
		uname = "unknown";
	}

	static synchronized void getUName() {
		if(!uname.equals("unknown")) return;
		System.out.println("Getting uname for logging");
		try {
			InetAddress addr = InetAddress.getLocalHost();
			if (addr != null) {
				uname =
					new StringTokenizer(addr.getHostName(), ".").nextToken();
			}
		} catch (Exception e) {
			// Ignored.
		}
	}
	
	private DateFormat df;
	private int[] fmt;
	private String[] str;

	/** Stream to write data to (compressed if rotate is on) */
	protected OutputStream logStream;
	/** Other stream to write data to (may be null) */
	protected OutputStream altLogStream;

	protected final boolean logOverwrite;

	/* Base filename for rotating logs */
	protected String baseFilename = null;
	
	protected File latestFile;
	protected File previousFile;

	/* Whether to redirect stdout */
	protected boolean redirectStdOut = false;
	/* Whether to redirect stderr */
	protected boolean redirectStdErr = false;

	protected final int MAX_LIST_SIZE;
	protected long MAX_LIST_BYTES = 10 * (1 << 20);
	protected long LIST_WRITE_THRESHOLD;

	/**
	 * Something weird happens when the disk gets full, also we don't want to
	 * block So run the actual write on another thread
	 * 
	 * Unfortunately, we can't use ConcurrentBlockingQueue because we need to dump stuff when the queue gets
	 * too big.
	 * 
	 * FIXME PERFORMANCE: Using an ArrayBlockingQueue avoids some unnecessary memory allocations, but it 
	 * means we have to take two locks. 
	 * Seriously consider reverting 88268b99856919df0d42c2787d9ea3674a9f6f0d..e359b4005ef728a159fdee988c483de8ce8f3f6b
	 * to go back to one lock and a LinkedList.
	 */
	protected final ArrayBlockingQueue<byte[]> list;
	protected long listBytes = 0;

	long maxOldLogfilesDiskUsage;
	/** LOCKING: Protected by logFiles */
	private File currentLogFile;
	private long currentLogFileStartTime;
	private long currentLogFileEndTime;
	protected final Deque<OldLogFile> logFiles = new ArrayDeque<OldLogFile>();
	private long oldLogFilesDiskSpaceUsage = 0;

	private static class OldLogFile {
		public OldLogFile(File currentFilename, long startTime, long endTime, long length) {
			this.filename = currentFilename;
			this.start = startTime;
			this.end = endTime;
			this.size = length;
		}
		final File filename;
		final long start; // inclusive
		final long end; // exclusive
		final long size;
	}
	
	public void setMaxListBytes(long len) {
		synchronized(list) {
			MAX_LIST_BYTES = len;
			LIST_WRITE_THRESHOLD = MAX_LIST_BYTES / 4;
		}
	}

	public void setInterval(String intervalName) throws IntervalParseException {
		StringBuilder sb = new StringBuilder(intervalName.length());
		for(int i=0;i<intervalName.length();i++) {
			char c = intervalName.charAt(i);
			if(!Character.isDigit(c)) break;
			sb.append(c);
		}
		if(sb.length() > 0) {
			String prefix = sb.toString();
			intervalName = intervalName.substring(prefix.length());
			INTERVAL_MULTIPLIER = Integer.parseInt(prefix);
		} else {
			INTERVAL_MULTIPLIER = 1;
		}
		if (intervalName.endsWith("S")) {
			intervalName = intervalName.substring(0, intervalName.length()-1);
		}
		if (intervalName.equalsIgnoreCase("MINUTE"))
			INTERVAL = Calendar.MINUTE;
		else if (intervalName.equalsIgnoreCase("HOUR"))
			INTERVAL = Calendar.HOUR;
		else if (intervalName.equalsIgnoreCase("DAY"))
			INTERVAL = Calendar.DAY_OF_MONTH;
		else if (intervalName.equalsIgnoreCase("WEEK"))
			INTERVAL = Calendar.WEEK_OF_YEAR;
		else if (intervalName.equalsIgnoreCase("MONTH"))
			INTERVAL = Calendar.MONTH;
		else if (intervalName.equalsIgnoreCase("YEAR"))
			INTERVAL = Calendar.YEAR;
		else
			throw new IntervalParseException("invalid interval " + intervalName);
		System.out.println("Set interval to "+INTERVAL+" and multiplier to "+INTERVAL_MULTIPLIER);
	}

	public static class IntervalParseException extends Exception {

		private static final long serialVersionUID = 69847854744673572L;

		public IntervalParseException(String string) {
			super(string);
		}

	}
	
	/**
	 * The extra parameter int digit is to be used for creating a logfile name
	 * when a log exists already with the same date.
	 * @param c
	 * @param digit
	 *			log file name suffix. ignored if this is {@code < 0}
	 * @param compressed
	 * @return
	 */
	protected String getHourLogName(Calendar c, int digit, boolean compressed){
		StringBuilder buf = new StringBuilder(50);
		buf.append(baseFilename).append('-');
		buf.append(Version.buildNumber());
		buf.append('-');
		buf.append(c.get(Calendar.YEAR)).append('-');
		pad2digits(buf, c.get(Calendar.MONTH) + 1);
		buf.append('-');
		pad2digits(buf, c.get(Calendar.DAY_OF_MONTH));
		buf.append('-');
		pad2digits(buf, c.get(Calendar.HOUR_OF_DAY));
		if (INTERVAL == Calendar.MINUTE) {
			buf.append('-');
			pad2digits(buf, c.get(Calendar.MINUTE));
		}
		if (digit > 0) {
			buf.append("-");
			buf.append(digit);
		}
		buf.append(".log");
		if(compressed) buf.append(".gz");
		return buf.toString();
	}

	private StringBuilder pad2digits(StringBuilder buf, int x) {
		String s = Integer.toString(x);
		if (s.length() == 1) {
			buf.append('0');
		}
		buf.append(s);
		return buf;
	}
	
	// Unless we are writing flat out, everything will hit disk within this period.
	private long flushTime = 1000; // Default is 1 second. Will be set by setMaxBacklogNotBusy().

	static final TimeZone gmt = TimeZone.getTimeZone("GMT");
	
	class WriterThread extends Thread {
		WriterThread() {
			super("Log File Writer Thread");
		}

		@Override
		@SuppressWarnings("fallthrough")
		public void run() {
			File currentFilename = null;
			byte[] o = null;
			long thisTime;
			long lastTime = -1;
			long startTime;
			long nextHour = -1;
			GregorianCalendar gc = null;
			if (baseFilename != null) {
				latestFile = new File(baseFilename+"-latest.log");
				previousFile = new File(baseFilename+"-previous.log");
				gc = new GregorianCalendar(gmt, Locale.UK);
				switch (INTERVAL) {
					case Calendar.YEAR :
						gc.set(Calendar.MONTH, 0);
					case Calendar.MONTH :
						gc.set(Calendar.DAY_OF_MONTH, 0);
					case Calendar.WEEK_OF_YEAR :
						if (INTERVAL == Calendar.WEEK_OF_YEAR)
							gc.set(Calendar.DAY_OF_WEEK, 0);
					case Calendar.DAY_OF_MONTH :
						gc.set(Calendar.HOUR, 0);
					case Calendar.HOUR :
						gc.set(Calendar.MINUTE, 0);
					case Calendar.MINUTE :
						gc.set(Calendar.SECOND, 0);
						gc.set(Calendar.MILLISECOND, 0);
				}
				if(INTERVAL_MULTIPLIER > 1) {
					int x = gc.get(INTERVAL);
					gc.set(INTERVAL, (x / INTERVAL_MULTIPLIER) * INTERVAL_MULTIPLIER);
				}
				findOldLogFiles(gc);
				currentFilename = new File(getHourLogName(gc, -1, true));
				startTime = gc.getTimeInMillis();
				lastTime = startTime;
				gc.add(INTERVAL, INTERVAL_MULTIPLIER);
				nextHour = gc.getTimeInMillis();
				synchronized(logFiles) {
					if((!logFiles.isEmpty()) && logFiles.getLast().filename.equals(currentFilename)) {
						logFiles.removeLast();
					}
					currentLogFile = currentFilename;
					currentLogFileStartTime = startTime;
					currentLogFileEndTime = nextHour;
				}
				logStream = openNewLogFile(currentFilename, true);
				if(latestFile != null) {
					altLogStream = openNewLogFile(latestFile, false);
				}
				System.err.println("Created log files: "+currentFilename+" next threshold is "+new Date(gc.getTimeInMillis()));
		    	if(logMINOR)
		    		Logger.minor(this, "Start time: "+gc+" -> "+startTime);
			}
			long timeWaitingForSync = -1;
			long flush;
			synchronized(this) {
				flush = flushTime;
			}
			while (true) {
				try {
					thisTime = System.currentTimeMillis();
					if (baseFilename != null) {
						if ((thisTime > nextHour) || switchedBaseFilename) {
					        File newFilename = new File(getHourLogName(gc, -1, true));
							gc.add(INTERVAL, INTERVAL_MULTIPLIER);
							long newEndTime = gc.getTimeInMillis();
							rotateLog(currentFilename, newFilename, lastTime, nextHour, newEndTime, gc);
							currentFilename = newFilename;
							
							lastTime = nextHour;
							nextHour = newEndTime;

							if(switchedBaseFilename) {
								synchronized(FileLoggerHook.class) {
									switchedBaseFilename = false;
								}
							}
						}
					}
					boolean died = false;
					boolean timeoutFlush = false;
					synchronized (list) {
						flush = flushTime;
						long maxWait;
						if(timeWaitingForSync == -1)
							maxWait = Long.MAX_VALUE;
						else
							maxWait = timeWaitingForSync + flush;
						o = list.poll();
						while(o == null) {
							if (closed) {
								died = true;
								break;
							}
							try {
								if(thisTime < maxWait) {
									// Wait no more than 500ms since the CloserThread might be waiting for closedFinished.
									list.wait(Math.min(500, (int)(Math.min(maxWait-thisTime, Integer.MAX_VALUE))));
									thisTime = System.currentTimeMillis();
									if(listBytes < LIST_WRITE_THRESHOLD) {
										// Don't write at all until the lower bytes threshold is exceeded, or the time threshold is.
										assert((listBytes == 0) == (list.peek() == null));
										if(listBytes != 0 && maxWait == Long.MAX_VALUE)
											maxWait = thisTime + flush;
										if(closed) // If closing, write stuff ASAP.
											o = list.poll();
										else if(maxWait != Long.MAX_VALUE) {
											continue;
										}
									} else {
										// Do NOT use list.poll(timeout) because it uses a separate lock.
										o = list.poll();
									}
								}
							} catch (InterruptedException e) {
								// Ignored.
							}
							if(o == null) {
								if(timeWaitingForSync == -1) {
									timeWaitingForSync = thisTime;
									maxWait = thisTime + flush;
								}
								if(thisTime >= maxWait) {
									timeoutFlush = true;
									timeWaitingForSync = -1; // We have stuff to write, we are no longer waiting.
									break;
								}
							} else break;
						}
						if(o != null) {
							listBytes -= o.length + LINE_OVERHEAD;
						}
					}
					if(timeoutFlush || died) {
						// Flush to disk 
						myWrite(logStream, null);
				        if(altLogStream != null)
				        	myWrite(altLogStream, null);
					}
					if(died) {
						try {
							logStream.close();
						} catch (IOException e) {
							System.err.println("Failed to close log stream: "+e);
						}
						if(altLogStream != null) {
							try {
								altLogStream.close();
							} catch (IOException e) {
								System.err.println("Failed to close compressed log stream: "+e);
							}
						}
						synchronized(list) {
							closedFinished = true;
							list.notifyAll();
						}
						return;
					}
					if(o == null) continue;
					myWrite(logStream,  o);
			        if(altLogStream != null)
			        	myWrite(altLogStream, o);
				} catch (OutOfMemoryError e) {
					System.err.println(e.getClass());
					System.err.println(e.getMessage());
					e.printStackTrace();
				    // FIXME
					//freenet.node.Main.dumpInterestingObjects();
				} catch (Throwable t) {
					System.err.println("FileLoggerHook log writer caught " + t);
					t.printStackTrace(System.err);
				}
			}
		}

		private File rotateLog(File currentFilename, File newFilename, long lastTime, long nextHour, long newNextHour, GregorianCalendar gc) {
	        // Switch logs
	        try {
	        	logStream.flush();
	        	if(altLogStream != null) altLogStream.flush();
	        } catch (IOException e) {
	        	System.err.println(
	        		"Flushing on change caught " + e);
	        }
	        try {
	        	logStream.close();
	        } catch (IOException e) {
	        	System.err.println(
	        			"Closing on change caught " + e);
	        }
	        long length = currentFilename.length();
	        OldLogFile olf = new OldLogFile(currentFilename, lastTime, nextHour, length);
	        // Rotate primary log stream
	        currentFilename = newFilename;
	        synchronized(logFiles) {
	        	logFiles.addLast(olf);
	        	currentLogFile = currentFilename;
	        	currentLogFileStartTime = nextHour;
	        	currentLogFileEndTime = newNextHour;
	        }
	        oldLogFilesDiskSpaceUsage += length;
	        trimOldLogFiles();
	        logStream = openNewLogFile(currentFilename, true);
	        if(latestFile != null) {
	        	try {
	        		altLogStream.close();
	        	} catch (IOException e) {
	        		System.err.println(
	        				"Closing alt on change caught " + e);
	        	}
	        	if(previousFile != null && latestFile.exists())
	        		FileUtil.renameTo(latestFile, previousFile);
	        	latestFile.delete();
	        	altLogStream = openNewLogFile(latestFile, false);
	        }
	        return currentFilename;
        }

		// Check every minute
		static final int maxSleepTime = 60 * 1000;
		/**
		 * @param b
		 *            the bytes to write, null to flush
		 */
		protected void myWrite(OutputStream os, byte[] b) {
			long sleepTime = 1000;
			while (true) {
				boolean thrown = false;
				try {
					if (b != null)
						os.write(b);
					else
						os.flush();
				} catch (IOException e) {
					System.err.println(
						"Exception writing to log: "
							+ e
							+ ", sleeping "
							+ sleepTime);
					thrown = true;
				}
				if (thrown) {
					try {
						Thread.sleep(sleepTime);
					} catch (InterruptedException e) {
					}
					sleepTime += sleepTime;
					if (sleepTime > maxSleepTime)
						sleepTime = maxSleepTime;
				} else
					return;
			}
		}

		protected OutputStream openNewLogFile(File filename, boolean compress) {
			while (true) {
				long sleepTime = 1000;
				try {
					OutputStream o = new FileOutputStream(filename, !logOverwrite);
					if(compress) {
						// buffer -> gzip -> buffer -> file
						o = new BufferedOutputStream(o, 512*1024); // to file
						o = new GZIPOutputStream(o);
						// gzip block size is 32kB
						o = new BufferedOutputStream(o, 65536); // to gzipper
					} else {
						// buffer -> file
						o = new BufferedOutputStream(o, 512*1024);
					}
					o.write(BOM);
					return o;
				} catch (IOException e) {
					System.err.println(
						"Could not create FOS " + filename + ": " + e);
					System.err.println(
						"Sleeping " + sleepTime / 1000 + " seconds");
					try {
						Thread.sleep(sleepTime);
					} catch (InterruptedException ex) {
					}
					sleepTime += sleepTime;
				}
			}
		}
	}
	
	private static final byte[] BOM;
	
	static {
		try {
			BOM = (""+(char)0xFEFF).getBytes(ENCODING);
		} catch (UnsupportedEncodingException e) {
			throw new Error(e);
		}
	}

	protected int runningCompressors = 0;
	protected Object runningCompressorsSync = new Object();

	private Date myDate = new Date();

	/**
	 * Create a Logger to append to the given file. If the file does not exist
	 * it will be created.
	 * 
	 * @param filename
	 *            the name of the file to log to.
	 * @param fmt
	 *            log message format string
	 * @param dfmt
	 *            date format string
	 * @param threshold
	 *            Lowest logged priority
	 * @param assumeWorking
	 *            If false, check whether stderr and stdout are writable and if
	 *            not, redirect them to the log file
	 * @exception IOException
	 *                if the file couldn't be opened for append.
	 * @throws IntervalParseException 
	 */
	public FileLoggerHook(
		String filename,
		String fmt,
		String dfmt,
		String logRotateInterval,
		LogLevel threshold,
		boolean assumeWorking,
		boolean logOverwrite,
		long maxOldLogfilesDiskUsage, int maxListSize)
		throws IOException, IntervalParseException {
		this(
			false,
			filename,
			fmt,
			dfmt,
			logRotateInterval,
			threshold,
			assumeWorking,
			logOverwrite,
			maxOldLogfilesDiskUsage,
			maxListSize);
	}
	
	private final Object trimOldLogFilesLock = new Object();
	
	public void trimOldLogFiles() {
		synchronized(trimOldLogFilesLock) {
			while(oldLogFilesDiskSpaceUsage > maxOldLogfilesDiskUsage) {
				OldLogFile olf;
				// TODO: creates a double lock situation, but only here. I think this is okay because the inner lock is only used for trivial things.
				synchronized(logFiles) {
					if(logFiles.isEmpty()) {
						System.err.println("ERROR: INCONSISTENT LOGGER TOTALS: Log file list is empty but still used "+oldLogFilesDiskSpaceUsage+" bytes!");
					}
					olf = logFiles.removeFirst();
				}
				olf.filename.delete();
				oldLogFilesDiskSpaceUsage -= olf.size;
		    	if(logMINOR)
		    		Logger.minor(this, "Deleting "+olf.filename+" - saving "+olf.size+
						" bytes, disk usage now: "+oldLogFilesDiskSpaceUsage+" of "+maxOldLogfilesDiskUsage);
			}
		}
	}

	/** Initialize oldLogFiles */
	public void findOldLogFiles(GregorianCalendar gc) {
		File currentFilename = new File(getHourLogName(gc, -1, true));
		System.out.println("Finding old log files. New log file is "+currentFilename);
		File numericSameDateFilename;
		int slashIndex = baseFilename.lastIndexOf(File.separatorChar);
		File dir;
		String prefix;
		if(slashIndex == -1) {
			dir = new File(System.getProperty("user.dir"));
			prefix = baseFilename.toLowerCase();
		} else {
			dir = new File(baseFilename.substring(0, slashIndex));
			prefix = baseFilename.substring(slashIndex+1).toLowerCase();
		}
		File[] files = dir.listFiles();
		if(files == null) return;
		java.util.Arrays.sort(files);
		long lastStartTime = -1;
        if(latestFile.exists())
        	FileUtil.renameTo(latestFile, previousFile);

        ArrayList<File> sameTimes = new ArrayList<File>();
        
		for(int i=0;i<files.length;i++) {
			File f = files[i];
			String name = f.getName();
			if(name.toLowerCase().startsWith(prefix)) {
				if(name.equals(previousFile.getName()) || name.equals(latestFile.getName())) {
					continue;
				}
				if(!name.endsWith(".log.gz")) {
					if(logMINOR) Logger.minor(this, "Does not end in .log.gz: "+name);
					f.delete();
					continue;
				} else {
					name = name.substring(0, name.length()-".log.gz".length());
				}
				name = name.substring(prefix.length());
				if((name.length() == 0) || (name.charAt(0) != '-')) {
					if(logMINOR) Logger.minor(this, "Deleting unrecognized: "+name+" ("+f.getPath()+ ')');
					f.delete();
					continue;
				} else
					name = name.substring(1);
				String[] tokens = name.split("-");
				int[] nums = new int[tokens.length];
				for(int j=0;j<tokens.length;j++) {
					try {
						nums[j] = Integer.parseInt(tokens[j]);
					} catch (NumberFormatException e) {
						Logger.normal(this, "Could not parse: "+tokens[j]+" into number from "+name);
						// Broken
						f.delete();
						continue;
					}
				}
				if(nums.length > 1)
					gc.set(Calendar.YEAR, nums[1]);
				if(nums.length > 2)
					gc.set(Calendar.MONTH, nums[2]-1);
				if(nums.length > 3)
					gc.set(Calendar.DAY_OF_MONTH, nums[3]);
				if(nums.length > 4)
					gc.set(Calendar.HOUR_OF_DAY, nums[4]);
				if(nums.length > 5)
					gc.set(Calendar.MINUTE, nums[5]);
				gc.set(Calendar.SECOND, 0);
				gc.set(Calendar.MILLISECOND, 0);
				long startTime = gc.getTimeInMillis();
				if(lastStartTime == -1) {
					lastStartTime = startTime;
				} else if(lastStartTime != startTime) {
					for(File oldFile : sameTimes) {
						long l = oldFile.length();
						OldLogFile olf = new OldLogFile(oldFile, lastStartTime, startTime, l);
						synchronized(logFiles) {
							logFiles.addLast(olf);
						}
						synchronized(trimOldLogFilesLock) {
							oldLogFilesDiskSpaceUsage += l;
						}
					}
					sameTimes.clear();
					lastStartTime = startTime;
				}
				sameTimes.add(f);
			} else {
				// Nothing to do with us
				Logger.normal(this, "Unknown file: "+name+" in the log directory");
			}
		}
		long startTime = System.currentTimeMillis();
		for(File oldFile : sameTimes) {
			long l = oldFile.length();
			OldLogFile olf = new OldLogFile(oldFile, lastStartTime, startTime, l);
			synchronized(logFiles) {
				logFiles.addLast(olf);
			}
			synchronized(trimOldLogFilesLock) {
				oldLogFilesDiskSpaceUsage += l;
			}
		}
		//If a compressed log file already exists for a given date,
		//add a number to the end of the file that already exists
		if(currentFilename != null && currentFilename.exists()) {
			System.out.println("Old log file exists for this time period: "+currentFilename);
			for(int a = 1;; a++){
				numericSameDateFilename = new File(getHourLogName(gc, a, true));
				if(numericSameDateFilename == null || !numericSameDateFilename.exists()) {
					if(numericSameDateFilename != null) {
						System.out.println("Renaming to: "+numericSameDateFilename);
						FileUtil.renameTo(currentFilename, numericSameDateFilename);
						synchronized(logFiles) {
							for(OldLogFile f : logFiles) {
								if(f.filename.equals(currentFilename)) {
									logFiles.remove(f);
									logFiles.add(new OldLogFile(numericSameDateFilename, f.start, f.end, f.size));
								}
							}
						}
					}
					break;
				}
			}
		}
		trimOldLogFiles();
	}

	public FileLoggerHook(
			String filename,
			String fmt,
			String dfmt,
			String threshold,
			String logRotateInterval,
			boolean assumeWorking,
			boolean logOverwrite,
			long maxOldLogFilesDiskUsage,
			int maxListSize)
			throws IOException, InvalidThresholdException, IntervalParseException {
			this(filename,
				fmt,
				dfmt,
				logRotateInterval,
				LogLevel.valueOf(threshold.toUpperCase()),
				assumeWorking,
				logOverwrite,
				maxOldLogFilesDiskUsage,
				maxListSize);
		}

	private void checkStdStreams() {
		// Redirect System.err and System.out to the Logger Printstream
		// if they don't exist (like when running under javaw)
		System.out.print(" \b");
		if (System.out.checkError()) {
			redirectStdOut = true;
		}
		System.err.print(" \b");
		if (System.err.checkError()) {
			redirectStdErr = true;
		}
	}

	public FileLoggerHook(
		OutputStream os,
		String fmt,
		String dfmt,
		LogLevel threshold) throws IntervalParseException {
		this(os, fmt, dfmt, threshold, true);
		logStream = os;
	}
	
	public FileLoggerHook(
			OutputStream os,
			String fmt,
			String dfmt,
			String threshold) throws InvalidThresholdException, IntervalParseException {
			this(os, fmt, dfmt, LogLevel.valueOf(threshold.toUpperCase()), true);
			logStream = os;
		}

	/**
	 * Create a Logger to send log output to the given PrintStream.
	 * 
	 * @param stream
	 *            the PrintStream to send log output to.
	 * @param fmt
	 *            log message format string
	 * @param dfmt
	 *            date format string
	 * @param threshold
	 *            Lowest logged priority
	 * @throws IntervalParseException 
	 */
	public FileLoggerHook(
		OutputStream stream,
		String fmt,
		String dfmt,
		LogLevel threshold,
		boolean overwrite) throws IntervalParseException {
		this(fmt, dfmt, threshold, "HOUR", overwrite, -1, 10000);
		logStream = stream;
	}

	public void start() {
		if(redirectStdOut) {
			try {
				System.setOut(new PrintStream(new OutputStreamLogger(LogLevel.NORMAL, "Stdout: ", ENCODING), false, ENCODING));
				if(redirectStdErr)
					System.setErr(new PrintStream(new OutputStreamLogger(LogLevel.ERROR, "Stderr: ", ENCODING), false, ENCODING));
			} catch (UnsupportedEncodingException e) {
				throw new Error(e);
			}
		}
		WriterThread wt = new WriterThread();
		wt.setDaemon(true);
		CloserThread ct = new CloserThread();
		SemiOrderedShutdownHook.get().addLateJob(ct);
		wt.start();
	}
	
	public FileLoggerHook(
		boolean rotate,
		String baseFilename,
		String fmt,
		String dfmt,
		String logRotateInterval,
		LogLevel threshold,
		boolean assumeWorking,
		boolean logOverwrite,
		long maxOldLogfilesDiskUsage, int maxListSize)
		throws IOException, IntervalParseException {
		this(fmt, dfmt, threshold, logRotateInterval, logOverwrite, maxOldLogfilesDiskUsage, maxListSize);
		//System.err.println("Creating FileLoggerHook with threshold
		// "+threshold);
		if (!assumeWorking)
			checkStdStreams();
		if (rotate) {
			this.baseFilename = baseFilename;
		} else {
			logStream = new BufferedOutputStream(new FileOutputStream(baseFilename, !logOverwrite), 65536);
		}
	}
	
	public FileLoggerHook(
			boolean rotate,
			String baseFilename,
			String fmt,
			String dfmt,
			String threshold,
			String logRotateInterval,
			boolean assumeWorking,
			boolean logOverwrite,
			long maxOldLogFilesDiskUsage, int maxListSize) throws IOException, InvalidThresholdException, IntervalParseException{
		this(rotate,baseFilename,fmt,dfmt,logRotateInterval,LogLevel.valueOf(threshold.toUpperCase()),assumeWorking,logOverwrite,maxOldLogFilesDiskUsage,maxListSize);
	}

	private FileLoggerHook(String fmt, String dfmt, LogLevel threshold, String logRotateInterval, boolean overwrite, long maxOldLogfilesDiskUsage, int maxListSize) throws IntervalParseException {
		super(threshold);
		this.maxOldLogfilesDiskUsage = maxOldLogfilesDiskUsage;
		this.logOverwrite = overwrite;
		setInterval(logRotateInterval);
		
		MAX_LIST_SIZE = maxListSize;
		list = new ArrayBlockingQueue<byte[]>(MAX_LIST_SIZE);
		
		setDateFormat(dfmt);
		setLogFormat(fmt);
	}

	private void setLogFormat(String fmt) {
		if ((fmt == null) || (fmt.length() == 0))
			fmt = "d:c:h:t:p:m";
		char[] f = fmt.toCharArray();

		ArrayList<Integer> fmtVec = new ArrayList<Integer>();
		ArrayList<String> strVec = new ArrayList<String>();

		StringBuilder sb = new StringBuilder();

		boolean comment = false;
		for (char fi: f) {
			int type = numberOf(fi);
			if(type == UNAME)
				getUName();
			if (!comment && (type != 0)) {
				if (sb.length() > 0) {
					strVec.add(sb.toString());
					fmtVec.add(0);
					sb = new StringBuilder();
				}
				fmtVec.add(type);
			} else if (fi == '\\') {
				comment = true;
			} else {
				comment = false;
				sb.append(fi);
			}
		}
		if (sb.length() > 0) {
			strVec.add(sb.toString());
			fmtVec.add(0);
		}

		this.fmt = new int[fmtVec.size()];
		int size = fmtVec.size();
		for (int i = 0; i < size; ++i)
			this.fmt[i] = fmtVec.get(i);

		this.str = new String[strVec.size()];
		str = strVec.toArray(str);
	}

	private void setDateFormat(String dfmt) {
		if ((dfmt != null) && (dfmt.length() != 0)) {
			try {
				df = new SimpleDateFormat(dfmt);
			} catch (RuntimeException e) {
				df = DateFormat.getDateTimeInstance();
			}
		} else
			df = DateFormat.getDateTimeInstance();

		df.setTimeZone(TimeZone.getTimeZone("UTC"));
	}

	@Override
	public void log(Object o, Class<?> c, String msg, Throwable e, LogLevel priority) {
		if (!instanceShouldLog(priority, c))
			return;

		if (closed)
			return;
		
		StringBuilder sb = new StringBuilder( e == null ? 512 : 1024 );
		int sctr = 0;

		for (int f: fmt) {
			switch (f) {
				case 0 :
					sb.append(str[sctr++]);
					break;
				case DATE :
					long now = System.currentTimeMillis();
					synchronized (this) {
						myDate.setTime(now);
						sb.append(df.format(myDate));
					}
					break;
				case CLASS :
					sb.append(c == null ? "<none>" : c.getName());
					break;
				case HASHCODE :
					sb.append(
						o == null
							? "<none>"
							: Integer.toHexString(o.hashCode()));
					break;
				case THREAD :
					sb.append(Thread.currentThread().getName());
					break;
				case PRIORITY :
					sb.append(priority.name());
					break;
				case MESSAGE :
					sb.append(msg);
					break;
				case UNAME :
					sb.append(uname);
					break;
			}
		}
		sb.append('\n');

		// Write stacktrace if available
		for(int j=0;j<20 && e != null;j++) {
			sb.append(e.toString());
			
			StackTraceElement[] trace = e.getStackTrace();
			
			if(trace == null)
				sb.append("(null)\n");
			else if(trace.length == 0)
				sb.append("(no stack trace)\n");
			else {
				sb.append('\n');
				for(StackTraceElement elt: trace) {
					sb.append("\tat ");
					sb.append(elt.toString());
					sb.append('\n');
				}
			}
			
			Throwable cause = e.getCause();
			if(cause != e) e = cause;
			else break;
		}

		try {
			logString(sb.toString().getBytes(ENCODING));
		} catch (UnsupportedEncodingException e1) {
			throw new Error(e1);
		}
	}

	/** Memory allocation overhead (estimated through experimentation with bsh) */
	private static final int LINE_OVERHEAD = 60;
	
	public void logString(byte[] b) throws UnsupportedEncodingException {
		synchronized (list) {
			int sz = list.size();
			if(!list.offer(b)) {
				byte[] ss = list.poll();
				if(ss != null) listBytes -= ss.length + LINE_OVERHEAD;
				ss = list.poll();
				if(ss != null) listBytes -= ss.length + LINE_OVERHEAD;
				String err =
					"GRRR: ERROR: Logging too fast, chopped "
						+ 2
						+ " entries, "
						+ listBytes
						+ " bytes in memory\n";
				byte[] buf = err.getBytes(ENCODING);
				if(list.offer(buf))
					listBytes += (buf.length + LINE_OVERHEAD);
				if(list.offer(b))
					listBytes += (b.length + LINE_OVERHEAD);
			} else
				listBytes += (b.length + LINE_OVERHEAD);
			int x = 0;
			if (listBytes > MAX_LIST_BYTES) {
				while ((list.size() > (MAX_LIST_SIZE * 0.9F))
					|| (listBytes > (MAX_LIST_BYTES * 0.9F))) {
					byte[] ss;
					ss = list.poll();
					listBytes -= (ss.length + LINE_OVERHEAD);
					x++;
				}
				String err =
					"GRRR: ERROR: Logging too fast, chopped "
						+ x
						+ " entries, "
						+ listBytes
						+ " bytes in memory\n";
				byte[] buf = err.getBytes(ENCODING);
				if(!list.offer(buf)) {
					byte[] ss = list.poll();
					if(ss != null) listBytes -= ss.length + LINE_OVERHEAD;
					if(list.offer(buf))
						listBytes += (buf.length + LINE_OVERHEAD);
				} else
					listBytes += (buf.length + LINE_OVERHEAD);
			}
			if (sz == 0)
				list.notifyAll();
		}
	}

	public long listBytes() {
		synchronized (list) {
			return listBytes;
		}
	}

	public static int numberOf(char c) {
		switch (c) {
			case 'd' :
				return DATE;
			case 'c' :
				return CLASS;
			case 'h' :
				return HASHCODE;
			case 't' :
				return THREAD;
			case 'p' :
				return PRIORITY;
			case 'm' :
				return MESSAGE;
			case 'u' :
				return UNAME;
			default :
				return 0;
		}
	}

	@Override
	public void close() {
		closed = true;
	}

	class CloserThread extends Thread {
		@Override
		public void run() {
			synchronized(list) {
				closed = true;
				long deadline = System.currentTimeMillis() + 10*1000;
				while(!closedFinished) {
					int wait = (int) (deadline - System.currentTimeMillis());
					if(wait <= 0) return;
					try {
						list.wait(wait);
					} catch (InterruptedException e) {
						// Ok.
					}
				}
				System.out.println("Completed writing logs to disk.");
			}
		}
	}

	/**
	 * Print a human- and script- readable list of available log files.
	 * @throws IOException 
	 */
	public SimpleFieldSet listAvailableLogs() {
		SimpleFieldSet fs = new SimpleFieldSet(true);
		OldLogFile[] oldLogFiles;
		synchronized(logFiles) {
			oldLogFiles = logFiles.toArray(new OldLogFile[logFiles.size()]);
		}
		DateFormat tempDF = DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT, Locale.UK);
		tempDF.setTimeZone(TimeZone.getTimeZone("GMT"));
		for(int i=0;i<=oldLogFiles.length;i++) {
			OldLogFile olf;
			if(i < oldLogFiles.length)
				olf = oldLogFiles[i];
			else
				olf = new OldLogFile(currentLogFile, currentLogFileStartTime, currentLogFileEndTime, currentLogFile.length());
			SimpleFieldSet subset = new SimpleFieldSet(true);
			subset.putSingle("Filename", olf.filename.getName());
			subset.putSingle("DateStart", tempDF.format(new Date(olf.start)));
			subset.putSingle("DateEnd", tempDF.format(new Date(olf.end)));
			subset.put("Size", olf.size);
			fs.put(Integer.toString(i), subset);
		}
		return fs;
	}

	public void sendLogByContainedDate(long time, OutputStream os, Pattern p) throws IOException {
		ArrayList<OldLogFile> toReturn = new ArrayList<OldLogFile>();
		synchronized(logFiles) {
			Iterator<OldLogFile> i = logFiles.iterator();
			boolean doneLast = false;
			while(true) {
				OldLogFile olf;
				if(i.hasNext()) {
					olf = i.next();
				} else {
					if(doneLast) break;
					doneLast = true;
					olf = new OldLogFile(currentLogFile, currentLogFileStartTime, currentLogFileEndTime, currentLogFile.length());
				}
				System.out.println("Checking "+time+" against "+olf.filename+" : start="+new Date(olf.start)+", end="+new Date(olf.end));
		    	if(logMINOR)
		    		Logger.minor(this, "Checking "+time+" against "+olf.filename+" : start="+olf.start+", end="+olf.end);
				if((time >= olf.start) && (time < olf.end)) {
					toReturn.add(olf);
					if(logMINOR) Logger.minor(this, "Found "+olf);
				}
			}
			
			if(toReturn.isEmpty()) {
				System.out.println("Could not find log file");
				return; // couldn't find it
			}
		}
		BufferedWriter osw = new BufferedWriter(new OutputStreamWriter(os, "UTF-8"));
		if(p == null) {
			osw.write("LogCount:"+toReturn.size()+"\n");
			osw.flush();
			for(OldLogFile olf : toReturn) {
				System.out.println("Writing data from log "+olf.filename);
				FileInputStream fis = null;
				try {
					fis = new FileInputStream(olf.filename);
					DataInputStream dis = new DataInputStream(fis);
					long written = 0;
					long size = olf.size;
					osw.write("Log:"+olf.filename.getName()+"\n");
					osw.write("LENGTH: "+size+"\n");
					osw.flush();
					byte[] buf = new byte[4096];
					while(written < size) {
						int toRead = (int) Math.min(buf.length, (size - written));
						try {
							dis.readFully(buf, 0, toRead);
						} catch (IOException e) {
							Logger.error(this, "Could not read bytes "+written+" to "+(written + toRead)+" from file "+olf.filename+" which is supposed to be "+size+" bytes ("+olf.filename.length()+ ')');
							return;
						}
						os.write(buf, 0, toRead);
						written += toRead;
					}
				} finally {
					Closer.close(fis);
				}
			}
		} else {
			long timeSent = System.currentTimeMillis();
			osw.flush();
			// Gzip the rest.
			GZIPOutputStream gos = new GZIPOutputStream(os);
			osw = new BufferedWriter(new OutputStreamWriter(gos, "UTF-8"));
			for(OldLogFile olf : toReturn) {
				System.out.println("Writing data from log "+olf.filename);
				// We will just prefix each line appropriately.
				FileInputStream fis = null;
				try {
					fis = new FileInputStream(olf.filename);
					GZIPInputStream gis = new GZIPInputStream(new BufferedInputStream(fis));
					BufferedReader br = new BufferedReader(new InputStreamReader(gis));
					String line;
					while((line = br.readLine()) != null) {
						if(p.matcher(line).find()) {
							osw.write("MATCH:"+line+"\n");
							timeSent = System.currentTimeMillis();
						} else {
							if(System.currentTimeMillis() - timeSent > 5*1000) {
								osw.write("WAIT\n");
								osw.flush();
								timeSent = System.currentTimeMillis();
							}
						}
					}
				} catch (EOFException e) {
					// Okay.
				} catch (IOException e) {
					// Not okay.
					osw.write("Error:IOException\n");
					osw.flush();
					return;
				} finally {
					Closer.close(fis);
				}
			}
			osw.write("EndLogFiltered\n");
			osw.flush();
			gos.finish();
		}
	}

	/** Set the maximum size of old (gzipped) log files to keep.
	 * Will start to prune old files immediately, but this will likely not be completed
	 * by the time the function returns as it is run off-thread.
	 */
	public void setMaxOldLogsSize(long val) {
		synchronized(trimOldLogFilesLock) {
			maxOldLogfilesDiskUsage = val;
		}
		Runnable r = new Runnable() {
			@Override
			public void run() {
				trimOldLogFiles();
			}
		};
		Thread t = new Thread(r, "Shrink logs");
		t.setDaemon(true);
		t.start();
	}

	private boolean switchedBaseFilename;
	
	public void switchBaseFilename(String filename) {
		synchronized(this) {
			this.baseFilename = filename;
			switchedBaseFilename = true;
		}
	}

	public void waitForSwitch() {
		long now = System.currentTimeMillis();
		synchronized(this) {
			if(!switchedBaseFilename) return;
			long startTime = now;
			long endTime = startTime + 10000;
			while(((now = System.currentTimeMillis()) < endTime) && !switchedBaseFilename) {
				try {
					wait(Math.max(1, endTime-now));
				} catch (InterruptedException e) {
					// Ignore
				}
			}
		}
	}

	public void deleteAllOldLogFiles() {
		synchronized(trimOldLogFilesLock) {
			while(true) {
				OldLogFile olf;
				synchronized(logFiles) {
					if(logFiles.isEmpty()) return;
					olf = logFiles.removeFirst();
				}
				olf.filename.delete();
				oldLogFilesDiskSpaceUsage -= olf.size;
				if(logMINOR)
					Logger.minor(this, "Deleting "+olf.filename+" - saving "+olf.size+
							" bytes, disk usage now: "+oldLogFilesDiskSpaceUsage+" of "+maxOldLogfilesDiskUsage);
			}
		}
	}

	/**
	 * This is used by the lost-lock deadlock detector so MUST NOT TAKE A LOCK ever!
	 */
	public boolean hasRedirectedStdOutErrNoLock() {
		return redirectStdOut || redirectStdErr;
	}

	public synchronized void setMaxBacklogNotBusy(long val) {
		flushTime = val;
	}
}
