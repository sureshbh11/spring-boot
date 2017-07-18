/*
 * Copyright 2012-2017 the original author or authors.
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

package org.springframework.boot.loader.tools;

import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Set;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;

import org.apache.commons.compress.archivers.jar.JarArchiveEntry;
import org.apache.commons.compress.archivers.jar.JarArchiveOutputStream;
import org.apache.commons.compress.archivers.zip.UnixStat;

/**
 * Writes JAR content, ensuring valid directory entries are always create and duplicate
 * items are ignored.
 *
 * @author Phillip Webb
 * @author Andy Wilkinson
 */
public class JarWriter implements LoaderClassesWriter, AutoCloseable {

	private static final String NESTED_LOADER_JAR = "META-INF/loader/spring-boot-loader.jar";

	private static final int BUFFER_SIZE = 32 * 1024;

	private final JarArchiveOutputStream jarOutput;

	private final Set<String> writtenEntries = new HashSet<>();

	/**
	 * Create a new {@link JarWriter} instance.
	 * @param file the file to write
	 * @throws IOException if the file cannot be opened
	 * @throws FileNotFoundException if the file cannot be found
	 */
	public JarWriter(File file) throws FileNotFoundException, IOException {
		this(file, null);
	}

	/**
	 * Create a new {@link JarWriter} instance.
	 * @param file the file to write
	 * @param launchScript an optional launch script to prepend to the front of the jar
	 * @throws IOException if the file cannot be opened
	 * @throws FileNotFoundException if the file cannot be found
	 */
	public JarWriter(File file, LaunchScript launchScript)
			throws FileNotFoundException, IOException {
		FileOutputStream fileOutputStream = new FileOutputStream(file);
		if (launchScript != null) {
			fileOutputStream.write(launchScript.toByteArray());
			setExecutableFilePermission(file);
		}
		this.jarOutput = new JarArchiveOutputStream(fileOutputStream);
		this.jarOutput.setEncoding("UTF-8");
	}

	private void setExecutableFilePermission(File file) {
		try {
			Path path = file.toPath();
			Set<PosixFilePermission> permissions = new HashSet<>(
					Files.getPosixFilePermissions(path));
			permissions.add(PosixFilePermission.OWNER_EXECUTE);
			Files.setPosixFilePermissions(path, permissions);
		}
		catch (Throwable ex) {
			// Ignore and continue creating the jar
		}
	}

	/**
	 * Write the specified manifest.
	 * @param manifest the manifest to write
	 * @throws IOException of the manifest cannot be written
	 */
	public void writeManifest(final Manifest manifest) throws IOException {
		JarArchiveEntry entry = new JarArchiveEntry("META-INF/MANIFEST.MF");
		writeEntry(entry, new EntryWriter() {
			@Override
			public void write(OutputStream outputStream) throws IOException {
				manifest.write(outputStream);
			}
		});
	}

	/**
	 * Write all entries from the specified jar file.
	 * @param jarFile the source jar file
	 * @throws IOException if the entries cannot be written
	 */
	public void writeEntries(JarFile jarFile) throws IOException {
		this.writeEntries(jarFile, new IdentityEntryTransformer());
	}

	void writeEntries(JarFile jarFile, EntryTransformer entryTransformer)
			throws IOException {
		Enumeration<JarEntry> entries = jarFile.entries();
		while (entries.hasMoreElements()) {
			JarArchiveEntry entry = new JarArchiveEntry(entries.nextElement());
			setUpStoredEntryIfNecessary(jarFile, entry);
			try (ZipHeaderPeekInputStream inputStream = new ZipHeaderPeekInputStream(
					jarFile.getInputStream(entry))) {
				EntryWriter entryWriter = new InputStreamEntryWriter(inputStream, true);
				JarArchiveEntry transformedEntry = entryTransformer.transform(entry);
				if (transformedEntry != null) {
					writeEntry(transformedEntry, entryWriter);
				}
			}
		}
	}

