package com.relativitas.maven.plugins.formatter;

/*
 * Copyright 2010. All work is copyrighted to their respective author(s),
 * unless otherwise stated.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.UnsupportedEncodingException;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.apache.commons.codec.digest.DigestUtils;
import org.apache.maven.plugin.AbstractMojo;
import org.apache.maven.plugin.MojoExecutionException;
import org.apache.maven.plugin.logging.Log;
import org.codehaus.plexus.resource.ResourceManager;
import org.codehaus.plexus.resource.loader.FileResourceLoader;
import org.codehaus.plexus.resource.loader.ResourceNotFoundException;
import org.codehaus.plexus.util.IOUtil;
import org.codehaus.plexus.util.PropertyUtils;
import org.codehaus.plexus.util.ReaderFactory;
import org.codehaus.plexus.util.StringUtils;
import org.eclipse.jdt.core.JavaCore;
import org.eclipse.jdt.core.ToolFactory;
import org.eclipse.jdt.core.formatter.CodeFormatter;
import org.eclipse.jface.text.BadLocationException;
import org.eclipse.jface.text.Document;
import org.eclipse.jface.text.IDocument;
import org.eclipse.text.edits.TextEdit;
import org.sonatype.plexus.build.incremental.BuildContext;

/**
 * A Maven plugin mojo to format Java source code using the Eclipse code
 * formatter.
 * 
 * Mojo parameters allow customizing formatting by specifying the config XML
 * file, line endings, compiler version, and source code locations. Reformatting
 * source files is avoided using an md5 hash of the content, comparing to the
 * original hash to the hash after formatting and a cached hash.
 * 
 * @goal format
 * @phase process-sources
 * 
 * @author jecki
 * @author Matt Blanchette
 */
public class FormatterMojo extends AbstractMojo {

	/**
	 * BuildContext for incremental build if available (m2e).
	 * @component
	 */
	private BuildContext buildContext;

	private static final String CACHE_PROPERTIES_FILENAME = "maven-java-formatter-cache.properties";
	private static final String[] DEFAULT_INCLUDES = new String[] { "**/*.java" };

	static final String LINE_ENDING_AUTO = "AUTO";
	static final String LINE_ENDING_KEEP = "KEEP";
	static final String LINE_ENDING_LF = "LF";
	static final String LINE_ENDING_CRLF = "CRLF";
	static final String LINE_ENDING_CR = "CR";

	static final String LINE_ENDING_LF_CHAR = "\n";
	static final String LINE_ENDING_CRLF_CHARS = "\r\n";
	static final String LINE_ENDING_CR_CHAR = "\r";

	/**
	 * CodeFormatter is not threadsafe,so we use one per thread.
	 * The ExecutorService is shutdown in the main thread, so it should clean itself.
	 * Ideally it should be a threadPool but this is overkill.
	 */
	public static ThreadLocal<CodeFormatter> threadLocalCodeFormatter = new ThreadLocal<CodeFormatter>();

	/**
	 * ResourceManager for retrieving the configFile resource.
	 * 
	 * @component
	 * @required
	 * @readonly
	 */
	private ResourceManager resourceManager;

	/**
	 * Project's source directory as specified in the POM.
	 * 
	 * @parameter expression="${project.build.sourceDirectory}"
	 * @readonly
	 * @required
	 */
	private File sourceDirectory;

	/**
	 * Project's test source directory as specified in the POM.
	 * 
	 * @parameter expression="${project.build.testSourceDirectory}"
	 * @readonly
	 * @required
	 */
	private File testSourceDirectory;

	/**
	 * Project's target directory as specified in the POM.
	 * 
	 * @parameter expression="${project.build.directory}"
	 * @readonly
	 * @required
	 */
	private File targetDirectory;

	/**
	 * Project's base directory.
	 * 
	 * @parameter expression="${basedir}"
	 * @readonly
	 * @required
	 */
	private File basedir;

	/**
	 * List of fileset patterns for Java source locations to include in formatting.
	 * Patterns are relative to the project source and test source directories.
	 * When not specified, the default include is <code>**&#47;*.java</code>
	 * 
	 * @parameter
	 * @since 0.3
	 */
	private String[] includes;

