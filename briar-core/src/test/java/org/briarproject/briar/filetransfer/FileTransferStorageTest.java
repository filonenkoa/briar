package org.briarproject.briar.filetransfer;

import org.briarproject.bramble.api.UniqueId;
import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.client.ContactGroupFactory;
import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.bramble.api.data.BdfEntry;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.data.MetadataParser;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DatabaseConfig;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.sync.Group;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.api.versioning.ClientVersioningManager;
import org.briarproject.bramble.test.BrambleMockTestCase;
import org.briarproject.briar.api.client.MessageTracker;
import org.briarproject.briar.api.conversation.ConversationManager;
import org.briarproject.briar.api.filetransfer.FileTransferHeader;
import org.jmock.Expectations;
import org.jmock.Sequence;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.Map;

import static org.briarproject.bramble.test.TestUtils.deleteTestDirectory;
import static org.briarproject.bramble.test.TestUtils.getContact;
import static org.briarproject.bramble.test.TestUtils.getGroup;
import static org.briarproject.bramble.test.TestUtils.getTestDirectory;
import static org.briarproject.bramble.test.TestUtils.getRandomId;
import static org.briarproject.bramble.test.TestUtils.readBytes;
import static org.briarproject.bramble.test.TestUtils.writeBytes;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.CHUNK_SIZE;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.CLIENT_ID;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MAJOR_VERSION;
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
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

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

	@Test
	public void testSendFileTracksHeaderBeforeChunks() throws Exception {
		Transaction txn = new Transaction(null, false);
		Contact contact = getContact();
		Group group = getGroup(CLIENT_ID, MAJOR_VERSION);
		Message headerMessage = new Message(new MessageId(getRandomId()),
				group.getId(), 1, new byte[] {1});
		Message chunkMessage = new Message(new MessageId(getRandomId()),
				group.getId(), 2, new byte[] {2});
		Sequence sequence = context.sequence("send-order");

		expectSendSetup(txn, contact, group);
		context.checking(new Expectations() {{
			oneOf(clientHelper).createMessage(with(equal(group.getId())),
					with(any(Long.class)), with(any(BdfList.class)));
			will(returnValue(headerMessage));
			inSequence(sequence);
			oneOf(clientHelper).addLocalMessage(with(same(txn)),
					with(same(headerMessage)), with(any(BdfDictionary.class)),
					with(true), with(false));
			inSequence(sequence);
			oneOf(conversationManager).trackOutgoingMessage(txn, headerMessage);
			inSequence(sequence);
			oneOf(clientHelper).createMessage(with(equal(group.getId())),
					with(any(Long.class)), with(any(BdfList.class)));
			will(returnValue(chunkMessage));
			inSequence(sequence);
			oneOf(clientHelper).addLocalMessage(with(same(txn)),
					with(same(chunkMessage)), with(any(BdfDictionary.class)),
					with(true), with(false));
			inSequence(sequence);
		}});

		sendFile(txn, contact.getId(), "file.bin", "application/octet-stream",
				1, new ByteArrayInputStream(new byte[] {1}));
	}

	@Test
	public void testSendFileRejectsShortStream() throws Exception {
		Transaction txn = new Transaction(null, false);
		Contact contact = getContact();
		Group group = getGroup(CLIENT_ID, MAJOR_VERSION);
		Message message = new Message(new MessageId(getRandomId()),
				group.getId(), 1, new byte[] {1});

		expectSendSetup(txn, contact, group);
		expectAnyLocalMessages(txn, group, message);

		try {
			sendFile(txn, contact.getId(), "file.bin", "application/octet-stream",
					2, new ByteArrayInputStream(new byte[] {1}));
			fail();
		} catch (IOException expected) {
			assertTrue(expected.getMessage().contains("Expected 2 bytes"));
		}
	}

	@Test
	public void testSendFileDoesNotOverreadFinalChunk() throws Exception {
		Transaction txn = new Transaction(null, false);
		Contact contact = getContact();
		Group group = getGroup(CLIENT_ID, MAJOR_VERSION);
		Message message = new Message(new MessageId(getRandomId()),
				group.getId(), 1, new byte[] {1});
		byte[] bytes = new byte[CHUNK_SIZE + 1];

		expectSendSetup(txn, contact, group);
		expectAnyLocalMessages(txn, group, message);

		FileTransferHeader header = sendFile(txn, contact.getId(), "file.bin",
				"application/octet-stream", bytes.length,
				new StrictLengthInputStream(bytes));

		assertEquals(2, header.getChunkTotal());
	}

	@Test
	public void testSendFileCreatesEmptyAssembledFileForZeroBytes()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		Contact contact = getContact();
		Group group = getGroup(CLIENT_ID, MAJOR_VERSION);
		Message message = new Message(new MessageId(getRandomId()),
				group.getId(), 1, new byte[] {1});

		expectSendSetup(txn, contact, group);
		expectAnyLocalMessages(txn, group, message);

		FileTransferHeader header = sendFile(txn, contact.getId(), "empty.bin",
				"application/octet-stream", 0,
				new ByteArrayInputStream(new byte[0]));

		assertEquals(0, header.getChunkTotal());
		InputStream in = manager.getFile(header);
		assertNotNull(in);
		try (InputStream i = in) {
			assertEquals(-1, i.read());
		}
	}

	private void expectSendSetup(Transaction txn, Contact contact, Group group)
			throws Exception {
		ContactId contactId = contact.getId();
		context.checking(new Expectations() {{
			oneOf(db).getContact(txn, contactId);
			will(returnValue(contact));
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID,
					MAJOR_VERSION, contact);
			will(returnValue(group));
		}});
	}

	private void expectAnyLocalMessages(Transaction txn, Group group,
			Message message) throws Exception {
		context.checking(new Expectations() {{
			allowing(clientHelper).createMessage(with(equal(group.getId())),
					with(any(Long.class)), with(any(BdfList.class)));
			will(returnValue(message));
			allowing(clientHelper).addLocalMessage(with(same(txn)),
					with(same(message)), with(any(BdfDictionary.class)),
					with(true), with(false));
			allowing(conversationManager).trackOutgoingMessage(txn, message);
		}});
	}

	private static class StrictLengthInputStream extends InputStream {

		private final byte[] bytes;
		private int offset = 0;

		private StrictLengthInputStream(byte[] bytes) {
			this.bytes = bytes;
		}

		@Override
		public int read(byte[] b, int off, int len) throws IOException {
			if (offset == bytes.length) return -1;
			int remaining = bytes.length - offset;
			if (len > remaining) throw new IOException("Overread final chunk");
			System.arraycopy(bytes, offset, b, off, len);
			offset += len;
			return len;
		}

		@Override
		public int read() throws IOException {
			if (offset == bytes.length) return -1;
			return bytes[offset++];
		}
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

	private FileTransferHeader sendFile(Transaction txn, ContactId c,
			String fileName, String contentType, long fileSize, InputStream in)
			throws Exception {
		Method method = FileTransferManagerImpl.class.getDeclaredMethod(
				"sendFile", Transaction.class, ContactId.class, String.class,
				String.class, long.class, InputStream.class);
		method.setAccessible(true);
		try {
			return (FileTransferHeader) method.invoke(manager, txn, c, fileName,
					contentType, fileSize, in);
		} catch (InvocationTargetException e) {
			Throwable cause = e.getCause();
			if (cause instanceof Exception) throw (Exception) cause;
			if (cause instanceof Error) throw (Error) cause;
			throw new RuntimeException(cause);
		}
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
