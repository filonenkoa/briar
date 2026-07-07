package org.briarproject.briar.filetransfer;

import org.briarproject.bramble.api.UniqueId;
import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.client.ContactGroupFactory;
import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.bramble.api.data.BdfEntry;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.data.MetadataParser;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DatabaseConfig;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.api.versioning.ClientVersioningManager;
import org.briarproject.bramble.test.BrambleMockTestCase;
import org.briarproject.briar.api.client.MessageTracker;
import org.briarproject.briar.api.conversation.ConversationManager;
import org.jmock.Expectations;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.briarproject.bramble.test.TestUtils.deleteTestDirectory;
import static org.briarproject.bramble.test.TestUtils.getTestDirectory;
import static org.briarproject.bramble.test.TestUtils.getRandomId;
import static org.briarproject.bramble.test.TestUtils.readBytes;
import static org.briarproject.bramble.test.TestUtils.writeBytes;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_KEY_CHUNK_INDEX;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_KEY_CHUNK_TOTAL;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_KEY_CHUNKS_RECEIVED;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_KEY_FILE_ID;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_KEY_MSG_TYPE;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_TYPE_CHUNK;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_TYPE_HEADER;
import static org.junit.Assert.assertArrayEquals;
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
		context.checking(new Expectations() {{
			allowing(databaseConfig).getDatabaseDirectory();
			will(returnValue(testDir));
		}});
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
		File chunk = getChunkFile(fileDir, 0);
		assertTrue(chunk.getParentFile().exists() ||
				chunk.getParentFile().mkdirs());
		writeBytes(chunk, new byte[] {1, 2, 3});
		writeBytes(getChunkTotalFile(fileDir, 0), new byte[] {'2'});

		assembleFile(fileDir, "file.bin", 2);

		assertFalse(getAssembledFile(fileDir, "file.bin").exists());
	}

	@Test
	public void testAssembledFileDoesNotCollideWithChunkFile()
			throws Exception {
		File fileDir = new File(testDir, "collision");
		assertTrue(fileDir.mkdirs());

		File chunk = getChunkFile(fileDir, 0);
		File assembled = getAssembledFile(fileDir, "chunk_0");

		assertFalse(chunk.equals(assembled));
		assertEquals("chunks", chunk.getParentFile().getName());
		assertEquals("assembled", assembled.getParentFile().getName());
	}

	@Test
	public void testCountExistingChunksIgnoresWrongChunkTotal()
			throws Exception {
		File fileDir = new File(testDir, "totals");
		assertTrue(fileDir.mkdirs());
		File chunk = getChunkFile(fileDir, 0);
		assertTrue(chunk.getParentFile().exists() ||
				chunk.getParentFile().mkdirs());
		writeBytes(chunk, new byte[] {1, 2, 3});
		writeBytes(new File(chunk.getParentFile(), chunk.getName() + ".total"),
				new byte[] {'3'});

		assertEquals(0, countExistingChunks(fileDir, 2));
		assertEquals(1, countExistingChunks(fileDir, 3));
	}

	@Test
	public void testDuplicateChunkDoesNotIncreaseExistingChunkCount()
			throws Exception {
		File fileDir = new File(testDir, "duplicate-count");
		assertTrue(fileDir.mkdirs());

		writeChunk(fileDir, 0, 2, new byte[] {1, 2, 3});
		writeChunk(fileDir, 0, 2, new byte[] {1, 2, 3});

		assertEquals(1, countExistingChunks(fileDir, 2));
	}

	@Test
	public void testDuplicateIncomingChunkRepairsReceivedCount()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		GroupId groupId = new GroupId(getRandomId());
		UniqueId fileId = new UniqueId(getRandomId());
		MessageId chunkMessageId = new MessageId(getRandomId());
		MessageId headerMessageId = new MessageId(getRandomId());
		Message chunkMessage = new Message(chunkMessageId, groupId, 1,
				new byte[] {1});
		File fileDir = getFileDir(fileId);
		writeChunk(fileDir, 0, 2, new byte[] {1, 2, 3});

		BdfDictionary header = new BdfDictionary();
		header.put(MSG_KEY_CHUNK_TOTAL, 2);
		header.put(MSG_KEY_CHUNKS_RECEIVED, 0);
		Map<MessageId, BdfDictionary> headers = new HashMap<>();
		headers.put(headerMessageId, header);
		BdfDictionary repair = BdfDictionary.of(new BdfEntry(
				MSG_KEY_CHUNKS_RECEIVED, 1));
		BdfDictionary meta = chunkMetadata(fileId, 0, 2);

		context.checking(new Expectations() {{
			oneOf(clientHelper).getMessageMetadataAsDictionary(
					with(same(txn)), with(equal(groupId)),
					with(any(BdfDictionary.class)));
			will(returnValue(headers));
			allowing(clientHelper).getMessageAsList(txn, chunkMessageId);
			will(returnValue(BdfList.of(MSG_TYPE_CHUNK, fileId.getBytes(), 0, 2,
					new byte[] {9})));
			oneOf(clientHelper).mergeMessageMetadata(txn, headerMessageId,
					repair);
		}});

		incomingChunk(txn, chunkMessage, meta);
	}

	@Test
	public void testChunkWithWrongTotalDoesNotOverwriteExistingChunk()
			throws Exception {
		File fileDir = new File(testDir, "wrong-total-overwrite");

		writeChunk(fileDir, 0, 3, new byte[] {1, 2, 3});
		writeChunk(fileDir, 0, 2, new byte[] {9});

		assertArrayEquals(new byte[] {1, 2, 3},
				readBytes(getChunkFile(fileDir, 0)));
		assertEquals(1, countExistingChunks(fileDir, 3));
		assertEquals(0, countExistingChunks(fileDir, 2));
	}

	@Test
	public void testOrphanChunkWithoutTotalIsRecoverable()
			throws Exception {
		File fileDir = new File(testDir, "orphan");
		File chunk = getChunkFile(fileDir, 0);
		assertTrue(chunk.getParentFile().exists() ||
				chunk.getParentFile().mkdirs());
		writeBytes(chunk, new byte[] {9});
		assertFalse(getChunkTotalFile(fileDir, 0).exists());

		writeChunk(fileDir, 0, 2, new byte[] {1, 2, 3});

		assertArrayEquals(new byte[] {1, 2, 3}, readBytes(chunk));
		assertArrayEquals(new byte[] {'2'}, readBytes(getChunkTotalFile(fileDir,
				0)));
		assertEquals(1, countExistingChunks(fileDir, 2));
	}

	@Test
	public void testZeroByteAssemblyUsesSafeFileName() throws Exception {
		File fileDir = new File(testDir, "zero");
		assertTrue(fileDir.mkdirs());

		assembleFile(fileDir, "../evil.txt", 0);

		File assembled = getAssembledFile(fileDir, "evil.txt");
		assertTrue(assembled.exists());
		assertEquals(0, assembled.length());
		assertFalse(new File(testDir, "evil.txt").exists());
	}

	private File getChunkFile(File fileDir, int chunkIndex) throws Exception {
		Method method = FileTransferManagerImpl.class.getDeclaredMethod(
				"getChunkFile", File.class, int.class);
		method.setAccessible(true);
		return (File) method.invoke(manager, fileDir, chunkIndex);
	}

	private File getChunkTotalFile(File fileDir, int chunkIndex)
			throws Exception {
		Method method = FileTransferManagerImpl.class.getDeclaredMethod(
				"getChunkTotalFile", File.class, int.class);
		method.setAccessible(true);
		return (File) method.invoke(manager, fileDir, chunkIndex);
	}

	private File getFileDir(UniqueId fileId) throws Exception {
		Method method = FileTransferManagerImpl.class.getDeclaredMethod(
				"getFileDir", UniqueId.class);
		method.setAccessible(true);
		return (File) method.invoke(manager, fileId);
	}

	private File getAssembledFile(File fileDir, String fileName)
			throws Exception {
		Method method = FileTransferManagerImpl.class.getDeclaredMethod(
				"getAssembledFile", File.class, String.class);
		method.setAccessible(true);
		return (File) method.invoke(manager, fileDir, fileName);
	}

	private int countExistingChunks(File fileDir, int chunkTotal)
			throws Exception {
		Method method = FileTransferManagerImpl.class.getDeclaredMethod(
				"countExistingChunks", File.class, int.class);
		method.setAccessible(true);
		return (Integer) method.invoke(manager, fileDir, chunkTotal);
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

	private void writeChunk(File fileDir, int chunkIndex, int chunkTotal,
			byte[] payload) throws Exception {
		Method method = FileTransferManagerImpl.class.getDeclaredMethod(
				"writeChunk", File.class, int.class, int.class, byte[].class);
		method.setAccessible(true);
		method.invoke(manager, fileDir, chunkIndex, chunkTotal, payload);
	}

	private void incomingChunk(Transaction txn, Message m, BdfDictionary meta)
			throws Exception {
		Method method = FileTransferManagerImpl.class.getDeclaredMethod(
				"incomingChunk", Transaction.class, Message.class,
				BdfDictionary.class);
		method.setAccessible(true);
		method.invoke(manager, txn, m, meta);
	}

	private BdfDictionary chunkMetadata(UniqueId fileId, int chunkIndex,
			int chunkTotal) {
		BdfDictionary meta = new BdfDictionary();
		meta.put(MSG_KEY_MSG_TYPE, MSG_TYPE_CHUNK);
		meta.put(MSG_KEY_FILE_ID, fileId.getBytes());
		meta.put(MSG_KEY_CHUNK_INDEX, chunkIndex);
		meta.put(MSG_KEY_CHUNK_TOTAL, chunkTotal);
		return meta;
	}
}