	/**
	 * List of fileset patterns for Java source locations to exclude from formatting.
	 * Patterns are relative to the project source and test source directories.
	 * When not specified, there is no default exclude.
	 * 
	 * @parameter
	 * @since 0.3
	 */
	private String[] excludes;

	/**
	 * Java compiler source version.
	 * 
	 * @parameter default-value="1.5" expression="${maven.compiler.source}"
	 */
	private String compilerSource;

	/**
	 * Java compiler compliance version.
	 * 
	 * @parameter default-value="1.5" expression="${maven.compiler.source}"
	 */
	private String compilerCompliance;

	/**
	 * Java compiler target version.
	 * 
	 * @parameter default-value="1.5" expression="${maven.compiler.target}"
	 */
	private String compilerTargetPlatform;

	/**
	 * The file encoding used to read and write source files. 
	 * When not specified and sourceEncoding also not set, 
	 * default is platform file encoding.
	 * 
	 * @parameter default-value="${project.build.sourceEncoding}"
	 * @since 0.3
	 */
	private String encoding;

	/**
	 * Sets the line-ending of files after formatting. Valid values are:
	 * <ul>
	 * <li><b>"AUTO"</b> - Use line endings of current system</li>
	 * <li><b>"KEEP"</b> - Preserve line endings of files, default to AUTO if
	 * ambiguous</li>
	 * <li><b>"LF"</b> - Use Unix and Mac style line endings</li>
	 * <li><b>"CRLF"</b> - Use DOS and Windows style line endings</li>
	 * <li><b>"CR"</b> - Use early Mac style line endings</li>
	 * </ul>
	 * 
	 * @parameter default-value="AUTO"
	 * @since 0.2.0
	 */
	private String lineEnding;

	/**
	 * File or classpath location of an Eclipse code formatter configuration xml
	 * file to use in formatting.
	 * 
	 * @parameter
	 */
	private String configFile;

	/**
	 * Used to switch performance profile. If the cached hash file exist, we expect only read of
	 *  the java file else we have to try to format all the file,this is the costly operation.
	 */
	private boolean isCleanBuild;

	/**
	 * Sets whether compilerSource, compilerCompliance, and
	 * compilerTargetPlatform values are used instead of those defined in the
	 * configFile.
	 * 
	 * @parameter default-value="false"
	 * @since 0.2.0
	 */
	private Boolean overrideConfigCompilerVersion;

	/**
	 * @see org.apache.maven.plugin.AbstractMojo#execute()
	 */
	public void execute() throws MojoExecutionException {
		long startClock = System.currentTimeMillis();

		setupEncoding();
		checkLineEnding();

		final ResultCollector rc = new ResultCollector();
		final Properties hashCache = readFileHashCacheFile();

		formatAll(rc, hashCache);

		storeFileHashCache(rc, hashCache);

		long endClock = System.currentTimeMillis();
		getLog().info("Successfully formatted: " + rc.successCount + " file(s)");
		getLog().info("Fail to format        : " + rc.failCount + " file(s)");
		getLog().info("Skipped               : " + rc.skippedCount + " file(s)");
		getLog().debug(
				"Writing in hashCache : " + rc.hashUpdatedCount + " file(s)");
		getLog().info(
				"Approximate time taken(" + this.getBasedirPath() + ": "
						+ ((endClock - startClock)) + " ms");
	}

	private void formatAll(final ResultCollector rc, final Properties hashCache)
			throws MojoExecutionException {
		final ExecutorService service;
		if (isCleanBuild) {
			//This seems to be the best ratio time gained/threads
			//More threads only reduce a little more.
			service = Executors.newFixedThreadPool(2);
		} else {
			service = Executors.newFixedThreadPool(1);
		}

		formatSourceDirectory(service, rc, hashCache, sourceDirectory);
		formatSourceDirectory(service, rc, hashCache, testSourceDirectory);

		service.shutdown();
		try {
			//If we wait 10 minutes,something is really wrong or you have Hundreds of thousands of files.This is bad!
			service.awaitTermination(10, TimeUnit.MINUTES);
		} catch (InterruptedException e) {
		}
	}

	private void formatSourceDirectory(ExecutorService service,
			final ResultCollector rc, final Properties hashCache,
			final File sourceDir) throws MojoExecutionException {
		final String[] fileString = constructFileList(sourceDir);
		for (final String string : fileString) {
			service.execute(new Runnable() {
				public void run() {
					formatFile(string, rc, hashCache, sourceDir);
				}
			});
		}
	}

