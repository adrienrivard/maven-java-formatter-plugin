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
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Properties;

import org.apache.maven.plugin.MojoExecutionException;
import org.codehaus.plexus.util.IOUtil;
import org.xml.sax.SAXException;

/**
 * This class reads a config file for Eclipse code formatter.
 * 
 * @author Adrien Rivard
 */
public class PropertiesConfigReader implements ConfigReader {

	public Map<String, String> read(File input) throws MojoExecutionException {
		FileInputStream configInput = null;
		try {
			configInput = new FileInputStream(input);
			return read(configInput);
		} catch (IOException e) {
			throw new MojoExecutionException("Cannot read config file ["
					+ input + "]", e);
		} catch (ConfigReadException e) {
			throw new MojoExecutionException(e.getMessage(), e);
		} finally {
			IOUtil.close(configInput);
		}
	}

	/**
	 * Read from the <code>input</code> and return it's configuration settings
	 * as a {@link Map}.
	 * 
	 * @param input
	 * @return return {@link Map} with all the configurations read from the
	 *         config file, or throws an exception if there's a problem reading
	 *         the input, e.g.: invalid XML.
	 * @throws SAXException
	 * @throws IOException
	 * @throws ConfigReadException
	 */
	public Map<String, String> read(InputStream input) throws IOException,
			ConfigReadException {
		Properties p = new Properties();
		p.load(input);
		Map<String, String> map = new HashMap<String, String>(p.size());
		for (Entry<Object, Object> entry : p.entrySet()) {
			map.put((String) entry.getKey(), (String) entry.getValue());
		}
		return map;
	}
}
