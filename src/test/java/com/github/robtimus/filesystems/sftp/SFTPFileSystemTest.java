/*
 * SFTPFileSystemTest.java
 * Copyright 2016 Rob Spoor
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.github.robtimus.filesystems.sftp;

import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.instanceOf;
import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.AccessDeniedException;
import java.nio.file.AccessMode;
import java.nio.file.CopyOption;
import java.nio.file.DirectoryNotEmptyException;
import java.nio.file.DirectoryStream;
import java.nio.file.DirectoryStream.Filter;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.nio.file.attribute.PosixFileAttributes;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import com.github.robtimus.filesystems.Messages;
import com.github.robtimus.filesystems.attribute.SimpleGroupPrincipal;
import com.github.robtimus.filesystems.attribute.SimpleUserPrincipal;
import com.jcraft.jsch.SftpException;

@SuppressWarnings({ "nls", "javadoc" })
public class SFTPFileSystemTest extends AbstractSFTPFileSystemTest {

    // SFTPFileSystem.getPath

    @Test
    public void testGetPath() {
        testGetPath("/", "/");
        testGetPath("/foo/bar", "/", "/foo", "/bar");
        testGetPath("/foo/../bar", "/foo/", "../bar");
    }

    private void testGetPath(String path, String first, String... more) {
        SFTPPath expected = createPath(path);
        Path actual = fileSystem.getPath(first, more);
        assertEquals(expected, actual);
    }

    // SFTPFileSystem.keepAlive

    @Test
    public void testKeepAlive() {
        assertDoesNotThrow(fileSystem::keepAlive);
    }

    // SFTPFileSystem.toUri

    @Test
    public void testToUri() {
        final String prefix = getBaseUrl();

        testToUri("/", prefix + "/");
        testToUri("/foo/bar", prefix + "/foo/bar");
        testToUri("/foo/../bar", prefix + "/bar");

        testToUri("", prefix + "/home");
        testToUri("foo/bar", prefix + "/home/foo/bar");
        testToUri("foo/../bar", prefix + "/home/bar");
    }

    private void testToUri(String path, String expected) {
        URI expectedUri = URI.create(expected);
        URI actual = fileSystem.toUri(createPath(path));
        assertEquals(expectedUri, actual);
    }

    // SFTPFileSystem.toAbsolutePath

    @Test
    public void testToAbsolutePath() {

        testToAbsolutePath("/", "/");
        testToAbsolutePath("/foo/bar", "/foo/bar");
        testToAbsolutePath("/foo/../bar", "/foo/../bar");

        testToAbsolutePath("", "/home");
        testToAbsolutePath("foo/bar", "/home/foo/bar");
        testToAbsolutePath("foo/../bar", "/home/foo/../bar");
    }

    private void testToAbsolutePath(String path, String expected) {
        SFTPPath expectedPath = createPath(expected);
        Path actual = fileSystem.toAbsolutePath(createPath(path));
        assertEquals(expectedPath, actual);
    }

    // SFTPFileSystem.toRealPath

    @Test
    public void testToRealPathNoFollowLinks() throws IOException {
        Path foo = addDirectory("/foo");
        addDirectory("/foo/bar");
        addDirectory("/bar");
        addFile("/home/foo/bar");
        Path bar = addFile("/home/bar");

        // symbolic links
        Path symLink = addSymLink("/hello", foo);
        addSymLink("/world", symLink);
        symLink = addSymLink("/home/baz", bar);
        addSymLink("/baz", symLink);

        testToRealPathNoFollowLinks("/", "/");
        testToRealPathNoFollowLinks("/foo/bar", "/foo/bar");
        testToRealPathNoFollowLinks("/foo/../bar", "/bar");

        testToRealPathNoFollowLinks("", "/home");
        testToRealPathNoFollowLinks("foo/bar", "/home/foo/bar");
        testToRealPathNoFollowLinks("foo/../bar", "/home/bar");

        // symbolic links
        testToRealPathNoFollowLinks("/hello", "/hello");
        testToRealPathNoFollowLinks("/world", "/world");
        testToRealPathNoFollowLinks("/home/baz", "/home/baz");
        testToRealPathNoFollowLinks("/baz", "/baz");
    }

    private void testToRealPathNoFollowLinks(String path, String expected) throws IOException {
        SFTPPath expectedPath = createPath(expected);
        Path actual = fileSystem.toRealPath(createPath(path), LinkOption.NOFOLLOW_LINKS);
        assertEquals(expectedPath, actual);
    }

    @Test
    public void testToRealPathFollowLinks() throws IOException {
        Path foo = addDirectory("/foo");
        addDirectory("/foo/bar");
        addDirectory("/bar");
        addFile("/home/foo/bar");
        Path bar = addFile("/home/bar");

        // symbolic links
        Path symLink = addSymLink("/hello", foo);
        addSymLink("/world", symLink);
        symLink = addSymLink("/home/baz", bar);
        addSymLink("/baz", symLink);

        testToRealPathFollowLinks("/", "/");
        testToRealPathFollowLinks("/foo/bar", "/foo/bar");
        testToRealPathFollowLinks("/foo/../bar", "/bar");

        testToRealPathFollowLinks("", "/home");
        testToRealPathFollowLinks("foo/bar", "/home/foo/bar");
        testToRealPathFollowLinks("foo/../bar", "/home/bar");

        // symbolic links
        testToRealPathFollowLinks("/hello", "/foo");
        testToRealPathFollowLinks("/world", "/foo");
        testToRealPathFollowLinks("/home/baz", "/home/bar");
        testToRealPathFollowLinks("/baz", "/home/bar");
    }

    private void testToRealPathFollowLinks(String path, String expected) throws IOException {
        SFTPPath expectedPath = createPath(expected);
        Path actual = fileSystem.toRealPath(createPath(path));
        assertEquals(expectedPath, actual);
    }

    @Test
    public void testToRealPathBrokenLink() throws IOException {
        addSymLink("/foo", getPath("/bar"));

        NoSuchFileException exception = assertThrows(NoSuchFileException.class, () -> createPath("/foo").toRealPath());
        assertEquals("/bar", exception.getFile());
    }

    @Test
    public void testToRealPathNotExisting() {
        NoSuchFileException exception = assertThrows(NoSuchFileException.class, () -> createPath("/foo").toRealPath());
        assertEquals("/foo", exception.getFile());
    }

    // SFTPFileSystem.newInputStream

    @Test
    public void testNewInputStream() throws IOException {
        addFile("/foo/bar");

        try (InputStream input = fileSystem.newInputStream(createPath("/foo/bar"))) {
            // don't do anything with the stream, there's a separate test for that
        }
        // verify that the file system can be used after closing the stream
        fileSystem.checkAccess(createPath("/foo/bar"));
    }

    @Test
    public void testNewInputStreamDeleteOnClose() throws IOException {
        addFile("/foo/bar");

        OpenOption[] options = { StandardOpenOption.DELETE_ON_CLOSE };
        try (InputStream input = fileSystem.newInputStream(createPath("/foo/bar"), options)) {
            // don't do anything with the stream, there's a separate test for that
        }
        assertFalse(Files.exists(getPath("/foo/bar")));
        assertEquals(0, getChildCount("/foo"));
    }

    @Test
    public void testNewInputStreamSFTPFailure() {

        // failure: file not found

        NoSuchFileException exception = assertThrows(NoSuchFileException.class, () -> fileSystem.newInputStream(createPath("/foo/bar")));
        assertEquals("/foo/bar", exception.getFile());

        verify(getExceptionFactory()).createNewInputStreamException(eq("/foo/bar"), any(SftpException.class));
    }

    // SFTPFileSystem.newOutputStream

    @Test
    public void testNewOutputStreamExisting() throws IOException {
        addDirectory("/foo");
        addFile("/foo/bar");

        OpenOption[] options = { StandardOpenOption.WRITE };
        try (OutputStream output = fileSystem.newOutputStream(createPath("/foo/bar"), options)) {
            // don't do anything with the stream, there's a separate test for that
        }
        // verify that the file system can be used after closing the stream
        fileSystem.checkAccess(createPath("/foo/bar"));

        assertTrue(Files.isDirectory(getPath("/foo")));
        assertTrue(Files.isRegularFile(getPath("/foo/bar")));
    }

    @Test
    public void testNewOutputStreamExistingDeleteOnClose() throws IOException {
        addDirectory("/foo");
        addFile("/foo/bar");

        OpenOption[] options = { StandardOpenOption.DELETE_ON_CLOSE };
        try (OutputStream output = fileSystem.newOutputStream(createPath("/foo/bar"), options)) {
            // don't do anything with the stream, there's a separate test for that
            assertTrue(Files.isRegularFile(getPath("/foo/bar")));
        }
        // verify that the file system can be used after closing the stream
        fileSystem.checkAccess(createPath("/foo"));

        assertTrue(Files.isDirectory(getPath("/foo")));
        assertFalse(Files.exists(getPath("/foo/bar")));
        assertEquals(0, getChildCount("/foo"));
    }

    @Test
    public void testNewOutputStreamExistingCreate() throws IOException {
        addDirectory("/foo");
        addFile("/foo/bar");

        OpenOption[] options = { StandardOpenOption.CREATE };
        try (OutputStream output = fileSystem.newOutputStream(createPath("/foo/bar"), options)) {
            // don't do anything with the stream, there's a separate test for that
        }
        // verify that the file system can be used after closing the stream
        fileSystem.checkAccess(createPath("/foo/bar"));

        assertTrue(Files.isDirectory(getPath("/foo")));
        assertTrue(Files.isRegularFile(getPath("/foo/bar")));
    }

    @Test
    public void testNewOutputStreamExistingCreateDeleteOnClose() throws IOException {
        addDirectory("/foo");
        addFile("/foo/bar");

        OpenOption[] options = { StandardOpenOption.CREATE, StandardOpenOption.DELETE_ON_CLOSE };
        try (OutputStream output = fileSystem.newOutputStream(createPath("/foo/bar"), options)) {
            // don't do anything with the stream, there's a separate test for that
            assertTrue(Files.isRegularFile(getPath("/foo/bar")));
        }
        // verify that the file system can be used after closing the stream
        fileSystem.checkAccess(createPath("/foo"));

        assertTrue(Files.isDirectory(getPath("/foo")));
        assertFalse(Files.exists(getPath("/foo/bar")));
    }

    @Test
    public void testNewOutputStreamExistingCreateNew() throws IOException {
        addDirectory("/foo");
        addFile("/foo/bar");

        OpenOption[] options = { StandardOpenOption.CREATE_NEW };
        FileAlreadyExistsException exception = assertThrows(FileAlreadyExistsException.class,
                () -> fileSystem.newOutputStream(createPath("/foo/bar"), options));
        assertEquals("/foo/bar", exception.getFile());

        // verify that the file system can be used after closing the stream
        assertDoesNotThrow(() -> fileSystem.checkAccess(createPath("/foo/bar")));
        assertTrue(Files.isDirectory(getPath("/foo")));
        assertTrue(Files.isRegularFile(getPath("/foo/bar")));
    }

    @Test
    public void testNewOutputStreamExistingSFTPFailure() throws IOException {
        addDirectory("/foo");
        Path bar = addFile("/foo/bar");
        bar.toFile().setReadOnly();

        // failure: no permission to write

        OpenOption[] options = { StandardOpenOption.WRITE };
        FileSystemException exception = assertThrows(FileSystemException.class, () -> fileSystem.newOutputStream(createPath("/foo/bar"), options));
        assertEquals("/foo/bar", exception.getFile());

        verify(getExceptionFactory()).createNewOutputStreamException(eq("/foo/bar"), any(SftpException.class), anyCollection());
        assertTrue(Files.isDirectory(getPath("/foo")));
        assertTrue(Files.isRegularFile(getPath("/foo/bar")));
    }

    @Test
    public void testNewOutputStreamExistingSFTPFailureDeleteOnClose() throws IOException {
        addDirectory("/foo");
        Path bar = addFile("/foo/bar");
        bar.toFile().setReadOnly();

        // failure: no permission to write

        OpenOption[] options = { StandardOpenOption.DELETE_ON_CLOSE };
        FileSystemException exception = assertThrows(FileSystemException.class, () -> fileSystem.newOutputStream(createPath("/foo/bar"), options));
        assertEquals("/foo/bar", exception.getFile());

        verify(getExceptionFactory()).createNewOutputStreamException(eq("/foo/bar"), any(SftpException.class), anyCollection());
        assertTrue(Files.isDirectory(getPath("/foo")));
        assertTrue(Files.isRegularFile(getPath("/foo/bar")));
    }

    @Test
    public void testNewOutputStreamNonExistingNoCreate() throws IOException {
        addDirectory("/foo");

        OpenOption[] options = { StandardOpenOption.WRITE };
        NoSuchFileException exception = assertThrows(NoSuchFileException.class, () -> fileSystem.newOutputStream(createPath("/foo/bar"), options));
        assertEquals("/foo/bar", exception.getFile());

        assertTrue(Files.isDirectory(getPath("/foo")));
        assertFalse(Files.exists(getPath("/foo/bar")));
    }

    @Test
    public void testNewOutputStreamNonExistingCreate() throws IOException {
        addDirectory("/foo");

        OpenOption[] options = { StandardOpenOption.CREATE };
        try (OutputStream input = fileSystem.newOutputStream(createPath("/foo/bar"), options)) {
            // don't do anything with the stream, there's a separate test for that
        } finally {
            assertTrue(Files.isDirectory(getPath("/foo")));
            assertTrue(Files.isRegularFile(getPath("/foo/bar")));
        }
    }

    @Test
    public void testNewOutputStreamNonExistingCreateDeleteOnClose() throws IOException {
        addDirectory("/foo");

        OpenOption[] options = { StandardOpenOption.CREATE, StandardOpenOption.DELETE_ON_CLOSE };
        try (OutputStream input = fileSystem.newOutputStream(createPath("/foo/bar"), options)) {
            // don't do anything with the stream, there's a separate test for that
            // we can't check here that /foo/bar exists, because it will only be stored in the file system once the stream is closed
        } finally {
            assertTrue(Files.isDirectory(getPath("/foo")));
            assertFalse(Files.exists(getPath("/foo/bar")));
            assertEquals(0, getChildCount("/foo"));
        }
    }

    @Test
    public void testNewOutputStreamNonExistingCreateNew() throws IOException {
        addDirectory("/foo");

        OpenOption[] options = { StandardOpenOption.CREATE_NEW };
        try (OutputStream input = fileSystem.newOutputStream(createPath("/foo/bar"), options)) {
            // don't do anything with the stream, there's a separate test for that
        } finally {
            assertTrue(Files.isDirectory(getPath("/foo")));
            assertTrue(Files.isRegularFile(getPath("/foo/bar")));
        }
    }

    @Test
    public void testNewOutputStreamNonExistingCreateNewDeleteOnClose() throws IOException {
        addDirectory("/foo");

        OpenOption[] options = { StandardOpenOption.CREATE_NEW, StandardOpenOption.DELETE_ON_CLOSE };
        try (OutputStream input = fileSystem.newOutputStream(createPath("/foo/bar"), options)) {
            // don't do anything with the stream, there's a separate test for that
            // we can't check here that /foo/bar exists, because it will only be stored in the file system once the stream is closed
        } finally {
            assertTrue(Files.isDirectory(getPath("/foo")));
            assertFalse(Files.exists(getPath("/foo/bar")));
        }
    }

    @Test
    public void testNewOutputStreamDirectoryNoCreate() throws IOException {
        addDirectory("/foo");

        OpenOption[] options = { StandardOpenOption.WRITE };
        FileSystemException exception = assertThrows(FileSystemException.class, () -> fileSystem.newOutputStream(createPath("/foo"), options));
        assertEquals("/foo", exception.getFile());
        assertEquals(Messages.fileSystemProvider().isDirectory("/foo").getReason(), exception.getReason());

        verify(getExceptionFactory(), never()).createNewOutputStreamException(anyString(), any(SftpException.class), anyCollection());
        assertTrue(Files.isDirectory(getPath("/foo")));
        assertEquals(0, getChildCount("/foo"));
    }

    @Test
    public void testNewOutputStreamDirectoryDeleteOnClose() throws IOException {
        addDirectory("/foo");

        OpenOption[] options = { StandardOpenOption.DELETE_ON_CLOSE };
        FileSystemException exception = assertThrows(FileSystemException.class, () -> fileSystem.newOutputStream(createPath("/foo"), options));
        assertEquals("/foo", exception.getFile());
        assertEquals(Messages.fileSystemProvider().isDirectory("/foo").getReason(), exception.getReason());

        verify(getExceptionFactory(), never()).createNewOutputStreamException(anyString(), any(SftpException.class), anyCollection());
        assertTrue(Files.isDirectory(getPath("/foo")));
        assertEquals(0, getChildCount("/foo"));
    }

    // SFTPFileSystem.newByteChannel

    @Test
    public void testNewByteChannelRead() throws IOException {
        addDirectory("/foo");
        Path bar = addFile("/foo/bar");
        setContents(bar, new byte[1024]);

        Set<? extends OpenOption> options = EnumSet.noneOf(StandardOpenOption.class);
        try (SeekableByteChannel channel = fileSystem.newByteChannel(createPath("/foo/bar"), options)) {
            // don't do anything with the channel, there's a separate test for that
            assertEquals(Files.size(bar), channel.size());
        }
        assertTrue(Files.isDirectory(getPath("/foo")));
        assertTrue(Files.isRegularFile(getPath("/foo/bar")));
    }

    @Test
    public void testNewByteChannelReadNonExisting() {

        // failure: file does not exist

        Set<? extends OpenOption> options = EnumSet.noneOf(StandardOpenOption.class);
        NoSuchFileException exception = assertThrows(NoSuchFileException.class, () -> fileSystem.newByteChannel(createPath("/foo/bar"), options));
        assertEquals("/foo/bar", exception.getFile());

        verify(getExceptionFactory()).createNewInputStreamException(eq("/foo/bar"), any(SftpException.class));
        assertFalse(Files.exists(getPath("/foo")));
        assertFalse(Files.exists(getPath("/foo/bar")));
    }

    @Test
    public void testNewByteChannelWrite() throws IOException {
        addFile("/foo/bar");

        Set<? extends OpenOption> options = EnumSet.of(StandardOpenOption.WRITE);
        try (SeekableByteChannel channel = fileSystem.newByteChannel(createPath("/foo/bar"), options)) {
            // don't do anything with the channel, there's a separate test for that
            assertEquals(0, channel.size());
        }
    }

    @Test
    public void testNewByteChannelWriteAppend() throws IOException {
        Path bar = addFile("/foo/bar");
        setContents(bar, new byte[1024]);

        Set<? extends OpenOption> options = EnumSet.of(StandardOpenOption.WRITE, StandardOpenOption.APPEND);
        try (SeekableByteChannel channel = fileSystem.newByteChannel(createPath("/foo/bar"), options)) {
            // don't do anything with the channel, there's a separate test for that
            assertEquals(Files.size(bar), channel.size());
        }
    }

    // SFTPFileSystem.newDirectoryStream

    @Test
    public void testNewDirectoryStream() throws IOException {

        try (DirectoryStream<Path> stream = fileSystem.newDirectoryStream(createPath("/"), AcceptAllFilter.INSTANCE)) {
            assertNotNull(stream);
            // don't do anything with the stream, there's a separate test for that
        }
    }

    @Test
    public void testNewDirectoryStreamNotExisting() {
        NoSuchFileException exception = assertThrows(NoSuchFileException.class,
                () -> fileSystem.newDirectoryStream(createPath("/foo"), AcceptAllFilter.INSTANCE));
        assertEquals("/foo", exception.getFile());
    }

    @Test
    public void testGetDirectoryStreamNotDirectory() throws IOException {
        addFile("/foo");

        NotDirectoryException exception = assertThrows(NotDirectoryException.class,
                () -> fileSystem.newDirectoryStream(createPath("/foo"), AcceptAllFilter.INSTANCE));
        assertEquals("/foo", exception.getFile());
    }

    private static final class AcceptAllFilter implements Filter<Path> {

        private static final AcceptAllFilter INSTANCE = new AcceptAllFilter();

        @Override
        public boolean accept(Path entry) {
            return true;
        }
    }

    // SFTPFileSystem.createNewDirectory

    @Test
    public void testCreateDirectory() throws IOException {
        assertFalse(Files.exists(getPath("/foo")));

        fileSystem.createDirectory(createPath("/foo"));

        assertTrue(Files.isDirectory(getPath("/foo")));
    }

    @Test
    public void testCreateDirectoryAlreadyExists() throws IOException {
        addDirectory("/foo/bar");

        FileAlreadyExistsException exception = assertThrows(FileAlreadyExistsException.class,
                () -> fileSystem.createDirectory(createPath("/foo/bar")));
        assertEquals("/foo/bar", exception.getFile());

        verify(getExceptionFactory(), never()).createCreateDirectoryException(anyString(), any(SftpException.class));
        assertTrue(Files.exists(getPath("/foo")));
        assertTrue(Files.exists(getPath("/foo/bar")));
    }

    @Test
    public void testCreateDirectorySFTPFailure() {
        // failure: parent does not exist

        NoSuchFileException exception = assertThrows(NoSuchFileException.class, () -> fileSystem.createDirectory(createPath("/foo/bar")));
        assertEquals("/foo/bar", exception.getFile());

        verify(getExceptionFactory()).createCreateDirectoryException(eq("/foo/bar"), any(SftpException.class));
        assertFalse(Files.exists(getPath("/foo")));
        assertFalse(Files.exists(getPath("/foo/bar")));
    }

    // SFTPFileSystem.delete

    @Test
    public void testDeleteNonExisting() {

        NoSuchFileException exception = assertThrows(NoSuchFileException.class, () -> fileSystem.delete(createPath("/foo")));
        assertEquals("/foo", exception.getFile());

        verify(getExceptionFactory(), never()).createDeleteException(eq("/foo"), any(SftpException.class), anyBoolean());
    }

    @Test
    public void testDeleteRoot() {

        FileSystemException exception = assertThrows(FileSystemException.class, () -> fileSystem.delete(createPath("/")));
        assertEquals("/", exception.getFile());

        verify(getExceptionFactory()).createDeleteException(eq("/"), any(SftpException.class), eq(true));
    }

    @Test
    public void testDeleteFile() throws IOException {
        addFile("/foo/bar");

        fileSystem.delete(createPath("/foo/bar"));

        assertTrue(Files.isDirectory(getPath("/foo")));
        assertFalse(Files.exists(getPath("/foo/bar")));
    }

    @Test
    public void testDeleteEmptyDir() throws IOException {
        addDirectory("/foo/bar");

        fileSystem.delete(createPath("/foo/bar"));

        assertTrue(Files.isDirectory(getPath("/foo")));
        assertFalse(Files.exists(getPath("/foo/bar")));
    }

    @Test
    public void testDeleteSFTPFailure() throws IOException {
        addDirectory("/foo/bar/baz");

        // failure: non-empty directory

        FileSystemException exception = assertThrows(FileSystemException.class, () -> fileSystem.delete(createPath("/foo/bar")));
        assertEquals("/foo/bar", exception.getFile());

        verify(getExceptionFactory()).createDeleteException(eq("/foo/bar"), any(SftpException.class), eq(true));
        assertTrue(Files.isDirectory(getPath("/foo")));
        assertTrue(Files.isDirectory(getPath("/foo/bar")));
        assertTrue(Files.isDirectory(getPath("/foo/bar/baz")));
    }

    // SFTPFileSystem.readSymbolicLink

    @Test
    public void testReadSymbolicLinkToFile() throws IOException {
        Path foo = addFile("/foo");
        addSymLink("/bar", foo);

        SFTPPath link = fileSystem.readSymbolicLink(createPath("/bar"));
        assertEquals(createPath("/foo"), link);
    }

    @Test
    public void testReadSymbolicLinkToDirectory() throws IOException {
        Path foo = addDirectory("/foo");
        addSymLink("/bar", foo);

        SFTPPath link = fileSystem.readSymbolicLink(createPath("/bar"));
        assertEquals(createPath("/foo"), link);
    }

    @Test
    public void testReadSymbolicLinkToNonExistingTarget() throws IOException {
        addSymLink("/bar", getPath("/foo"));

        SFTPPath link = fileSystem.readSymbolicLink(createPath("/bar"));
        assertEquals(createPath("/foo"), link);
    }

    @Test
    public void testReadSymbolicLinkNotExisting() {

        NoSuchFileException exception = assertThrows(NoSuchFileException.class, () -> fileSystem.readSymbolicLink(createPath("/foo")));
        assertEquals("/foo", exception.getFile());

        verify(getExceptionFactory()).createReadLinkException(eq("/foo"), any(SftpException.class));
    }

    @Test
    public void testReadSymbolicLinkNoLinkButFile() throws IOException {
        addFile("/foo");

        FileSystemException exception = assertThrows(FileSystemException.class, () -> fileSystem.readSymbolicLink(createPath("/foo")));
        assertEquals("/foo", exception.getFile());

        verify(getExceptionFactory()).createReadLinkException(eq("/foo"), any(SftpException.class));
    }

    @Test
    public void testReadSymbolicLinkNoLinkButDirectory() throws IOException {
        addDirectory("/foo");

        FileSystemException exception = assertThrows(FileSystemException.class, () -> fileSystem.readSymbolicLink(createPath("/foo")));
        assertEquals("/foo", exception.getFile());

        verify(getExceptionFactory()).createReadLinkException(eq("/foo"), any(SftpException.class));
    }

    // SFTPFileSystem.copy

    @Test
    public void testCopySame() throws IOException {
        addDirectory("/home/foo");
        addDirectory("/home/foo/bar");

        CopyOption[] options = {};
        fileSystem.copy(createPath("/home"), createPath(""), options);
        fileSystem.copy(createPath("/home/foo"), createPath("foo"), options);
        fileSystem.copy(createPath("/home/foo/bar"), createPath("foo/bar"), options);

        assertTrue(Files.isDirectory(getPath("/home/foo")));
        assertTrue(Files.isDirectory(getPath("/home/foo/bar")));
        assertEquals(0, getChildCount("/home/foo/bar"));
    }

    @Test
    public void testCopyNonExisting() throws IOException {
        addDirectory("/foo");

        CopyOption[] options = {};
        NoSuchFileException exception = assertThrows(NoSuchFileException.class,
                () -> fileSystem.copy(createPath("/foo/bar"), createPath("/foo/baz"), options));
        assertEquals("/foo/bar", exception.getFile());

        assertTrue(Files.isDirectory(getPath("/foo")));
        assertEquals(0, getChildCount("/foo"));
    }

    @Test
    public void testCopySFTPFailure() throws IOException {
        addDirectory("/foo");
        addFile("/foo/bar");

        // failure: target parent does not exist

        CopyOption[] options = {};
        NoSuchFileException exception = assertThrows(NoSuchFileException.class,
                () -> fileSystem.copy(createPath("/foo/bar"), createPath("/baz/bar"), options));
        assertEquals("/baz/bar", exception.getFile());

        verify(getExceptionFactory()).createNewOutputStreamException(eq("/baz/bar"), any(SftpException.class), anyCollection());
        assertTrue(Files.isDirectory(getPath("/foo")));
        assertTrue(Files.isRegularFile(getPath("/foo/bar")));
        assertFalse(Files.exists(getPath("/baz")));
        assertFalse(Files.exists(getPath("/baz/bar")));
    }

    @Test
    public void testCopyRoot() throws IOException {
        // copying a directory (including the root) will not copy its contents, so copying the root is allowed
        addDirectory("/foo");

        CopyOption[] options = {};
        fileSystem.copy(createPath("/"), createPath("/foo/bar"), options);

        assertTrue(Files.isDirectory(getPath("/foo")));
        assertTrue(Files.isDirectory(getPath("/foo/bar")));
        assertEquals(0, getChildCount("/foo/bar"));
    }

    @Test
    public void testCopyReplaceFile() throws IOException {
        addDirectory("/foo");
        addFile("/foo/bar");
        addFile("/baz");

        CopyOption[] options = {};
        FileAlreadyExistsException exception = assertThrows(FileAlreadyExistsException.class,
                () -> fileSystem.copy(createPath("/baz"), createPath("/foo/bar"), options));
        assertEquals("/foo/bar", exception.getFile());

        assertTrue(Files.isDirectory(getPath("/foo")));
        assertTrue(Files.isRegularFile(getPath("/foo/bar")));
        assertTrue(Files.isRegularFile(getPath("/baz")));
    }

    @Test
    public void testCopyReplaceFileAllowed() throws IOException {
        addDirectory("/foo");
        addFile("/foo/bar");
        addFile("/baz");

        CopyOption[] options = { StandardCopyOption.REPLACE_EXISTING };
        fileSystem.copy(createPath("/baz"), createPath("/foo/bar"), options);

        assertTrue(Files.isDirectory(getPath("/foo")));
        assertTrue(Files.isRegularFile(getPath("/foo/bar")));
        assertTrue(Files.isRegularFile(getPath("/foo/bar")));
    }

    @Test
    public void testCopyReplaceNonEmptyDir() throws IOException {
        addDirectory("/foo");
        addFile("/foo/bar");
        addFile("/baz");

        CopyOption[] options = {};
        FileAlreadyExistsException exception = assertThrows(FileAlreadyExistsException.class,
                () -> fileSystem.copy(createPath("/baz"), createPath("/foo"), options));
        assertEquals("/foo", exception.getFile());

        assertTrue(Files.isDirectory(getPath("/foo")));
        assertTrue(Files.isRegularFile(getPath("/foo/bar")));
        assertTrue(Files.isRegularFile(getPath("/baz")));
    }

    @Test
    public void testCopyReplaceNonEmptyDirAllowed() throws IOException {
        addDirectory("/foo");
        addFile("/foo/bar");
        addFile("/baz");

        CopyOption[] options = { StandardCopyOption.REPLACE_EXISTING };
        FileSystemException exception = assertThrows(FileSystemException.class,
                () -> fileSystem.copy(createPath("/baz"), createPath("/foo"), options));
        assertEquals("/foo", exception.getFile());

        verify(getExceptionFactory()).createDeleteException(eq("/foo"), any(SftpException.class), eq(true));
        assertTrue(Files.isDirectory(getPath("/foo")));
        assertTrue(Files.isRegularFile(getPath("/foo/bar")));
        assertTrue(Files.isRegularFile(getPath("/baz")));
    }

    @Test
    public void testCopyReplaceEmptyDir() throws IOException {
        addDirectory("/foo");
        addFile("/baz");

        CopyOption[] options = {};
        FileAlreadyExistsException exception = assertThrows(FileAlreadyExistsException.class,
                () -> fileSystem.copy(createPath("/baz"), createPath("/foo"), options));
        assertEquals("/foo", exception.getFile());

        assertTrue(Files.isDirectory(getPath("/foo")));
        assertTrue(Files.isRegularFile(getPath("/baz")));
    }

    @Test
    public void testCopyReplaceEmptyDirAllowed() throws IOException {
        addDirectory("/foo");
        addDirectory("/baz");

        CopyOption[] options = { StandardCopyOption.REPLACE_EXISTING };
        fileSystem.copy(createPath("/baz"), createPath("/foo"), options);

        assertTrue(Files.isDirectory(getPath("/foo")));
        assertTrue(Files.isDirectory(getPath("/foo")));
    }

    @Test
    public void testCopyFile() throws IOException {
        addDirectory("/foo");
        addFile("/baz");

        CopyOption[] options = {};
        fileSystem.copy(createPath("/baz"), createPath("/foo/bar"), options);

        assertTrue(Files.isDirectory(getPath("/foo")));
        assertTrue(Files.isRegularFile(getPath("/foo/bar")));
        assertTrue(Files.isRegularFile(getPath("/baz")));
    }

    @Test
    public void testCopyFileMultipleConnections() throws IOException {
        addDirectory("/foo");
        addFile("/baz");

        CopyOption[] options = {};
        fileSystem2.copy(createPath(fileSystem2, "/baz"), createPath(fileSystem2, "/foo/bar"), options);

        assertTrue(Files.isDirectory(getPath("/foo")));
        assertTrue(Files.isRegularFile(getPath("/foo/bar")));
        assertTrue(Files.isRegularFile(getPath("/baz")));
    }

    @Test
    public void testCopyEmptyDir() throws IOException {
        addDirectory("/foo");
        addDirectory("/baz");

        CopyOption[] options = {};
        fileSystem.copy(createPath("/baz"), createPath("/foo/bar"), options);

        assertTrue(Files.isDirectory(getPath("/foo")));
        assertTrue(Files.isDirectory(getPath("/foo/bar")));
        assertTrue(Files.isDirectory(getPath("/baz")));

        assertEquals(0, getChildCount("/foo/bar"));
    }

    @Test
    public void testCopyNonEmptyDir() throws IOException {
        addDirectory("/foo");
        addDirectory("/baz");
        addFile("/baz/qux");

        CopyOption[] options = {};
        fileSystem.copy(createPath("/baz"), createPath("/foo/bar"), options);

        assertTrue(Files.isDirectory(getPath("/foo")));
        assertTrue(Files.isDirectory(getPath("/foo/bar")));
        assertTrue(Files.isDirectory(getPath("/baz")));

        assertEquals(0, getChildCount("/foo/bar"));
    }

    @Test
    public void testCopyReplaceFileDifferentFileSystems() throws IOException {
        addDirectory("/foo");
        addFile("/foo/bar");
        addFile("/baz");

        CopyOption[] options = {};
        FileAlreadyExistsException exception = assertThrows(FileAlreadyExistsException.class,
                () -> fileSystem.copy(createPath("/baz"), createPath(fileSystem2, "/foo/bar"), options));
        assertEquals("/foo/bar", exception.getFile());

        assertTrue(Files.isDirectory(getPath("/foo")));
        assertTrue(Files.isRegularFile(getPath("/foo/bar")));
        assertTrue(Files.isRegularFile(getPath("/baz")));
    }

    @Test
    public void testCopyReplaceFileAllowedDifferentFileSystems() throws IOException {
        addDirectory("/foo");
        addFile("/foo/bar");
        addFile("/baz");

        CopyOption[] options = { StandardCopyOption.REPLACE_EXISTING };
        fileSystem.copy(createPath("/baz"), createPath(fileSystem2, "/foo/bar"), options);

        assertTrue(Files.isDirectory(getPath("/foo")));
        assertTrue(Files.isRegularFile(getPath("/foo/bar")));
        assertTrue(Files.isRegularFile(getPath("/foo/bar")));
    }

    @Test
    public void testCopyReplaceNonEmptyDirDifferentFileSystems() throws IOException {
        addDirectory("/foo");
        addFile("/foo/bar");
        addFile("/baz");

        CopyOption[] options = {};
        FileAlreadyExistsException exception = assertThrows(FileAlreadyExistsException.class,
                () -> fileSystem.copy(createPath("/baz"), createPath(fileSystem2, "/foo"), options));
        assertEquals("/foo", exception.getFile());

        assertTrue(Files.isDirectory(getPath("/foo")));
        assertTrue(Files.isRegularFile(getPath("/foo/bar")));
        assertTrue(Files.isRegularFile(getPath("/baz")));
    }

    @Test
    public void testCopyReplaceNonEmptyDirAllowedDifferentFileSystems() throws IOException {
        addDirectory("/foo");
        addFile("/foo/bar");
        addFile("/baz");

        CopyOption[] options = { StandardCopyOption.REPLACE_EXISTING };
        FileSystemException exception = assertThrows(FileSystemException.class,
                () -> fileSystem.copy(createPath("/baz"), createPath(fileSystem2, "/foo"), options));
        assertEquals("/foo", exception.getFile());

        verify(getExceptionFactory()).createDeleteException(eq("/foo"), any(SftpException.class), eq(true));
        assertTrue(Files.isDirectory(getPath("/foo")));
        assertTrue(Files.isRegularFile(getPath("/foo/bar")));
        assertTrue(Files.isRegularFile(getPath("/baz")));
    }

    @Test
    public void testCopyReplaceEmptyDirDifferentFileSystems() throws IOException {
        addDirectory("/foo");
        addFile("/baz");

        CopyOption[] options = {};
        FileAlreadyExistsException exception = assertThrows(FileAlreadyExistsException.class,
                () -> fileSystem.copy(createPath("/baz"), createPath(fileSystem2, "/foo"), options));
        assertEquals("/foo", exception.getFile());

        assertTrue(Files.isDirectory(getPath("/foo")));
        assertTrue(Files.isRegularFile(getPath("/baz")));
    }

    @Test
    public void testCopyReplaceEmptyDirAllowedDifferentFileSystems() throws IOException {
        addDirectory("/foo");
        addDirectory("/baz");

        CopyOption[] options = { StandardCopyOption.REPLACE_EXISTING };
        fileSystem.copy(createPath("/baz"), createPath(fileSystem2, "/foo"), options);

        assertTrue(Files.isDirectory(getPath("/foo")));
        assertTrue(Files.isDirectory(getPath("/foo")));
    }

    @Test
    public void testCopyFileDifferentFileSystems() throws IOException {
        addDirectory("/foo");
        addFile("/baz");

        CopyOption[] options = {};
        fileSystem.copy(createPath("/baz"), createPath(fileSystem2, "/foo/bar"), options);

        assertTrue(Files.isDirectory(getPath("/foo")));
        assertTrue(Files.isRegularFile(getPath("/foo/bar")));
        assertTrue(Files.isRegularFile(getPath("/baz")));
    }

    @Test
    public void testCopyEmptyDirDifferentFileSystems() throws IOException {
        addDirectory("/foo");
        addDirectory("/baz");

        CopyOption[] options = {};
        fileSystem.copy(createPath("/baz"), createPath(fileSystem2, "/foo/bar"), options);

        assertTrue(Files.isDirectory(getPath("/foo")));
        assertTrue(Files.isDirectory(getPath("/foo/bar")));
        assertTrue(Files.isDirectory(getPath("/baz")));

        assertEquals(0, getChildCount("/foo/bar"));
    }

    @Test
    public void testCopyNonEmptyDirDifferentFileSystems() throws IOException {
        addDirectory("/foo");
        addDirectory("/baz");
        addFile("/baz/qux");

        CopyOption[] options = {};
        fileSystem.copy(createPath("/baz"), createPath(fileSystem2, "/foo/bar"), options);

        assertTrue(Files.isDirectory(getPath("/foo")));
        assertTrue(Files.isDirectory(getPath("/foo/bar")));
        assertTrue(Files.isDirectory(getPath("/baz")));

        assertEquals(0, getChildCount("/foo/bar"));
    }

    @Test
    public void testCopyWithAttributes() throws IOException {
        addDirectory("/foo");
        addDirectory("/baz");
        addFile("/baz/qux");

        CopyOption[] options = { StandardCopyOption.COPY_ATTRIBUTES };
        UnsupportedOperationException exception = assertThrows(UnsupportedOperationException.class,
                () -> fileSystem.copy(createPath("/baz"), createPath("/foo/bar"), options));
        assertEquals(Messages.fileSystemProvider().unsupportedCopyOption(StandardCopyOption.COPY_ATTRIBUTES).getMessage(), exception.getMessage());
    }

    // SFTPFileSystem.move

    @Test
    public void testMoveSame() throws IOException {
        Path foo = addDirectory("/home/foo");
        addDirectory("/home/foo/bar");
        addSymLink("/baz", foo);

        CopyOption[] options = {};
        fileSystem.move(createPath("/"), createPath("/"), options);
        fileSystem.move(createPath("/home"), createPath(""), options);
        fileSystem.move(createPath("/home/foo"), createPath("foo"), options);
        fileSystem.move(createPath("/home/foo/bar"), createPath("foo/bar"), options);
        fileSystem.move(createPath("/home/foo"), createPath("/baz"), options);
        fileSystem.move(createPath("/baz"), createPath("/home/foo"), options);

        assertTrue(Files.isDirectory(getPath("/home/foo")));
        assertTrue(Files.isDirectory(getPath("/home/foo/bar")));
        assertTrue(Files.isSymbolicLink(getPath("/baz")));
        assertEquals(0, getChildCount("/home/foo/bar"));
    }

    @Test
    public void testMoveNonExisting() throws IOException {
        addDirectory("/foo");

        CopyOption[] options = {};
        NoSuchFileException exception = assertThrows(NoSuchFileException.class,
                () -> fileSystem.move(createPath("/foo/bar"), createPath("/foo/baz"), options));
        assertEquals("/foo/bar", exception.getFile());

        assertTrue(Files.isDirectory(getPath("/foo")));
        assertEquals(0, getChildCount("/foo"));
    }

    @Test
    public void testMoveSFTPFailure() throws IOException {
        addDirectory("/foo");
        addFile("/foo/bar");

        // failure: non-existing target parent

        CopyOption[] options = {};
        FileSystemException exception = assertThrows(FileSystemException.class,
                () -> fileSystem.move(createPath("/foo/bar"), createPath("/baz/bar"), options));
        assertEquals("/foo/bar", exception.getFile());
        assertEquals("/baz/bar", exception.getOtherFile());

        verify(getExceptionFactory()).createMoveException(eq("/foo/bar"), eq("/baz/bar"), any(SftpException.class));
        assertTrue(Files.isDirectory(getPath("/foo")));
        assertTrue(Files.isRegularFile(getPath("/foo/bar")));
        assertFalse(Files.exists(getPath("/baz")));
    }

    @Test
    public void testMoveEmptyRoot() {

        CopyOption[] options = {};
        DirectoryNotEmptyException exception = assertThrows(DirectoryNotEmptyException.class,
                () -> fileSystem.move(createPath("/"), createPath("/baz"), options));
        assertEquals("/", exception.getFile());

        assertFalse(Files.exists(getPath("/baz")));
    }

    @Test
    public void testMoveNonEmptyRoot() throws IOException {
        addDirectory("/foo");

        CopyOption[] options = {};
        DirectoryNotEmptyException exception = assertThrows(DirectoryNotEmptyException.class,
                () -> fileSystem.move(createPath("/"), createPath("/baz"), options));
        assertEquals("/", exception.getFile());

        assertTrue(Files.isDirectory(getPath("/foo")));
        assertFalse(Files.exists(getPath("/baz")));
    }

    @Test
    public void testMoveReplaceFile() throws IOException {
        addDirectory("/foo");
        addDirectory("/foo/bar");
        addFile("/baz");

        CopyOption[] options = {};
        FileSystemException exception = assertThrows(FileSystemException.class,
                () -> fileSystem.move(createPath("/baz"), createPath("/foo/bar"), options));
        assertEquals("/baz", exception.getFile());
        assertEquals("/foo/bar", exception.getOtherFile());

        verify(getExceptionFactory()).createMoveException(eq("/baz"), eq("/foo/bar"), any(SftpException.class));
        assertTrue(Files.isDirectory(getPath("/foo")));
        assertTrue(Files.isDirectory(getPath("/foo/bar")));
        assertTrue(Files.isRegularFile(getPath("/baz")));
    }

    @Test
    public void testMoveReplaceFileAllowed() throws IOException {
        addDirectory("/foo");
        addFile("/foo/bar");
        addFile("/baz");

        CopyOption[] options = { StandardCopyOption.REPLACE_EXISTING };
        fileSystem.move(createPath("/baz"), createPath("/foo/bar"), options);

        assertTrue(Files.isDirectory(getPath("/foo")));
        assertTrue(Files.isRegularFile(getPath("/foo/bar")));
        assertFalse(Files.exists(getPath("/baz")));
    }

    @Test
    public void testMoveReplaceEmptyDir() throws IOException {
        addDirectory("/foo");
        addFile("/baz");

        CopyOption[] options = {};
        FileSystemException exception = assertThrows(FileSystemException.class,
                () -> fileSystem.move(createPath("/baz"), createPath("/foo"), options));
        assertEquals("/baz", exception.getFile());
        assertEquals("/foo", exception.getOtherFile());

        verify(getExceptionFactory()).createMoveException(eq("/baz"), eq("/foo"), any(SftpException.class));
        assertTrue(Files.isDirectory(getPath("/foo")));
        assertTrue(Files.isRegularFile(getPath("/baz")));
    }

    @Test
    public void testMoveReplaceEmptyDirAllowed() throws IOException {
        addDirectory("/foo");
        addFile("/baz");

        CopyOption[] options = { StandardCopyOption.REPLACE_EXISTING };
        fileSystem.move(createPath("/baz"), createPath("/foo"), options);

        assertTrue(Files.isRegularFile(getPath("/foo")));
        assertFalse(Files.exists(getPath("/baz")));
    }

    @Test
    public void testMoveFile() throws IOException {
        addDirectory("/foo");
        addFile("/baz");

        CopyOption[] options = {};
        fileSystem.move(createPath("/baz"), createPath("/foo/bar"), options);

        assertTrue(Files.isDirectory(getPath("/foo")));
        assertTrue(Files.isRegularFile(getPath("/foo/bar")));
        assertFalse(Files.exists(getPath("/baz")));
    }

    @Test
    public void testMoveEmptyDir() throws IOException {
        addDirectory("/foo");
        addDirectory("/baz");

        CopyOption[] options = {};
        fileSystem.move(createPath("/baz"), createPath("/foo/bar"), options);

        assertTrue(Files.isDirectory(getPath("/foo")));
        assertTrue(Files.isDirectory(getPath("/foo/bar")));
        assertFalse(Files.exists(getPath("/baz")));
    }

    @Test
    public void testMoveNonEmptyDir() throws IOException {
        addDirectory("/foo");
        addDirectory("/baz");
        addFile("/baz/qux");

        CopyOption[] options = {};
        fileSystem.move(createPath("/baz"), createPath("/foo/bar"), options);

        assertTrue(Files.isDirectory(getPath("/foo")));
        assertTrue(Files.isDirectory(getPath("/foo/bar")));
        assertTrue(Files.isRegularFile(getPath("/foo/bar/qux")));
        assertEquals(1, getChildCount("/foo"));
        assertEquals(1, getChildCount("/foo/bar"));
    }

    @Test
    public void testMoveNonEmptyDirSameParent() throws IOException {
        addDirectory("/foo");
        addFile("/foo/bar");

        CopyOption[] options = {};
        try {
            fileSystem.move(createPath("/foo"), createPath("/baz"), options);
        } finally {
            assertFalse(Files.exists(getPath("/foo")));
            assertTrue(Files.isDirectory(getPath("/baz")));
            assertTrue(Files.isRegularFile(getPath("/baz/bar")));
        }
    }

    @Test
    public void testMoveReplaceFileDifferentFileSystem() throws IOException {
        addDirectory("/foo");
        addDirectory("/foo/bar");
        addFile("/baz");

        CopyOption[] options = {};
        FileAlreadyExistsException exception = assertThrows(FileAlreadyExistsException.class,
                () -> fileSystem.move(createPath("/baz"), createPath(fileSystem2, "/foo/bar"), options));
        assertEquals("/foo/bar", exception.getFile());

        verify(getExceptionFactory(), never()).createMoveException(anyString(), anyString(), any(SftpException.class));
        assertTrue(Files.isDirectory(getPath("/foo")));
        assertTrue(Files.isDirectory(getPath("/foo/bar")));
        assertTrue(Files.isRegularFile(getPath("/baz")));
    }

    @Test
    public void testMoveReplaceFileAllowedDifferentFileSystem() throws IOException {
        addDirectory("/foo");
        addFile("/foo/bar");
        addFile("/baz");

        CopyOption[] options = { StandardCopyOption.REPLACE_EXISTING };
        fileSystem.move(createPath("/baz"), createPath(fileSystem2, "/foo/bar"), options);

        assertTrue(Files.isDirectory(getPath("/foo")));
        assertTrue(Files.isRegularFile(getPath("/foo/bar")));
        assertFalse(Files.exists(getPath("/baz")));
    }

    @Test
    public void testMoveReplaceEmptyDirDifferentFileSystem() throws IOException {
        addDirectory("/foo");
        addFile("/baz");

        CopyOption[] options = {};
        FileAlreadyExistsException exception = assertThrows(FileAlreadyExistsException.class,
                () -> fileSystem.move(createPath("/baz"), createPath(fileSystem2, "/foo"), options));
        assertEquals("/foo", exception.getFile());

        verify(getExceptionFactory(), never()).createMoveException(anyString(), anyString(), any(SftpException.class));
        assertTrue(Files.isDirectory(getPath("/foo")));
        assertTrue(Files.isRegularFile(getPath("/baz")));
    }

    public void testMoveReplaceEmptyDirAllowedDifferentFileSystem() throws IOException {
        addDirectory("/foo");
        addFile("/baz");

        CopyOption[] options = { StandardCopyOption.REPLACE_EXISTING };
        fileSystem.move(createPath("/baz"), createPath(fileSystem2, "/foo"), options);

        assertTrue(Files.isRegularFile(getPath("/foo")));
        assertFalse(Files.exists(getPath("/baz")));
    }

    @Test
    public void testMoveFileDifferentFileSystem() throws IOException {
        addDirectory("/foo");
        addFile("/baz");

        CopyOption[] options = {};
        fileSystem.move(createPath("/baz"), createPath(fileSystem2, "/foo/bar"), options);

        assertTrue(Files.isDirectory(getPath("/foo")));
        assertTrue(Files.isRegularFile(getPath("/foo/bar")));
        assertFalse(Files.exists(getPath("/baz")));
    }

    @Test
    public void testMoveEmptyDirDifferentFileSystem() throws IOException {
        addDirectory("/foo");
        addDirectory("/baz");

        CopyOption[] options = {};
        fileSystem.move(createPath("/baz"), createPath(fileSystem2, "/foo/bar"), options);

        assertTrue(Files.isDirectory(getPath("/foo")));
        assertTrue(Files.isDirectory(getPath("/foo/bar")));
        assertFalse(Files.exists(getPath("/baz")));
    }

    @Test
    public void testMoveNonEmptyDirDifferentFileSystem() throws IOException {
        addDirectory("/foo");
        addDirectory("/baz");
        addFile("/baz/qux");

        CopyOption[] options = {};
        FileSystemException exception = assertThrows(FileSystemException.class,
                () -> fileSystem.move(createPath("/baz"), createPath(fileSystem2, "/foo/bar"), options));
        assertEquals("/baz", exception.getFile());

        verify(getExceptionFactory()).createDeleteException(eq("/baz"), any(SftpException.class), eq(true));
        assertTrue(Files.isDirectory(getPath("/baz")));
        assertTrue(Files.isRegularFile(getPath("/baz/qux")));
        assertEquals(1, getChildCount("/baz"));

        assertTrue(Files.isDirectory(getPath("/foo")));
        assertTrue(Files.isDirectory(getPath("/foo/bar")));
        assertFalse(Files.isRegularFile(getPath("/foo/bar/qux")));
        assertEquals(1, getChildCount("/foo"));
        assertEquals(0, getChildCount("/foo/bar"));
    }

    // SFTPFileSystem.isSameFile

    @Test
    public void testIsSameFileEquals() throws IOException {

        assertTrue(fileSystem.isSameFile(createPath("/"), createPath("/")));
        assertTrue(fileSystem.isSameFile(createPath("/foo"), createPath("/foo")));
        assertTrue(fileSystem.isSameFile(createPath("/foo/bar"), createPath("/foo/bar")));

        assertTrue(fileSystem.isSameFile(createPath(""), createPath("")));
        assertTrue(fileSystem.isSameFile(createPath("foo"), createPath("foo")));
        assertTrue(fileSystem.isSameFile(createPath("foo/bar"), createPath("foo/bar")));

        assertTrue(fileSystem.isSameFile(createPath(""), createPath("/home")));
        assertTrue(fileSystem.isSameFile(createPath("/home"), createPath("")));
    }

    @Test
    public void testIsSameFileExisting() throws IOException {
        Path bar = addFile("/home/foo/bar");
        addSymLink("/bar", bar);

        assertTrue(fileSystem.isSameFile(createPath("/home"), createPath("")));
        assertTrue(fileSystem.isSameFile(createPath("/home/foo"), createPath("foo")));
        assertTrue(fileSystem.isSameFile(createPath("/home/foo/bar"), createPath("foo/bar")));

        assertTrue(fileSystem.isSameFile(createPath(""), createPath("/home")));
        assertTrue(fileSystem.isSameFile(createPath("foo"), createPath("/home/foo")));
        assertTrue(fileSystem.isSameFile(createPath("foo/bar"), createPath("/home/foo/bar")));

        assertFalse(fileSystem.isSameFile(createPath("foo"), createPath("foo/bar")));

        assertTrue(fileSystem.isSameFile(createPath("/bar"), createPath("/home/foo/bar")));
    }

    @Test
    public void testIsSameFileFirstNonExisting() {
        NoSuchFileException exception = assertThrows(NoSuchFileException.class, () -> fileSystem.isSameFile(createPath("/foo"), createPath("/")));
        assertEquals("/foo", exception.getFile());
    }

    @Test
    public void testIsSameFileSecondNonExisting() {
        NoSuchFileException exception = assertThrows(NoSuchFileException.class, () -> fileSystem.isSameFile(createPath("/"), createPath("/foo")));
        assertEquals("/foo", exception.getFile());
    }

    // SFTPFileSystem.isHidden

    @Test
    public void testIsHidden() throws IOException {
        addDirectory("/foo");
        addDirectory("/.foo");
        addFile("/foo/bar");
        addFile("/foo/.bar");

        assertFalse(fileSystem.isHidden(createPath("/foo")));
        assertTrue(fileSystem.isHidden(createPath("/.foo")));
        assertFalse(fileSystem.isHidden(createPath("/foo/bar")));
        assertTrue(fileSystem.isHidden(createPath("/foo/.bar")));
    }

    @Test
    public void testIsHiddenNonExisting() {
        NoSuchFileException exception = assertThrows(NoSuchFileException.class, () -> fileSystem.isHidden(createPath("/foo")));
        assertEquals("/foo", exception.getFile());
    }

    // SFTPFileSystem.checkAccess

    @Test
    public void testCheckAccessNonExisting() {
        NoSuchFileException exception = assertThrows(NoSuchFileException.class, () -> fileSystem.checkAccess(createPath("/foo/bar")));
        assertEquals("/foo/bar", exception.getFile());
    }

    @Test
    public void testCheckAccessNoModes() throws IOException {
        addDirectory("/foo/bar");

        fileSystem.checkAccess(createPath("/foo/bar"));
    }

    @Test
    public void testCheckAccessOnlyRead() throws IOException {
        addDirectory("/foo/bar");

        fileSystem.checkAccess(createPath("/foo/bar"), AccessMode.READ);
    }

    @Test
    public void testCheckAccessOnlyWriteNotReadOnly() throws IOException {
        addDirectory("/foo/bar");

        fileSystem.checkAccess(createPath("/foo/bar"), AccessMode.WRITE);
    }

    @Disabled("On Windows, the permissions are not reported correctly, but always as rw-rw-rw-")
    @Test
    public void testCheckAccessOnlyWriteReadOnly() throws IOException {
        Path bar = addDirectory("/foo/bar");
        bar.toFile().setReadOnly();

        AccessDeniedException exception = assertThrows(AccessDeniedException.class,
                () -> fileSystem.checkAccess(createPath("/foo/bar"), AccessMode.WRITE));
        assertEquals("/foo/bar", exception.getFile());
    }

    @Test
    public void testCheckAccessOnlyExecute() throws IOException {
        Path bar = addFile("/foo/bar");
        bar.toFile().setReadOnly();

        AccessDeniedException exception = assertThrows(AccessDeniedException.class,
                () -> fileSystem.checkAccess(createPath("/foo/bar"), AccessMode.EXECUTE));
        assertEquals("/foo/bar", exception.getFile());
    }

    // SFTPFileSystem.readAttributes (SFTPFileAttributes variant)

    @Test
    public void testReadAttributesFileFollowLinks() throws IOException {
        Path foo = addFile("/foo");
        setContents(foo, new byte[1024]);

        PosixFileAttributes attributes = fileSystem.readAttributes(createPath("/foo"));

        assertEquals(Files.size(foo), attributes.size());
        assertNotNull(attributes.owner().getName());
        assertNotNull(attributes.group().getName());
        assertNotNull(attributes.permissions());
        assertFalse(attributes.isDirectory());
        assertTrue(attributes.isRegularFile());
        assertFalse(attributes.isSymbolicLink());
        assertFalse(attributes.isOther());
    }

    @Test
    public void testReadAttributesFileNoFollowLinks() throws IOException {
        Path foo = addFile("/foo");
        setContents(foo, new byte[1024]);

        PosixFileAttributes attributes = fileSystem.readAttributes(createPath("/foo"), LinkOption.NOFOLLOW_LINKS);

        assertEquals(Files.size(foo), attributes.size());
        assertNotNull(attributes.owner().getName());
        assertNotNull(attributes.group().getName());
        assertNotNull(attributes.permissions());
        assertFalse(attributes.isDirectory());
        assertTrue(attributes.isRegularFile());
        assertFalse(attributes.isSymbolicLink());
        assertFalse(attributes.isOther());
    }

    @Test
    public void testReadAttributesDirectoryFollowLinks() throws IOException {
        Path foo = addDirectory("/foo");

        PosixFileAttributes attributes = fileSystem.readAttributes(createPath("/foo"));

        assertEquals(Files.size(foo), attributes.size());
        assertNotNull(attributes.owner().getName());
        assertNotNull(attributes.group().getName());
        assertNotNull(attributes.permissions());
        assertTrue(attributes.isDirectory());
        assertFalse(attributes.isRegularFile());
        assertFalse(attributes.isSymbolicLink());
        assertFalse(attributes.isOther());
    }

    @Test
    public void testReadAttributesDirectoryNoFollowLinks() throws IOException {
        Path foo = addDirectory("/foo");

        PosixFileAttributes attributes = fileSystem.readAttributes(createPath("/foo"), LinkOption.NOFOLLOW_LINKS);

        assertEquals(Files.size(foo), attributes.size());
        assertNotNull(attributes.owner().getName());
        assertNotNull(attributes.group().getName());
        assertNotNull(attributes.permissions());
        assertTrue(attributes.isDirectory());
        assertFalse(attributes.isRegularFile());
        assertFalse(attributes.isSymbolicLink());
        assertFalse(attributes.isOther());
    }

    @Test
    public void testReadAttributesSymLinkToFileFollowLinks() throws IOException {
        Path foo = addFile("/foo");
        setContents(foo, new byte[1024]);
        Path bar = addSymLink("/bar", foo);

        PosixFileAttributes attributes = fileSystem.readAttributes(createPath("/bar"));

        long sizeOfFoo = Files.readAttributes(foo, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).size();
        long sizeOfBar = Files.readAttributes(bar, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).size();

        assertEquals(sizeOfFoo, attributes.size());
        assertNotEquals(sizeOfBar, attributes.size());
        assertNotNull(attributes.owner().getName());
        assertNotNull(attributes.group().getName());
        assertNotNull(attributes.permissions());
        assertFalse(attributes.isDirectory());
        assertTrue(attributes.isRegularFile());
        assertFalse(attributes.isSymbolicLink());
        assertFalse(attributes.isOther());
    }

    @Test
    public void testReadAttributesSymLinkToFileNoFollowLinks() throws IOException {
        Path foo = addFile("/foo");
        setContents(foo, new byte[1024]);
        Path bar = addSymLink("/bar", foo);

        PosixFileAttributes attributes = fileSystem.readAttributes(createPath("/bar"), LinkOption.NOFOLLOW_LINKS);

        long sizeOfFoo = Files.readAttributes(foo, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).size();
        long sizeOfBar = Files.readAttributes(bar, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).size();

        assertEquals(sizeOfBar, attributes.size());
        assertNotEquals(sizeOfFoo, attributes.size());
        assertNotNull(attributes.owner().getName());
        assertNotNull(attributes.group().getName());
        assertNotNull(attributes.permissions());
        assertFalse(attributes.isDirectory());
        assertFalse(attributes.isRegularFile());
        assertTrue(attributes.isSymbolicLink());
        assertFalse(attributes.isOther());
    }

    @Test
    public void testReadAttributesSymLinkToDirectoryFollowLinks() throws IOException {
        Path foo = addDirectory("/foo");
        addSymLink("/bar", foo);

        PosixFileAttributes attributes = fileSystem.readAttributes(createPath("/bar"));

        long sizeOfFoo = Files.readAttributes(foo, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).size();

        // on Windows, foo and bar have the same sizes
        assertEquals(sizeOfFoo, attributes.size());
        assertNotNull(attributes.owner().getName());
        assertNotNull(attributes.group().getName());
        assertNotNull(attributes.permissions());
        assertTrue(attributes.isDirectory());
        assertFalse(attributes.isRegularFile());
        assertFalse(attributes.isSymbolicLink());
        assertFalse(attributes.isOther());
    }

    @Test
    public void testReadAttributesSymLinkToDirectoryNoFollowLinks() throws IOException {
        Path foo = addDirectory("/foo");
        Path bar = addSymLink("/bar", foo);

        PosixFileAttributes attributes = fileSystem.readAttributes(createPath("/bar"), LinkOption.NOFOLLOW_LINKS);

        long sizeOfBar = Files.readAttributes(bar, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS).size();

        // on Windows, foo and bar have the same sizes
        assertEquals(sizeOfBar, attributes.size());
        assertNotNull(attributes.owner().getName());
        assertNotNull(attributes.group().getName());
        assertNotNull(attributes.permissions());
        assertFalse(attributes.isDirectory());
        assertFalse(attributes.isRegularFile());
        assertTrue(attributes.isSymbolicLink());
        assertFalse(attributes.isOther());
    }

    @Test
    public void testReadAttributesNonExisting() {
        NoSuchFileException exception = assertThrows(NoSuchFileException.class, () -> fileSystem.readAttributes(createPath("/foo")));
        assertEquals("/foo", exception.getFile());
    }

    // SFTPFileSystem.readAttributes (map variant)

    @Test
    public void testReadAttributesMapNoTypeLastModifiedTime() throws IOException {
        addDirectory("/foo");

        Map<String, Object> attributes = fileSystem.readAttributes(createPath("/foo"), "lastModifiedTime");
        assertEquals(Collections.singleton("basic:lastModifiedTime"), attributes.keySet());
        assertNotNull(attributes.get("basic:lastModifiedTime"));
    }

    @Test
    public void testReadAttributesMapNoTypeLastAccessTime() throws IOException {
        addDirectory("/foo");

        Map<String, Object> attributes = fileSystem.readAttributes(createPath("/foo"), "lastAccessTime");
        assertEquals(Collections.singleton("basic:lastAccessTime"), attributes.keySet());
        assertNotNull(attributes.get("basic:lastAccessTime"));
    }

    @Test
    public void testReadAttributesMapNoTypeCreateTime() throws IOException {
        addDirectory("/foo");

        Map<String, Object> attributes = fileSystem.readAttributes(createPath("/foo"), "creationTime");
        assertEquals(Collections.singleton("basic:creationTime"), attributes.keySet());
        assertNotNull(attributes.get("basic:creationTime"));
    }

    @Test
    public void testReadAttributesMapNoTypeBasicSize() throws IOException {
        Path foo = addFile("/foo");
        setContents(foo, new byte[1024]);

        Map<String, Object> attributes = fileSystem.readAttributes(createPath("/foo"), "size");
        Map<String, ?> expected = Collections.singletonMap("basic:size", Files.size(foo));
        assertEquals(expected, attributes);
    }

    @Test
    public void testReadAttributesMapNoTypeIsRegularFile() throws IOException {
        addDirectory("/foo");

        Map<String, Object> attributes = fileSystem.readAttributes(createPath("/foo"), "isRegularFile");
        Map<String, ?> expected = Collections.singletonMap("basic:isRegularFile", false);
        assertEquals(expected, attributes);
    }

    @Test
    public void testReadAttributesMapNoTypeIsDirectory() throws IOException {
        addDirectory("/foo");

        Map<String, Object> attributes = fileSystem.readAttributes(createPath("/foo"), "isDirectory");
        Map<String, ?> expected = Collections.singletonMap("basic:isDirectory", true);
        assertEquals(expected, attributes);
    }

    @Test
    public void testReadAttributesMapNoTypeIsSymbolicLink() throws IOException {
        addDirectory("/foo");

        Map<String, Object> attributes = fileSystem.readAttributes(createPath("/foo"), "isSymbolicLink");
        Map<String, ?> expected = Collections.singletonMap("basic:isSymbolicLink", false);
        assertEquals(expected, attributes);
    }

    @Test
    public void testReadAttributesMapNoTypeIsOther() throws IOException {
        addDirectory("/foo");

        Map<String, Object> attributes = fileSystem.readAttributes(createPath("/foo"), "isOther");
        Map<String, ?> expected = Collections.singletonMap("basic:isOther", false);
        assertEquals(expected, attributes);
    }

    @Test
    public void testReadAttributesMapNoTypeFileKey() throws IOException {
        addDirectory("/foo");

        Map<String, Object> attributes = fileSystem.readAttributes(createPath("/foo"), "fileKey");
        Map<String, ?> expected = Collections.singletonMap("basic:fileKey", null);
        assertEquals(expected, attributes);
    }

    @Test
    public void testReadAttributesMapNoTypeMultiple() throws IOException {
        Path foo = addDirectory("/foo");

        Map<String, Object> attributes = fileSystem.readAttributes(createPath("/foo"), "size,isDirectory");
        Map<String, Object> expected = new HashMap<>();
        expected.put("basic:size", Files.size(foo));
        expected.put("basic:isDirectory", true);
        assertEquals(expected, attributes);
    }

    @Test
    public void testReadAttributesMapNoTypeAll() throws IOException {
        Path foo = addDirectory("/foo");

        Map<String, Object> attributes = fileSystem.readAttributes(createPath("/foo"), "*");
        Map<String, Object> expected = new HashMap<>();
        expected.put("basic:size", Files.size(foo));
        expected.put("basic:isRegularFile", false);
        expected.put("basic:isDirectory", true);
        expected.put("basic:isSymbolicLink", false);
        expected.put("basic:isOther", false);
        expected.put("basic:fileKey", null);

        assertNotNull(attributes.remove("basic:lastModifiedTime"));
        assertNotNull(attributes.remove("basic:lastAccessTime"));
        assertNotNull(attributes.remove("basic:creationTime"));
        assertEquals(expected, attributes);

        attributes = fileSystem.readAttributes(createPath("/foo"), "basic:lastModifiedTime,*");
        assertNotNull(attributes.remove("basic:lastModifiedTime"));
        assertNotNull(attributes.remove("basic:lastAccessTime"));
        assertNotNull(attributes.remove("basic:creationTime"));
        assertEquals(expected, attributes);
    }

    @Test
    public void testReadAttributesMapBasicLastModifiedTime() throws IOException {
        addDirectory("/foo");

        Map<String, Object> attributes = fileSystem.readAttributes(createPath("/foo"), "basic:lastModifiedTime");
        assertEquals(Collections.singleton("basic:lastModifiedTime"), attributes.keySet());
        assertNotNull(attributes.get("basic:lastModifiedTime"));
    }

    @Test
    public void testReadAttributesMapBasicLastAccessTime() throws IOException {
        addDirectory("/foo");

        Map<String, Object> attributes = fileSystem.readAttributes(createPath("/foo"), "basic:lastAccessTime");
        assertEquals(Collections.singleton("basic:lastAccessTime"), attributes.keySet());
        assertNotNull(attributes.get("basic:lastAccessTime"));
    }

    @Test
    public void testReadAttributesMapBasicCreateTime() throws IOException {
        addDirectory("/foo");

        Map<String, Object> attributes = fileSystem.readAttributes(createPath("/foo"), "basic:creationTime");
        assertEquals(Collections.singleton("basic:creationTime"), attributes.keySet());
        assertNotNull(attributes.get("basic:creationTime"));
    }

    @Test
    public void testReadAttributesMapBasicSize() throws IOException {
        Path foo = addFile("/foo");
        setContents(foo, new byte[1024]);

        Map<String, Object> attributes = fileSystem.readAttributes(createPath("/foo"), "basic:size");
        Map<String, ?> expected = Collections.singletonMap("basic:size", Files.size(foo));
        assertEquals(expected, attributes);
    }

    @Test
    public void testReadAttributesMapBasicIsRegularFile() throws IOException {
        addDirectory("/foo");

        Map<String, Object> attributes = fileSystem.readAttributes(createPath("/foo"), "basic:isRegularFile");
        Map<String, ?> expected = Collections.singletonMap("basic:isRegularFile", false);
        assertEquals(expected, attributes);
    }

    @Test
    public void testReadAttributesMapBasicIsDirectory() throws IOException {
        addDirectory("/foo");

        Map<String, Object> attributes = fileSystem.readAttributes(createPath("/foo"), "basic:isDirectory");
        Map<String, ?> expected = Collections.singletonMap("basic:isDirectory", true);
        assertEquals(expected, attributes);
    }

    @Test
    public void testReadAttributesMapBasicIsSymbolicLink() throws IOException {
        addDirectory("/foo");

        Map<String, Object> attributes = fileSystem.readAttributes(createPath("/foo"), "basic:isSymbolicLink");
        Map<String, ?> expected = Collections.singletonMap("basic:isSymbolicLink", false);
        assertEquals(expected, attributes);
    }

    @Test
    public void testReadAttributesMapBasicIsOther() throws IOException {
        addDirectory("/foo");

        Map<String, Object> attributes = fileSystem.readAttributes(createPath("/foo"), "basic:isOther");
        Map<String, ?> expected = Collections.singletonMap("basic:isOther", false);
        assertEquals(expected, attributes);
    }

    @Test
    public void testReadAttributesMapBasicFileKey() throws IOException {
        addDirectory("/foo");

        Map<String, Object> attributes = fileSystem.readAttributes(createPath("/foo"), "basic:fileKey");
        Map<String, ?> expected = Collections.singletonMap("basic:fileKey", null);
        assertEquals(expected, attributes);
    }

    @Test
    public void testReadAttributesMapBasicMultiple() throws IOException {
        Path foo = addDirectory("/foo");

        Map<String, Object> attributes = fileSystem.readAttributes(createPath("/foo"), "basic:size,isDirectory");
        Map<String, Object> expected = new HashMap<>();
        expected.put("basic:size", Files.size(foo));
        expected.put("basic:isDirectory", true);
        assertEquals(expected, attributes);
    }

    @Test
    public void testReadAttributesMapBasicAll() throws IOException {
        Path foo = addDirectory("/foo");

        Map<String, Object> attributes = fileSystem.readAttributes(createPath("/foo"), "basic:*");
        Map<String, Object> expected = new HashMap<>();
        expected.put("basic:size", Files.size(foo));
        expected.put("basic:isRegularFile", false);
        expected.put("basic:isDirectory", true);
        expected.put("basic:isSymbolicLink", false);
        expected.put("basic:isOther", false);
        expected.put("basic:fileKey", null);

        assertNotNull(attributes.remove("basic:lastModifiedTime"));
        assertNotNull(attributes.remove("basic:lastAccessTime"));
        assertNotNull(attributes.remove("basic:creationTime"));
        assertEquals(expected, attributes);

        attributes = fileSystem.readAttributes(createPath("/foo"), "basic:lastModifiedTime,*");
        assertNotNull(attributes.remove("basic:lastModifiedTime"));
        assertNotNull(attributes.remove("basic:lastAccessTime"));
        assertNotNull(attributes.remove("basic:creationTime"));
        assertEquals(expected, attributes);
    }

    @Test
    public void testReadAttributesMapPosixLastModifiedTime() throws IOException {
        addDirectory("/foo");

        Map<String, Object> attributes = fileSystem.readAttributes(createPath("/foo"), "posix:lastModifiedTime");
        assertEquals(Collections.singleton("posix:lastModifiedTime"), attributes.keySet());
        assertNotNull(attributes.get("posix:lastModifiedTime"));
    }

    @Test
    public void testReadAttributesMapPosixLastAccessTime() throws IOException {
        addDirectory("/foo");

        Map<String, Object> attributes = fileSystem.readAttributes(createPath("/foo"), "posix:lastAccessTime");
        assertEquals(Collections.singleton("posix:lastAccessTime"), attributes.keySet());
        assertNotNull(attributes.get("posix:lastAccessTime"));
    }

    @Test
    public void testReadAttributesMapPosixCreateTime() throws IOException {
        addDirectory("/foo");

        Map<String, Object> attributes = fileSystem.readAttributes(createPath("/foo"), "posix:creationTime");
        assertEquals(Collections.singleton("posix:creationTime"), attributes.keySet());
        assertNotNull(attributes.get("posix:creationTime"));
    }

    @Test
    public void testReadAttributesMapPosixSize() throws IOException {
        Path foo = addFile("/foo");
        setContents(foo, new byte[1024]);

        Map<String, Object> attributes = fileSystem.readAttributes(createPath("/foo"), "posix:size");
        Map<String, ?> expected = Collections.singletonMap("posix:size", Files.size(foo));
        assertEquals(expected, attributes);
    }

    @Test
    public void testReadAttributesMapPosixIsRegularFile() throws IOException {
        addDirectory("/foo");

        Map<String, Object> attributes = fileSystem.readAttributes(createPath("/foo"), "posix:isRegularFile");
        Map<String, ?> expected = Collections.singletonMap("posix:isRegularFile", false);
        assertEquals(expected, attributes);
    }

    @Test
    public void testReadAttributesMapPosixIsDirectory() throws IOException {
        addDirectory("/foo");

        Map<String, Object> attributes = fileSystem.readAttributes(createPath("/foo"), "posix:isDirectory");
        Map<String, ?> expected = Collections.singletonMap("posix:isDirectory", true);
        assertEquals(expected, attributes);
    }

    @Test
    public void testReadAttributesMapPosixIsSymbolicLink() throws IOException {
        addDirectory("/foo");

        Map<String, Object> attributes = fileSystem.readAttributes(createPath("/foo"), "posix:isSymbolicLink");
        Map<String, ?> expected = Collections.singletonMap("posix:isSymbolicLink", false);
        assertEquals(expected, attributes);
    }

    @Test
    public void testReadAttributesMapPosixIsOther() throws IOException {
        addDirectory("/foo");

        Map<String, Object> attributes = fileSystem.readAttributes(createPath("/foo"), "posix:isOther");
        Map<String, ?> expected = Collections.singletonMap("posix:isOther", false);
        assertEquals(expected, attributes);
    }

    @Test
    public void testReadAttributesMapPosixFileKey() throws IOException {
        addDirectory("/foo");

        Map<String, Object> attributes = fileSystem.readAttributes(createPath("/foo"), "posix:fileKey");
        Map<String, ?> expected = Collections.singletonMap("posix:fileKey", null);
        assertEquals(expected, attributes);
    }

    @Test
    public void testReadAttributesMapOwnerOwner() throws IOException {
        addDirectory("/foo");

        Map<String, Object> attributes = fileSystem.readAttributes(createPath("/foo"), "owner:owner");
        assertEquals(Collections.singleton("owner:owner"), attributes.keySet());
        assertNotNull(attributes.get("owner:owner"));
    }

    @Test
    public void testReadAttributesMapOwnerAll() throws IOException {
        addDirectory("/foo");

        Map<String, Object> attributes = fileSystem.readAttributes(createPath("/foo"), "owner:*");
        assertEquals(Collections.singleton("owner:owner"), attributes.keySet());
        assertNotNull(attributes.get("owner:owner"));

        attributes = fileSystem.readAttributes(createPath("/foo"), "owner:owner,*");
        assertEquals(Collections.singleton("owner:owner"), attributes.keySet());
        assertNotNull(attributes.get("owner:owner"));
    }

    @Test
    public void testReadAttributesMapPosixOwner() throws IOException {
        addDirectory("/foo");

        Map<String, Object> attributes = fileSystem.readAttributes(createPath("/foo"), "posix:owner");
        assertEquals(Collections.singleton("posix:owner"), attributes.keySet());
        assertNotNull(attributes.get("posix:owner"));
    }

    @Test
    public void testReadAttributesMapPosixGroup() throws IOException {
        addDirectory("/foo");

        Map<String, Object> attributes = fileSystem.readAttributes(createPath("/foo"), "posix:group");
        assertEquals(Collections.singleton("posix:group"), attributes.keySet());
        assertNotNull(attributes.get("posix:group"));
    }

    @Test
    public void testReadAttributesMapPosixPermissions() throws IOException {
        addDirectory("/foo");

        Map<String, Object> attributes = fileSystem.readAttributes(createPath("/foo"), "posix:permissions");
        assertEquals(Collections.singleton("posix:permissions"), attributes.keySet());
        assertNotNull(attributes.get("posix:permissions"));
    }

    @Test
    public void testReadAttributesMapPosixMultiple() throws IOException {
        Path foo = addDirectory("/foo");

        Map<String, Object> attributes = fileSystem.readAttributes(createPath("/foo"), "posix:size,owner,group");
        Map<String, ?> expected = Collections.singletonMap("posix:size", Files.size(foo));
        assertNotNull(attributes.remove("posix:owner"));
        assertNotNull(attributes.remove("posix:group"));
        assertEquals(expected, attributes);
    }

    @Test
    public void testReadAttributesMapPosixAll() throws IOException {
        Path foo = addDirectory("/foo");

        Map<String, Object> attributes = fileSystem.readAttributes(createPath("/foo"), "posix:*");
        Map<String, Object> expected = new HashMap<>();
        expected.put("posix:size", Files.size(foo));
        expected.put("posix:isRegularFile", false);
        expected.put("posix:isDirectory", true);
        expected.put("posix:isSymbolicLink", false);
        expected.put("posix:isOther", false);
        expected.put("posix:fileKey", null);

        assertNotNull(attributes.remove("posix:lastModifiedTime"));
        assertNotNull(attributes.remove("posix:lastAccessTime"));
        assertNotNull(attributes.remove("posix:creationTime"));
        assertNotNull(attributes.remove("posix:owner"));
        assertNotNull(attributes.remove("posix:group"));
        assertNotNull(attributes.remove("posix:permissions"));
        assertEquals(expected, attributes);

        attributes = fileSystem.readAttributes(createPath("/foo"), "posix:lastModifiedTime,*");
        assertNotNull(attributes.remove("posix:lastModifiedTime"));
        assertNotNull(attributes.remove("posix:lastAccessTime"));
        assertNotNull(attributes.remove("posix:creationTime"));
        assertNotNull(attributes.remove("posix:owner"));
        assertNotNull(attributes.remove("posix:group"));
        assertNotNull(attributes.remove("posix:permissions"));
        assertEquals(expected, attributes);
    }

    @Test
    public void testReadAttributesMapUnsupportedAttribute() throws IOException {
        addDirectory("/foo");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> fileSystem.readAttributes(createPath("/foo"), "posix:lastModifiedTime,owner,dummy"));
        assertEquals(Messages.fileSystemProvider().unsupportedFileAttribute("dummy").getMessage(), exception.getMessage());
    }

    @Test
    public void testReadAttributesMapUnsupportedType() throws IOException {
        addDirectory("/foo");

        UnsupportedOperationException exception = assertThrows(UnsupportedOperationException.class,
                () -> fileSystem.readAttributes(createPath("/foo"), "zipfs:*"));
        assertEquals(Messages.fileSystemProvider().unsupportedFileAttributeView("zipfs").getMessage(), exception.getMessage());
    }

    // SFTPFileSystem.setOwner

    // the success flow cannot be properly tested on Windows

    @Test
    public void testSetOwnerNonExisting() {
        FileSystemException exception = assertThrows(FileSystemException.class,
                () -> fileSystem.setOwner(createPath("/foo/bar"), new SimpleUserPrincipal("1")));
        assertEquals("/foo/bar", exception.getFile());

        verify(getExceptionFactory()).createSetOwnerException(eq("/foo/bar"), any(SftpException.class));
    }

    @Test
    public void testSetOwnerNonNumeric() throws IOException {
        addFile("/foo/bar");

        IOException exception = assertThrows(IOException.class, () -> fileSystem.setOwner(createPath("/foo/bar"), new SimpleUserPrincipal("test")));
        assertThat(exception.getCause(), instanceOf(NumberFormatException.class));

        verify(getExceptionFactory(), never()).createSetOwnerException(anyString(), any(SftpException.class));
    }

    // SFTPFileSystem.setGroup

    // the success flow cannot be properly tested on Windows

    @Test
    public void testSetGroupNonExisting() {
        FileSystemException exception = assertThrows(FileSystemException.class,
                () -> fileSystem.setGroup(createPath("/foo/bar"), new SimpleGroupPrincipal("1")));
        assertEquals("/foo/bar", exception.getFile());

        verify(getExceptionFactory()).createSetGroupException(eq("/foo/bar"), any(SftpException.class));
    }

    @Test
    public void testSetGroupNonNumeric() throws IOException {
        addFile("/foo/bar");

        IOException exception = assertThrows(IOException.class, () -> fileSystem.setGroup(createPath("/foo/bar"), new SimpleGroupPrincipal("test")));
        assertThat(exception.getCause(), instanceOf(NumberFormatException.class));

        verify(getExceptionFactory(), never()).createSetOwnerException(anyString(), any(SftpException.class));
    }

    // SFTPFileSystem.setPermissions

    // the success flow cannot be properly tested on Windows

    @Test
    public void testSetPermissionsNonExisting() {
        FileSystemException exception = assertThrows(FileSystemException.class,
                () -> fileSystem.setPermissions(createPath("/foo/bar"), PosixFilePermissions.fromString("r--r--r--")));
        assertEquals("/foo/bar", exception.getFile());

        verify(getExceptionFactory()).createSetPermissionsException(eq("/foo/bar"), any(SftpException.class));
    }

    // SFTPFileSystem.setLastModifiedTime

    @Test
    public void testSetLastModifiedTimeExisting() throws IOException {
        addFile("/foo/bar");

        SFTPPath path = createPath("/foo/bar");
        // times are in seconds
        FileTime lastModifiedTime = FileTime.from(123456789L, TimeUnit.SECONDS);
        fileSystem.setLastModifiedTime(path, lastModifiedTime, false);
        assertEquals(lastModifiedTime, fileSystem.readAttributes(path).lastModifiedTime());
    }

    @Test
    public void testSetLastModifiedTimeNonExisting() {
        FileSystemException exception = assertThrows(FileSystemException.class,
                () -> fileSystem.setLastModifiedTime(createPath("/foo/bar"), FileTime.from(123456789L, TimeUnit.SECONDS), false));
        assertEquals("/foo/bar", exception.getFile());

        verify(getExceptionFactory()).createSetModificationTimeException(eq("/foo/bar"), any(SftpException.class));
    }

    // cannot test followLinks true/false on Windows because symbolic links have the same time as their targets

    // SFTPFileSystem.setAttribute

    @Test
    public void testSetAttributeLastModifiedTime() throws IOException {
        Path foo = addDirectory("/foo");

        SFTPPath fooPath = createPath("/foo");
        FileTime lastModifiedTime = FileTime.from(123456L, TimeUnit.SECONDS);

        fileSystem.setAttribute(fooPath, "basic:lastModifiedTime", lastModifiedTime);
        assertEquals(lastModifiedTime, Files.getLastModifiedTime(foo));

        lastModifiedTime = FileTime.from(1234567L, TimeUnit.SECONDS);

        fileSystem.setAttribute(fooPath, "posix:lastModifiedTime", lastModifiedTime);
        assertEquals(lastModifiedTime, Files.getLastModifiedTime(foo));

        lastModifiedTime = FileTime.from(12345678L, TimeUnit.SECONDS);

        fileSystem.setAttribute(fooPath, "lastModifiedTime", lastModifiedTime);
        assertEquals(lastModifiedTime, Files.getLastModifiedTime(foo));
    }

    @Test
    public void testSetAttributeUnsupportedAttribute() throws IOException {
        addDirectory("/foo");

        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
                () -> fileSystem.setAttribute(createPath("/foo"), "basic:creationTime", true));
        assertEquals(Messages.fileSystemProvider().unsupportedFileAttribute("basic:creationTime").getMessage(), exception.getMessage());
    }

    @Test
    public void testSetAttributeUnsupportedType() throws IOException {
        addDirectory("/foo");

        UnsupportedOperationException exception = assertThrows(UnsupportedOperationException.class,
                () -> fileSystem.setAttribute(createPath("/foo"), "zipfs:size", true));
        assertEquals(Messages.fileSystemProvider().unsupportedFileAttributeView("zipfs").getMessage(), exception.getMessage());
    }

    @Test
    public void testSetAttributeInvalidValueType() throws IOException {
        addDirectory("/foo");

        assertThrows(ClassCastException.class, () -> fileSystem.setAttribute(createPath("/foo"), "basic:lastModifiedTime", 1));
    }

    // SFTPFileSystem.getTotalSpace

    @Test
    public void testGetTotalSpace() throws IOException {
        // SshServer does not support statVFS
        assertEquals(Long.MAX_VALUE, fileSystem.getTotalSpace(createPath("/")));
    }

    // SFTPFileSystem.getUsableSpace

    @Test
    public void testGetUsableSpace() throws IOException {
        // SshServer does not support statVFS
        assertEquals(Long.MAX_VALUE, fileSystem.getUsableSpace(createPath("/")));
    }

    // SFTPFileSystem.getUnallocatedSpace

    @Test
    public void testGetUnallocatedSpace() throws IOException {
        // SshServer does not support statVFS
        assertEquals(Long.MAX_VALUE, fileSystem.getUnallocatedSpace(createPath("/")));
    }
}