	private void setUpStoredEntryIfNecessary(JarFile jarFile, JarArchiveEntry entry)
			throws IOException {
		try (ZipHeaderPeekInputStream inputStream = new ZipHeaderPeekInputStream(
				jarFile.getInputStream(entry))) {
			if (inputStream.hasZipHeader() && entry.getMethod() != ZipEntry.STORED) {
				new CrcAndSize(inputStream).setupStoredEntry(entry);
			}
		}
	}

	/**
	 * Writes an entry. The {@code inputStream} is closed once the entry has been written
	 * @param entryName The name of the entry
	 * @param inputStream The stream from which the entry's data can be read
	 * @throws IOException if the write fails
	 */
	@Override
	public void writeEntry(String entryName, InputStream inputStream) throws IOException {
		JarArchiveEntry entry = new JarArchiveEntry(entryName);
		writeEntry(entry, new InputStreamEntryWriter(inputStream, true));
	}

	/**
	 * Write a nested library.
	 * @param destination the destination of the library
	 * @param library the library
	 * @throws IOException if the write fails
	 */
	public void writeNestedLibrary(String destination, Library library)
			throws IOException {
		File file = library.getFile();
		JarArchiveEntry entry = new JarArchiveEntry(destination + library.getName());
		entry.setTime(getNestedLibraryTime(file));
		if (library.isUnpackRequired()) {
			entry.setComment("UNPACK:" + FileUtils.sha1Hash(file));
		}
		new CrcAndSize(file).setupStoredEntry(entry);
		writeEntry(entry, new InputStreamEntryWriter(new FileInputStream(file), true));
	}

	private long getNestedLibraryTime(File file) {
		try {
			try (JarFile jarFile = new JarFile(file)) {
				Enumeration<JarEntry> entries = jarFile.entries();
				while (entries.hasMoreElements()) {
					JarEntry entry = entries.nextElement();
					if (!entry.isDirectory()) {
						return entry.getTime();
					}
				}
			}
		}
		catch (Exception ex) {
			// Ignore and just use the source file timestamp
		}
		return file.lastModified();
	}

	/**
	 * Write the required spring-boot-loader classes to the JAR.
	 * @throws IOException if the classes cannot be written
	 */
	@Override
	public void writeLoaderClasses() throws IOException {
		writeLoaderClasses(NESTED_LOADER_JAR);
	}

	/**
	 * Write the required spring-boot-loader classes to the JAR.
	 * @param loaderJarResourceName the name of the resource containing the loader classes
	 * to be written
	 * @throws IOException if the classes cannot be written
	 */
	@Override
	public void writeLoaderClasses(String loaderJarResourceName) throws IOException {
		URL loaderJar = getClass().getClassLoader().getResource(loaderJarResourceName);
		JarInputStream inputStream = new JarInputStream(
				new BufferedInputStream(loaderJar.openStream()));
		JarEntry entry;
		while ((entry = inputStream.getNextJarEntry()) != null) {
			if (entry.getName().endsWith(".class")) {
				writeEntry(new JarArchiveEntry(entry),
						new InputStreamEntryWriter(inputStream, false));
			}
		}
		inputStream.close();
	}

	/**
	 * Close the writer.
	 * @throws IOException if the file cannot be closed
	 */
	@Override
	public void close() throws IOException {
		this.jarOutput.close();
	}

	/**
	 * Perform the actual write of a {@link JarEntry}. All other {@code write} method
	 * delegate to this one.
	 * @param entry the entry to write
	 * @param entryWriter the entry writer or {@code null} if there is no content
	 * @throws IOException in case of I/O errors
	 */
	private void writeEntry(JarArchiveEntry entry, EntryWriter entryWriter)
			throws IOException {
		String parent = entry.getName();
		if (parent.endsWith("/")) {
			parent = parent.substring(0, parent.length() - 1);
			entry.setUnixMode(UnixStat.DIR_FLAG | UnixStat.DEFAULT_DIR_PERM);
		}
		else {
			entry.setUnixMode(UnixStat.FILE_FLAG | UnixStat.DEFAULT_FILE_PERM);
		}
		if (parent.lastIndexOf("/") != -1) {
			parent = parent.substring(0, parent.lastIndexOf("/") + 1);
			if (parent.length() > 0) {
				writeEntry(new JarArchiveEntry(parent), null);
			}
		}

		if (this.writtenEntries.add(entry.getName())) {
			this.jarOutput.putArchiveEntry(entry);
			if (entryWriter != null) {
				entryWriter.write(this.jarOutput);
			}
			this.jarOutput.closeArchiveEntry();
		}
	}