	/**
	 * Create a list of files to be formatted by this mojo.
	 * This collection uses the includes and excludes to find the source files.
	 */
	private String[] constructFileList(File sourceDirectory)
			throws MojoExecutionException {
		if (sourceDirectory.exists()) {
			org.codehaus.plexus.util.Scanner scanner = buildContext
					.newScanner(sourceDirectory);
			// code below is standard plexus Scanner stuff
			if (includes != null && includes.length > 0) {
				scanner.setIncludes(includes);
			} else {
				scanner.setIncludes(DEFAULT_INCLUDES);
			}
			scanner.setExcludes(excludes);
			scanner.scan();
			String[] includedFiles = scanner.getIncludedFiles();
			return includedFiles;
		}
		return new String[0];
	}

	private void checkLineEnding() throws MojoExecutionException {
		if (!LINE_ENDING_AUTO.equals(lineEnding)
				&& !LINE_ENDING_KEEP.equals(lineEnding)
				&& !LINE_ENDING_LF.equals(lineEnding)
				&& !LINE_ENDING_CRLF.equals(lineEnding)
				&& !LINE_ENDING_CR.equals(lineEnding)) {
			throw new MojoExecutionException(
					"Unknown value for lineEnding parameter");
		}
	}

	private void setupEncoding() throws MojoExecutionException {
		if (StringUtils.isEmpty(encoding)) {
			encoding = ReaderFactory.FILE_ENCODING;
			getLog().warn(
					"File encoding has not been set, using platform encoding ("
							+ encoding
							+ ") to format source files, i.e. build is platform dependent!");
		} else {
			try {
				"Test Encoding".getBytes(encoding);
			} catch (UnsupportedEncodingException e) {
				throw new MojoExecutionException("Encoding '" + encoding
						+ "' is not supported");
			}
			getLog().info(
					"Using '" + encoding + "' encoding to format source files.");
		}
	}

	private String getBasedirPath() {
		try {
			return basedir.getCanonicalPath();
		} catch (Exception e) {
			return "";
		}
	}

	private void storeFileHashCache(ResultCollector rc, Properties props) {
		if (rc.hashUpdatedCount > 0) {
			File cacheFile = new File(targetDirectory,
					CACHE_PROPERTIES_FILENAME);
			OutputStream out = null;
			try {
				out = buildContext.newFileOutputStream(cacheFile);
				props.store(out, null);
				buildContext.setValue(CACHE_PROPERTIES_FILENAME, props);
			} catch (IOException e) {
				getLog().warn("Cannot store file hash cache properties file", e);
				return;
			} finally {
				IOUtil.close(out);
			}
		}
	}

	private Properties readFileHashCacheFile() {
		File cacheFile = new File(targetDirectory, CACHE_PROPERTIES_FILENAME);
		Properties readCachedFiles = null;
		readCachedFiles = (Properties) buildContext
				.getValue(CACHE_PROPERTIES_FILENAME);
		if (readCachedFiles == null || buildContext.hasDelta(cacheFile)) {
			readCachedFiles = new Properties();
			if (!targetDirectory.exists()) {
				targetDirectory.mkdirs();
			} else if (!targetDirectory.isDirectory()) {
				getLog().warn(
						"Something strange here as the "
								+ "supposedly target directory is not a directory.");
				return readCachedFiles;
			}
			if (cacheFile.exists()) {
				readCachedFiles = PropertyUtils.loadProperties(cacheFile);
				isCleanBuild = false;
			} else {
				isCleanBuild = true;
			}
		}
		return readCachedFiles;
	}

	/**
	 * @param file
	 * @param rc
	 * @param hashCache
	 * @param basedirPath
	 */
	private void formatFile(String fileString, ResultCollector rc,
			Properties hashCache, File currentSourceDirectory) {
		File file = new File(currentSourceDirectory, fileString);
		if (!buildContext.isIncremental() || buildContext.hasDelta(file)) {
			try {
				buildContext.removeMessages(file);
				doFormatFile(file, rc, hashCache);
			} catch (Exception e) {
				rc.failCount++;
				buildContext.addMessage(file, 0, 0, e.getMessage(),
						BuildContext.SEVERITY_WARNING, e);
			}
		}
	}

