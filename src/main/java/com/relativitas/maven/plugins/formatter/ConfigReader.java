package com.relativitas.maven.plugins.formatter;

import java.io.IOException;
import java.io.InputStream;
import java.util.Map;

public interface ConfigReader {
	/**
	 * Read from the <code>input</code> and return it's configuration settings
	 * as a {@link Map}.
	 * 
	 * @param input
	 * @return return {@link Map} with all the configurations read from the
	 *         config file, or throws an exception if there's a problem reading
	 * @throws IOException
	 * @throws ConfigReadException
	 */
	Map<String, String> read(InputStream input) throws IOException,
			 ConfigReadException;
}