	/**
	 * Interface used to write jar entry date.
	 */
	private interface EntryWriter {

		/**
		 * Write entry data to the specified output stream.
		 * @param outputStream the destination for the data
		 * @throws IOException in case of I/O errors
		 */
		void write(OutputStream outputStream) throws IOException;

	}

	/**
	 * {@link EntryWriter} that writes content from an {@link InputStream}.
	 */
	private static class InputStreamEntryWriter implements EntryWriter {

		private final InputStream inputStream;

		private final boolean close;

		InputStreamEntryWriter(InputStream inputStream, boolean close) {
			this.inputStream = inputStream;
			this.close = close;
		}

		@Override
		public void write(OutputStream outputStream) throws IOException {
			byte[] buffer = new byte[BUFFER_SIZE];
			int bytesRead = -1;
			while ((bytesRead = this.inputStream.read(buffer)) != -1) {
				outputStream.write(buffer, 0, bytesRead);
			}
			outputStream.flush();
			if (this.close) {
				this.inputStream.close();
			}
		}

	}

	/**
	 * {@link InputStream} that can peek ahead at zip header bytes.
	 */
	private static class ZipHeaderPeekInputStream extends FilterInputStream {

		private static final byte[] ZIP_HEADER = new byte[] { 0x50, 0x4b, 0x03, 0x04 };

		private final byte[] header;

		private ByteArrayInputStream headerStream;

		protected ZipHeaderPeekInputStream(InputStream in) throws IOException {
			super(in);
			this.header = new byte[4];
			int len = in.read(this.header);
			this.headerStream = new ByteArrayInputStream(this.header, 0, len);
		}

		@Override
		public int read() throws IOException {
			int read = (this.headerStream == null ? -1 : this.headerStream.read());
			if (read != -1) {
				this.headerStream = null;
				return read;
			}
			return super.read();
		}

		@Override
		public int read(byte[] b) throws IOException {
			return read(b, 0, b.length);
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			int read = (this.headerStream == null ? -1
					: this.headerStream.read(b, off, len));
			if (read != -1) {
				this.headerStream = null;
				return read;
			}
			return super.read(b, off, len);
		}

		public boolean hasZipHeader() {
			return Arrays.equals(this.header, ZIP_HEADER);
		}

	}

	/**
	 * Data holder for CRC and Size.
	 */
	private static class CrcAndSize {

		private final CRC32 crc = new CRC32();

		private long size;

		CrcAndSize(File file) throws IOException {
			try (FileInputStream inputStream = new FileInputStream(file)) {
				load(inputStream);
			}
		}

		CrcAndSize(InputStream inputStream) throws IOException {
			load(inputStream);
		}

		private void load(InputStream inputStream) throws IOException {
			byte[] buffer = new byte[BUFFER_SIZE];
			int bytesRead = -1;
			while ((bytesRead = inputStream.read(buffer)) != -1) {
				this.crc.update(buffer, 0, bytesRead);
				this.size += bytesRead;
			}
		}

		public void setupStoredEntry(JarArchiveEntry entry) {
			entry.setSize(this.size);
			entry.setCompressedSize(this.size);
			entry.setCrc(this.crc.getValue());
			entry.setMethod(ZipEntry.STORED);
		}

	}

	/**
	 * An {@code EntryTransformer} enables the transformation of {@link JarEntry jar
	 * entries} during the writing process.
	 */
	interface EntryTransformer {

		JarArchiveEntry transform(JarArchiveEntry jarEntry);

	}

	/**
	 * An {@code EntryTransformer} that returns the entry unchanged.
	 */
	private static final class IdentityEntryTransformer implements EntryTransformer {

		@Override
		public JarArchiveEntry transform(JarArchiveEntry jarEntry) {
			return jarEntry;
		}

	}

}