	/**
	 * Format individual file.
	 * 
	 * @param file
	 * @param rc
	 * @param hashCache
	 * @param basedirPath
	 * @throws IOException
	 * @throws BadLocationException
	 */
	private void doFormatFile(File file, ResultCollector rc,
			Properties hashCache) throws IOException, BadLocationException {
		Log log = getLog();
		String code = readFileAsString(file);
		String originalHash = md5hash(code);

		String canonicalPath = file.getCanonicalPath();
		String path = canonicalPath.substring(getBasedirPath().length());
		String cachedHash = hashCache.getProperty(path);
		if (cachedHash != null && cachedHash.equals(originalHash)) {
			rc.skippedCount++;
			log.debug(file.getAbsolutePath() + " is already formatted.");
			return;
		}

		String lineSeparator = getLineEnding(code);

		TextEdit te = null;
		try {
			te = getCodeFormatter().format(
					CodeFormatter.K_COMPILATION_UNIT
							+ CodeFormatter.F_INCLUDE_COMMENTS, code, 0,
					code.length(), 0, lineSeparator);
		} catch (RuntimeException formatFailed) {
			log.debug("Formatting of " + file.getAbsolutePath() + " failed",
					formatFailed);
		} catch (MojoExecutionException e) {
			log.debug("Formatting of " + file.getAbsolutePath() + " failed", e);
		}
		if (te == null) {
			rc.skippedCount++;
			log.debug(file.getAbsolutePath()
					+ " cannot be formatted. Possible cause is unmatched source/target/compliance version.");
			return;
		}

		IDocument doc = new Document(code);
		te.apply(doc);
		String formattedCode = doc.get();
		String formattedHash = md5hash(formattedCode);
		if (cachedHash == null || !cachedHash.equals(formattedHash)) {
			hashCache.setProperty(path, formattedHash);
			rc.hashUpdatedCount++;
			if (log.isDebugEnabled()) {
				log.debug("Adding hash code to cachedHash for path " + path
						+ ":" + formattedHash);
			}
		}
		if (originalHash.equals(formattedHash)) {
			rc.skippedCount++;
			log.debug("Equal hash code for " + path
					+ ". Not writing result to file.");
			return;
		}

		writeStringToFile(formattedCode, file);
		rc.successCount++;
	}

	/**
	 * @param str
	 * @return
	 * @throws UnsupportedEncodingException
	 */
	private String md5hash(String str) throws UnsupportedEncodingException {
		return DigestUtils.md5Hex(str.getBytes(encoding));
	}

	/**
	 * Read the given file and return the content as a string.
	 * 
	 * @param file
	 * @return
	 * @throws java.io.IOException
	 */
	private String readFileAsString(File file) throws IOException {
		FileInputStream input = null;
		try {
			input = new FileInputStream(file);
			return IOUtil.toString(input);
		} finally {
			IOUtil.close(input);
		}
	}

	/**
	 * Write the given string to a file.
	 * 
	 * @param str
	 * @param file
	 * @throws IOException
	 */
	private void writeStringToFile(String str, File file) throws IOException {
		if (!file.exists() && file.isDirectory()) {
			return;
		}
		OutputStream output = null;
		try {
			output = buildContext.newFileOutputStream(file);
			IOUtil.copy(str, output);
		} finally {
			IOUtil.close(output);
		}
	}

	/**
	 * Create a {@link CodeFormatter} instance to be used by this mojo.
	 * 
	 * @throws MojoExecutionException
	 */
	private CodeFormatter createCodeFormatter() throws MojoExecutionException {
		Map<String, String> options = getFormattingOptions();
		CodeFormatter formatter = ToolFactory.createCodeFormatter(options);
		return formatter;
	}

	/**
	 * Create a {@link CodeFormatter} instance to be used by this mojo.
	 * 
	 * @throws MojoExecutionException
	 */
	private CodeFormatter getCodeFormatter() throws MojoExecutionException {
		CodeFormatter codeFormatter = threadLocalCodeFormatter.get();
		if (codeFormatter == null) {
			codeFormatter = createCodeFormatter();
			threadLocalCodeFormatter.set(codeFormatter);
		}
		return codeFormatter;
	}

