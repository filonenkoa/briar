package org.briarproject.briar.filetransfer;

import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.client.ContactGroupFactory;
import org.briarproject.bramble.api.data.MetadataParser;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DatabaseConfig;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.versioning.ClientVersioningManager;
import org.briarproject.bramble.test.BrambleMockTestCase;
import org.briarproject.briar.api.client.MessageTracker;
import org.briarproject.briar.api.conversation.ConversationManager;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Method;

import static org.briarproject.bramble.test.TestUtils.deleteTestDirectory;
import static org.briarproject.bramble.test.TestUtils.getTestDirectory;
import static org.briarproject.bramble.test.TestUtils.writeBytes;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FileTransferStorageTest extends BrambleMockTestCase {

	private final DatabaseComponent db = context.mock(DatabaseComponent.class);
	private final ClientHelper clientHelper = context.mock(ClientHelper.class);
	private final ClientVersioningManager clientVersioningManager =
			context.mock(ClientVersioningManager.class);
	private final ContactGroupFactory contactGroupFactory =
			context.mock(ContactGroupFactory.class);
	private final MessageTracker messageTracker =
			context.mock(MessageTracker.class);
	private final ConversationManager conversationManager =
			context.mock(ConversationManager.class);
	private final MetadataParser metadataParser =
			context.mock(MetadataParser.class);
	private final EventBus eventBus = context.mock(EventBus.class);
	private final DatabaseConfig databaseConfig =
			context.mock(DatabaseConfig.class);

	private final File testDir = getTestDirectory();
	private FileTransferManagerImpl manager;

	@Before
	public void setUp() {
		manager = new FileTransferManagerImpl(db, clientHelper,
				clientVersioningManager, contactGroupFactory, messageTracker,
				conversationManager, metadataParser, eventBus, databaseConfig);
		assertTrue(testDir.mkdirs());
	}

	@After
	public void tearDown() {
		deleteTestDirectory(testDir);
	}

	@Test
	public void testSafeFileNameStripsPathTraversal() throws Exception {
		assertEquals("evil.txt", safeFileName("../evil.txt"));
		assertEquals("evil.txt", safeFileName("/tmp/evil.txt"));
		assertEquals("file", safeFileName(""));
		assertEquals("file", safeFileName("."));
		assertEquals("file", safeFileName(".."));
	}

	@Test
	public void testAssembleDoesNotCreateOutputWhenChunksMissing()
			throws Exception {
		File fileDir = new File(testDir, "missing");
		assertTrue(fileDir.mkdirs());
		writeBytes(new File(fileDir, "chunk_0"), new byte[] {1, 2, 3});

		assembleFile(fileDir, "file.bin", 2);

		assertFalse(new File(fileDir, "file.bin").exists());
	}

	@Test
	public void testZeroByteAssemblyUsesSafeFileName() throws Exception {
		File fileDir = new File(testDir, "zero");
		assertTrue(fileDir.mkdirs());

		assembleFile(fileDir, "../evil.txt", 0);

		File assembled = new File(fileDir, "evil.txt");
		assertTrue(assembled.exists());
		assertEquals(0, assembled.length());
		assertFalse(new File(testDir, "evil.txt").exists());
	}

	private String safeFileName(String fileName) throws Exception {
		Method method = FileTransferManagerImpl.class.getDeclaredMethod(
				"safeFileName", String.class);
		method.setAccessible(true);
		return (String) method.invoke(manager, fileName);
	}

	private void assembleFile(File fileDir, String fileName, int chunkTotal)
			throws Exception {
		Method method = FileTransferManagerImpl.class.getDeclaredMethod(
				"assembleFile", File.class, String.class, int.class);
		method.setAccessible(true);
		method.invoke(manager, fileDir, fileName, chunkTotal);
	}
}