	/**
	 * Return the options to be passed when creating {@link CodeFormatter}
	 * instance.
	 * 
	 * @return
	 * @throws MojoExecutionException
	 */
	private Map<String, String> getFormattingOptions()
			throws MojoExecutionException {
		Map<String, String> config = null;
		if (configFile != null) {
			config = getOptionsFromXMLConfigFile();
		} else {
			File eclipseConfigFile = new File(basedir.getAbsolutePath(),
					".settings/org.eclipse.jdt.core.prefs");
			if (eclipseConfigFile.exists()) {
				config = new PropertiesConfigReader().read(eclipseConfigFile);
			}
		}
		if (config == null) {
			//otherwise, default eclipse formatter options
			config = new HashMap<String, String>();
		}
		if (overrideConfigCompilerVersion) {
			config.put(JavaCore.COMPILER_SOURCE, compilerSource);
			config.put(JavaCore.COMPILER_COMPLIANCE, compilerCompliance);
			config.put(JavaCore.COMPILER_CODEGEN_TARGET_PLATFORM,
					compilerTargetPlatform);
		}
		return config;
	}

	/**
	 * Read config file and return the config as {@link Map}.
	 * 
	 * @return
	 * @throws MojoExecutionException
	 */
	private Map<String, String> getOptionsFromXMLConfigFile()
			throws MojoExecutionException {
		InputStream configInput = null;
		ConfigReader configReader = null;
		if (configFile != null) {
			try {
				resourceManager.addSearchPath(FileResourceLoader.ID,
						basedir.getAbsolutePath());
				configInput = resourceManager
						.getResourceAsInputStream(configFile);
				configReader = new XmlConfigReader();
			} catch (ResourceNotFoundException e) {
				throw new MojoExecutionException("Config file [" + configFile
						+ "] cannot be found", e);
			}
		}
		if (configInput == null) {
			throw new MojoExecutionException("Config file [" + configFile
					+ "] does not exist");
		}
		try {
			return configReader.read(configInput);
		} catch (IOException e) {
			throw new MojoExecutionException("Cannot read config file ["
					+ configFile + "]", e);
		} catch (ConfigReadException e) {
			throw new MojoExecutionException(e.getMessage(), e);
		} finally {
			IOUtil.close(configInput);
		}
	}

	/**
	 * Returns the lineEnding parameter as characters when the value is known
	 * (LF, CRLF, CR) or can be determined from the file text (KEEP). Otherwise
	 * null is returned.
	 * 
	 * @return
	 */
	String getLineEnding(String fileDataString) {
		String lineEnd = null;
		if (LINE_ENDING_KEEP.equals(lineEnding)) {
			lineEnd = determineLineEnding(fileDataString);
		} else if (LINE_ENDING_LF.equals(lineEnding)) {
			lineEnd = LINE_ENDING_LF_CHAR;
		} else if (LINE_ENDING_CRLF.equals(lineEnding)) {
			lineEnd = LINE_ENDING_CRLF_CHARS;
		} else if (LINE_ENDING_CR.equals(lineEnding)) {
			lineEnd = LINE_ENDING_CR_CHAR;
		}
		return lineEnd;
	}

	/**
	 * Returns the most occurring line-ending characters in the file text or
	 * null if no line-ending occurs the most.
	 * 
	 * @return
	 */
	String determineLineEnding(String fileDataString) {
		int lfCount = 0;
		int crCount = 0;
		int crlfCount = 0;

		for (int i = 0; i < fileDataString.length(); i++) {
			char c = fileDataString.charAt(i);
			if (c == '\r') {
				if ((i + 1) < fileDataString.length()
						&& fileDataString.charAt(i + 1) == '\n') {
					crlfCount++;
					i++;
				} else {
					crCount++;
				}
			} else if (c == '\n') {
				lfCount++;
			}
		}
		if (lfCount > crCount && lfCount > crlfCount) {
			return LINE_ENDING_LF_CHAR;
		} else if (crlfCount > lfCount && crlfCount > crCount) {
			return LINE_ENDING_CRLF_CHARS;
		} else if (crCount > lfCount && crCount > crlfCount) {
			return LINE_ENDING_CR_CHAR;
		}
		return null;
	}

	private class ResultCollector {
		public int hashUpdatedCount;
		private int successCount;
		private int failCount;
		private int skippedCount;
	}
}
