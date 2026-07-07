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
import org.briarproject.bramble.api.db.DbCallable;
import org.briarproject.bramble.api.db.CommitAction;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DatabaseConfig;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.EventAction;
import org.briarproject.bramble.api.db.Metadata;
import org.briarproject.bramble.api.db.TaskAction;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.sync.Group;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.api.sync.MessageStatus;
import org.briarproject.bramble.api.sync.validation.IncomingMessageHook.DeliveryAction;
import org.briarproject.bramble.api.versioning.ClientVersioningManager;
import org.briarproject.bramble.test.BrambleMockTestCase;
import org.briarproject.briar.api.client.MessageTracker;
import org.briarproject.briar.api.conversation.ConversationManager;
import org.briarproject.briar.api.conversation.ConversationMessageHeader;
import org.briarproject.briar.api.filetransfer.FileTransferHeader;
import org.briarproject.briar.api.filetransfer.FileTransferProgress;
import org.hamcrest.Description;
import org.jmock.Expectations;
import org.jmock.Sequence;
import org.jmock.api.Action;
import org.jmock.api.Invocation;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayInputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.Map;

import static org.briarproject.bramble.api.client.ContactGroupConstants.GROUP_KEY_CONTACT_ID;
import static org.briarproject.bramble.test.TestUtils.deleteTestDirectory;
import static org.briarproject.bramble.test.TestUtils.getContact;
import static org.briarproject.bramble.test.TestUtils.getGroup;
import static org.briarproject.bramble.test.TestUtils.getTestDirectory;
import static org.briarproject.bramble.test.TestUtils.getRandomId;
import static org.briarproject.bramble.test.TestUtils.readBytes;
import static org.briarproject.bramble.test.TestUtils.writeBytes;
import static org.briarproject.briar.client.MessageTrackerConstants.MSG_KEY_READ;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.CHUNK_SIZE;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.CLIENT_ID;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MAJOR_VERSION;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_KEY_CHUNK_INDEX;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_KEY_CHUNK_TOTAL;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_KEY_CHUNKS_RECEIVED;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_KEY_CONTENT_TYPE;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_KEY_FILE_ID;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_KEY_FILE_NAME;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_KEY_FILE_SIZE;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_KEY_LOCAL;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_KEY_MSG_TYPE;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_KEY_TRANSFER_STATE;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_KEY_TIMESTAMP;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_TYPE_CHUNK;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_TYPE_CONTROL;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_TYPE_HEADER;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.TRANSFER_STATE_CANCELLED_BY_SENDER;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.TRANSFER_STATE_REJECTED_BY_RECEIVER;
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

		assembleFile(fileDir, "file.bin", 2, 3);

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
		MessageId existingChunkMessageId = new MessageId(getRandomId());
		MessageId headerMessageId = new MessageId(getRandomId());
		Message chunkMessage = new Message(chunkMessageId, groupId, 1,
				new byte[] {1});
		File fileDir = getFileDir(fileId);
		writeChunk(fileDir, 0, 2, new byte[] {1, 2, 3});

		BdfDictionary header = headerMetadata(fileId, "file.bin", 4L, 2, 0);
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
			oneOf(clientHelper).getMessageIds(with(same(txn)),
					with(equal(groupId)), with(any(BdfDictionary.class)));
			will(returnValue(Arrays.asList(existingChunkMessageId)));
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
	public void testCorrectChunkReplacesWrongTotalOrphanAfterHeader()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		GroupId groupId = new GroupId(getRandomId());
		UniqueId fileId = new UniqueId(getRandomId());
		MessageId chunkMessageId = new MessageId(getRandomId());
		MessageId headerMessageId = new MessageId(getRandomId());
		Message chunkMessage = new Message(chunkMessageId, groupId, 1,
				new byte[] {1});
		File fileDir = getFileDir(fileId);
		writeChunk(fileDir, 0, 3, new byte[] {9});

		BdfDictionary header = headerMetadata(fileId, "file.bin", 4L, 2, 0);
		Map<MessageId, BdfDictionary> headers = new HashMap<>();
		headers.put(headerMessageId, header);
		BdfDictionary received = BdfDictionary.of(new BdfEntry(
				MSG_KEY_CHUNKS_RECEIVED, 1));
		BdfDictionary meta = chunkMetadata(fileId, 0, 2);

		context.checking(new Expectations() {{
			oneOf(clientHelper).getMessageMetadataAsDictionary(
					with(same(txn)), with(equal(groupId)),
					with(any(BdfDictionary.class)));
			will(returnValue(headers));
			oneOf(clientHelper).getMessageAsList(txn, chunkMessageId);
			will(returnValue(BdfList.of(MSG_TYPE_CHUNK, fileId.getBytes(), 0, 2,
					new byte[] {1, 2})));
			oneOf(clientHelper).mergeMessageMetadata(txn, headerMessageId,
					received);
		}});

		incomingChunk(txn, chunkMessage, meta);

		assertArrayEquals(new byte[] {1, 2}, readBytes(getChunkFile(fileDir, 0)));
		assertEquals(1, countExistingChunks(fileDir, 2));
		assertEquals(0, countExistingChunks(fileDir, 3));
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

		assembleFile(fileDir, "../evil.txt", 0, 0);

		File assembled = getAssembledFile(fileDir, "evil.txt");
		assertTrue(assembled.exists());
		assertEquals(0, assembled.length());
		assertFalse(new File(testDir, "evil.txt").exists());
	}

	@Test
	public void testAssemblyDeletesTempAndDoesNotCompleteIfSizeIsWrong()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		GroupId groupId = new GroupId(getRandomId());
		UniqueId fileId = new UniqueId(getRandomId());
		MessageId chunkMessageId = new MessageId(getRandomId());
		MessageId headerMessageId = new MessageId(getRandomId());
		Message chunkMessage = new Message(chunkMessageId, groupId, 1,
				new byte[] {1});
		File fileDir = getFileDir(fileId);
		writeChunk(fileDir, 0, 2, new byte[] {1, 2});

		BdfDictionary header = headerMetadata(fileId, "file.bin", 5L, 2, 1);
		Map<MessageId, BdfDictionary> headers = new HashMap<>();
		headers.put(headerMessageId, header);
		BdfDictionary repaired = BdfDictionary.of(new BdfEntry(
				MSG_KEY_CHUNKS_RECEIVED, 0));
		BdfDictionary meta = chunkMetadata(fileId, 1, 2);

		context.checking(new Expectations() {{
			oneOf(clientHelper).getMessageMetadataAsDictionary(
					with(same(txn)), with(equal(groupId)),
					with(any(BdfDictionary.class)));
			will(returnValue(headers));
			oneOf(clientHelper).getMessageAsList(txn, chunkMessageId);
			will(returnValue(BdfList.of(MSG_TYPE_CHUNK, fileId.getBytes(), 1, 2,
					new byte[] {3, 4})));
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					headerMessageId);
			will(returnValue(header));
			oneOf(clientHelper).mergeMessageMetadata(txn, headerMessageId,
					repaired);
		}});

		incomingChunk(txn, chunkMessage, meta);

		File assembled = getAssembledFile(fileDir, "file.bin");
		assertFalse(assembled.exists());
		assertFalse(new File(assembled.getParentFile(), "file.bin.tmp").exists());
		assertFalse(getChunkFile(fileDir, 0).exists());
		assertFalse(getChunkTotalFile(fileDir, 0).exists());
		assertFalse(getChunkFile(fileDir, 1).exists());
		assertFalse(getChunkTotalFile(fileDir, 1).exists());
	}

	@Test
	public void testCorrectChunksRecoverAfterFailedAssembly()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		GroupId groupId = new GroupId(getRandomId());
		UniqueId fileId = new UniqueId(getRandomId());
		MessageId chunkMessageId = new MessageId(getRandomId());
		MessageId headerMessageId = new MessageId(getRandomId());
		Message chunkMessage = new Message(chunkMessageId, groupId, 2,
				new byte[] {1});
		File fileDir = getFileDir(fileId);
		writeChunk(fileDir, 0, 2, new byte[] {1, 2});
		writeChunk(fileDir, 1, 2, new byte[] {3, 4});
		assembleFile(fileDir, "file.bin", 2, 5);

		BdfDictionary header = headerMetadata(fileId, "file.bin", 5L, 2, 0);
		Map<MessageId, BdfDictionary> headers = new HashMap<>();
		headers.put(headerMessageId, header);
		BdfDictionary received = BdfDictionary.of(new BdfEntry(
				MSG_KEY_CHUNKS_RECEIVED, 1));
		BdfDictionary meta = chunkMetadata(fileId, 0, 2);

		context.checking(new Expectations() {{
			oneOf(clientHelper).getMessageMetadataAsDictionary(
					with(same(txn)), with(equal(groupId)),
					with(any(BdfDictionary.class)));
			will(returnValue(headers));
			oneOf(clientHelper).getMessageAsList(txn, chunkMessageId);
			will(returnValue(BdfList.of(MSG_TYPE_CHUNK, fileId.getBytes(), 0, 2,
					new byte[] {1, 2, 3})));
			oneOf(clientHelper).mergeMessageMetadata(txn, headerMessageId,
					received);
		}});

		incomingChunk(txn, chunkMessage, meta);

		assertArrayEquals(new byte[] {1, 2, 3}, readBytes(getChunkFile(fileDir, 0)));
		assertEquals(1, countExistingChunks(fileDir, 2));
	}

	@Test
	public void testSuccessfulAssemblyDeletesChunkFiles() throws Exception {
		File fileDir = new File(testDir, "cleanup");
		assertTrue(fileDir.mkdirs());
		writeChunk(fileDir, 0, 2, new byte[] {1, 2});
		writeChunk(fileDir, 1, 2, new byte[] {3, 4});

		assembleFile(fileDir, "file.bin", 2, 4);

		File assembled = getAssembledFile(fileDir, "file.bin");
		assertTrue(assembled.exists());
		assertArrayEquals(new byte[] {1, 2, 3, 4}, readBytes(assembled));
		assertFalse(getChunkFile(fileDir, 0).exists());
		assertFalse(getChunkTotalFile(fileDir, 0).exists());
		assertFalse(getChunkFile(fileDir, 1).exists());
		assertFalse(getChunkTotalFile(fileDir, 1).exists());
	}

	@Test
	public void testDuplicateChunkAfterAssemblyDoesNotRecreateChunk()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		GroupId groupId = new GroupId(getRandomId());
		UniqueId fileId = new UniqueId(getRandomId());
		MessageId chunkMessageId = new MessageId(getRandomId());
		MessageId headerMessageId = new MessageId(getRandomId());
		Message chunkMessage = new Message(chunkMessageId, groupId, 2,
				new byte[] {1});
		File fileDir = getFileDir(fileId);
		writeChunk(fileDir, 0, 1, new byte[] {1, 2, 3});
		assembleFile(fileDir, "file.bin", 1, 3);

		BdfDictionary header = headerMetadata(fileId, "file.bin", 3L, 1, 1);
		Map<MessageId, BdfDictionary> headers = new HashMap<>();
		headers.put(headerMessageId, header);
		BdfDictionary meta = chunkMetadata(fileId, 0, 1);

		context.checking(new Expectations() {{
			oneOf(clientHelper).getMessageMetadataAsDictionary(
					with(same(txn)), with(equal(groupId)),
					with(any(BdfDictionary.class)));
			will(returnValue(headers));
			never(clientHelper).getMessageAsList(txn, chunkMessageId);
			never(clientHelper).mergeMessageMetadata(with(same(txn)),
					with(any(MessageId.class)), with(any(BdfDictionary.class)));
		}});

		incomingChunk(txn, chunkMessage, meta);

		assertFalse(getChunkFile(fileDir, 0).exists());
		assertFalse(getChunkTotalFile(fileDir, 0).exists());
	}

	@Test
	public void testStaleAssemblyTempDoesNotCompleteTransfer()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		GroupId groupId = new GroupId(getRandomId());
		UniqueId fileId = new UniqueId(getRandomId());
		MessageId chunkMessageId = new MessageId(getRandomId());
		MessageId headerMessageId = new MessageId(getRandomId());
		Message chunkMessage = new Message(chunkMessageId, groupId, 1,
				new byte[] {1});
		File fileDir = getFileDir(fileId);
		File tmp = new File(getAssembledFile(fileDir, "file.bin")
				.getParentFile(), "file.bin.tmp");
		assertTrue(tmp.getParentFile().mkdirs());
		writeBytes(tmp, new byte[] {9});

		BdfDictionary header = headerMetadata(fileId, "file.bin", 6L, 2, 0);
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
			oneOf(clientHelper).getMessageAsList(txn, chunkMessageId);
			will(returnValue(BdfList.of(MSG_TYPE_CHUNK, fileId.getBytes(), 0, 2,
					new byte[] {1, 2, 3})));
			oneOf(clientHelper).mergeMessageMetadata(txn, headerMessageId,
					repair);
		}});

		incomingChunk(txn, chunkMessage, meta);

		assertTrue(getChunkFile(fileDir, 0).exists());
		assertTrue(tmp.exists());
	}

	@Test
	public void testAssembledFileRepairsIncompleteMetadata()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		GroupId groupId = new GroupId(getRandomId());
		UniqueId fileId = new UniqueId(getRandomId());
		MessageId chunkMessageId = new MessageId(getRandomId());
		MessageId headerMessageId = new MessageId(getRandomId());
		Message chunkMessage = new Message(chunkMessageId, groupId, 1,
				new byte[] {1});
		File fileDir = getFileDir(fileId);
		File assembled = getAssembledFile(fileDir, "file.bin");
		assertTrue(assembled.getParentFile().mkdirs());
		writeBytes(assembled, new byte[] {1, 2, 3});

		BdfDictionary header = headerMetadata(fileId, "file.bin", 3L, 1, 0);
		Map<MessageId, BdfDictionary> headers = new HashMap<>();
		headers.put(headerMessageId, header);
		BdfDictionary repair = BdfDictionary.of(new BdfEntry(
				MSG_KEY_CHUNKS_RECEIVED, 1));
		BdfDictionary meta = chunkMetadata(fileId, 0, 1);

		context.checking(new Expectations() {{
			oneOf(clientHelper).getMessageMetadataAsDictionary(
					with(same(txn)), with(equal(groupId)),
					with(any(BdfDictionary.class)));
			will(returnValue(headers));
			never(clientHelper).getMessageAsList(txn, chunkMessageId);
			oneOf(clientHelper).mergeMessageMetadata(txn, headerMessageId,
					repair);
		}});

		incomingChunk(txn, chunkMessage, meta);

		assertFalse(getChunkFile(fileDir, 0).exists());
		assertFalse(getChunkTotalFile(fileDir, 0).exists());
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
	public void testSendFileRejectsUnsafeFileNameBeforeCreatingHeader()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		Contact contact = getContact();

		try {
			sendFile(txn, contact.getId(), "../evil.txt",
					"application/octet-stream", 0,
					new ByteArrayInputStream(new byte[0]));
			fail();
		} catch (IOException expected) {
			// Expected.
		}
	}

	@Test
	public void testSendFileRejectsEmptyContentTypeBeforeCreatingHeader()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		Contact contact = getContact();

		try {
			sendFile(txn, contact.getId(), "file.bin", "", 0,
					new ByteArrayInputStream(new byte[0]));
			fail();
		} catch (IOException expected) {
			// Expected.
		}
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
	public void testSendFileCleansUpChunksAfterFailure() throws Exception {
		Transaction txn = new Transaction(null, false);
		Contact contact = getContact();
		Group group = getGroup(CLIENT_ID, MAJOR_VERSION);
		Message message = new Message(new MessageId(getRandomId()),
				group.getId(), 1, new byte[] {1});
		byte[] bytes = new byte[CHUNK_SIZE];

		expectSendSetup(txn, contact, group);
		expectAnyLocalMessages(txn, group, message);

		try {
			sendFile(txn, contact.getId(), "file.bin", "application/octet-stream",
					CHUNK_SIZE + 1L, new ByteArrayInputStream(bytes));
			fail();
		} catch (IOException expected) {
			assertTrue(expected.getMessage().contains(
					"Expected " + (CHUNK_SIZE + 1L) + " bytes"));
		}

		File storageDir = new File(testDir, "filetransfer");
		File[] files = storageDir.listFiles();
		assertTrue(files == null || files.length == 0);
	}

	@Test
	public void testSendFileCleansUpFilesAfterTransactionFailure()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		Contact contact = getContact();
		Group group = getGroup(CLIENT_ID, MAJOR_VERSION);
		Message message = new Message(new MessageId(getRandomId()),
				group.getId(), 1, new byte[] {1});

		expectSendSetup(txn, contact, group);
		expectAnyLocalMessages(txn, group, message);
		context.checking(new Expectations() {{
			oneOf(db).transactionWithResult(with(false),
					with(any(DbCallable.class)));
			will(new Action() {
				@Override
				public Object invoke(Invocation invocation) throws Throwable {
					DbCallable<?, ?> callable =
							(DbCallable<?, ?>) invocation.getParameter(1);
					callable.call(txn);
					throw new DbException();
				}

				@Override
				public void describeTo(Description description) {
					description.appendText("runs transaction then fails");
				}
			});
		}});

		try {
			manager.sendFile(contact.getId(), "file.bin",
					"application/octet-stream", 1,
					new ByteArrayInputStream(new byte[] {1}));
			fail();
		} catch (DbException expected) {
			// Expected after transaction body has written transfer files.
		}

		File storageDir = new File(testDir, "filetransfer");
		File[] files = storageDir.listFiles();
		assertTrue(files == null || files.length == 0);
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

	@Test
	public void testSetReadFlagMergesMessageMetadata() throws Exception {
		Transaction txn = new Transaction(null, false);
		GroupId groupId = new GroupId(getRandomId());
		MessageId messageId = new MessageId(getRandomId());
		BdfDictionary readMeta = BdfDictionary.of(new BdfEntry(MSG_KEY_READ,
				true));

		context.checking(new Expectations() {{
			oneOf(messageTracker).setReadFlag(txn, groupId, messageId, true);
			oneOf(clientHelper).mergeMessageMetadata(txn, messageId, readMeta);
		}});

		manager.setReadFlag(txn, groupId, messageId, true);
	}

	@Test
	public void testGetFileTransferHeaderDefaultsLegacyReadMetadata()
			throws Exception {
		Transaction localTxn = new Transaction(null, true);
		Transaction remoteTxn = new Transaction(null, true);
		Group group = getGroup(CLIENT_ID, MAJOR_VERSION);
		MessageId localMessageId = new MessageId(getRandomId());
		MessageId remoteMessageId = new MessageId(getRandomId());
		Message localMessage = new Message(localMessageId, group.getId(), 1,
				new byte[] {1});
		Message remoteMessage = new Message(remoteMessageId, group.getId(), 2,
				new byte[] {2});
		BdfDictionary localMeta = legacyHeaderMetadata(
				new UniqueId(getRandomId()), true);
		BdfDictionary remoteMeta = legacyHeaderMetadata(
				new UniqueId(getRandomId()), false);

		context.checking(new Expectations() {{
			oneOf(db).transactionWithResult(with(true),
					with(any(DbCallable.class)));
			will(runTransaction(localTxn));
			oneOf(clientHelper).getMessageMetadataAsDictionary(localTxn,
					localMessageId);
			will(returnValue(localMeta));
			oneOf(clientHelper).getMessage(localTxn, localMessageId);
			will(returnValue(localMessage));

			oneOf(db).transactionWithResult(with(true),
					with(any(DbCallable.class)));
			will(runTransaction(remoteTxn));
			oneOf(clientHelper).getMessageMetadataAsDictionary(remoteTxn,
					remoteMessageId);
			will(returnValue(remoteMeta));
			oneOf(clientHelper).getMessage(remoteTxn, remoteMessageId);
			will(returnValue(remoteMessage));
		}});

		FileTransferHeader localHeader =
				manager.getFileTransferHeader(localMessageId);
		FileTransferHeader remoteHeader =
				manager.getFileTransferHeader(remoteMessageId);

		assertTrue(localHeader.isRead());
		assertFalse(remoteHeader.isRead());
	}

	@Test
	public void testOutgoingProgressIsCancelledWhenHeaderCancelled()
			throws Exception {
		Transaction txn = new Transaction(null, true);
		Group group = getGroup(CLIENT_ID, MAJOR_VERSION);
		UniqueId fileId = new UniqueId(getRandomId());
		MessageId headerId = new MessageId(getRandomId());
		FileTransferHeader header = new FileTransferHeader(headerId,
				group.getId(), 1, true, true, false, false, 0, fileId,
				"large.bin", "application/octet-stream", CHUNK_SIZE * 2L, 2);
		BdfDictionary meta = headerMetadata(fileId, "large.bin",
				CHUNK_SIZE * 2L, 2, 0);
		meta.put(MSG_KEY_TRANSFER_STATE, TRANSFER_STATE_CANCELLED_BY_SENDER);

		context.checking(new Expectations() {{
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn, headerId);
			will(returnValue(meta));
			never(clientHelper).getMessageIds(with(same(txn)),
					with(equal(group.getId())), with(any(BdfDictionary.class)));
		}});

		FileTransferProgress p = getOutgoingProgress(txn, header);

		assertEquals(FileTransferProgress.State.CANCELLED, p.getState());
		assertEquals(0, p.getTransferred());
	}

	@Test
	public void testIncomingProgressIsRejectedWhenHeaderRejected()
			throws Exception {
		Transaction txn = new Transaction(null, true);
		Group group = getGroup(CLIENT_ID, MAJOR_VERSION);
		UniqueId fileId = new UniqueId(getRandomId());
		MessageId headerId = new MessageId(getRandomId());
		FileTransferHeader header = new FileTransferHeader(headerId,
				group.getId(), 1, false, false, false, false, 0, fileId,
				"large.bin", "application/octet-stream", CHUNK_SIZE * 2L, 2);
		BdfDictionary meta = headerMetadata(fileId, "large.bin",
				CHUNK_SIZE * 2L, 2, 1);
		meta.put(MSG_KEY_TRANSFER_STATE, TRANSFER_STATE_REJECTED_BY_RECEIVER);
		Map<MessageId, BdfDictionary> headers = new HashMap<>();
		headers.put(headerId, meta);

		context.checking(new Expectations() {{
			oneOf(clientHelper).getMessageMetadataAsDictionary(with(same(txn)),
					with(equal(group.getId())), with(any(BdfDictionary.class)));
			will(returnValue(headers));
		}});

		FileTransferProgress p = getIncomingProgress(txn, header);

		assertEquals(FileTransferProgress.State.REJECTED, p.getState());
		assertEquals(0, p.getTransferred());
	}

	@Test
	public void testIncomingControlSetsHeaderStateAndSchedulesCleanup()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		GroupId groupId = new GroupId(getRandomId());
		UniqueId fileId = new UniqueId(getRandomId());
		MessageId controlId = new MessageId(getRandomId());
		MessageId headerId = new MessageId(getRandomId());
		Message control = new Message(controlId, groupId, 1, new byte[] {1});
		Metadata metadata = new Metadata();
		File fileDir = getFileDir(fileId);
		writeChunk(fileDir, 0, 2, new byte[] {1, 2});
		BdfDictionary controlMeta = new BdfDictionary();
		controlMeta.put(MSG_KEY_MSG_TYPE, MSG_TYPE_CONTROL);
		controlMeta.put(MSG_KEY_FILE_ID, fileId.getBytes());
		controlMeta.put(MSG_KEY_TRANSFER_STATE,
				TRANSFER_STATE_CANCELLED_BY_SENDER);
		Map<MessageId, BdfDictionary> headers = new HashMap<>();
		headers.put(headerId, headerMetadata(fileId, "file.bin", 4L, 2, 1));
		BdfDictionary merge = BdfDictionary.of(new BdfEntry(
				MSG_KEY_TRANSFER_STATE, TRANSFER_STATE_CANCELLED_BY_SENDER));

		context.checking(new Expectations() {{
			oneOf(metadataParser).parse(metadata);
			will(returnValue(controlMeta));
			oneOf(clientHelper).getMessageMetadataAsDictionary(with(same(txn)),
					with(equal(groupId)), with(any(BdfDictionary.class)));
			will(returnValue(headers));
			oneOf(clientHelper).mergeMessageMetadata(txn, headerId, merge);
		}});

		DeliveryAction action = manager.incomingMessage(txn, control, metadata);

		assertEquals(DeliveryAction.ACCEPT_DO_NOT_SHARE, action);
		assertTrue(fileDir.exists());
		runCommitTasks(txn);
		assertFalse(fileDir.exists());
	}

	@Test
	public void testIncomingChunkForTerminalHeaderIsIgnored()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		GroupId groupId = new GroupId(getRandomId());
		UniqueId fileId = new UniqueId(getRandomId());
		MessageId chunkMessageId = new MessageId(getRandomId());
		MessageId headerMessageId = new MessageId(getRandomId());
		Message chunkMessage = new Message(chunkMessageId, groupId, 1,
				new byte[] {1});
		File fileDir = getFileDir(fileId);
		writeChunk(fileDir, 0, 2, new byte[] {1, 2});
		BdfDictionary header = headerMetadata(fileId, "file.bin", 4L, 2, 0);
		header.put(MSG_KEY_TRANSFER_STATE, TRANSFER_STATE_REJECTED_BY_RECEIVER);
		Map<MessageId, BdfDictionary> headers = new HashMap<>();
		headers.put(headerMessageId, header);
		BdfDictionary meta = chunkMetadata(fileId, 1, 2);

		context.checking(new Expectations() {{
			oneOf(clientHelper).getMessageMetadataAsDictionary(
					with(same(txn)), with(equal(groupId)),
					with(any(BdfDictionary.class)));
			will(returnValue(headers));
			never(clientHelper).getMessageAsList(txn, chunkMessageId);
			never(clientHelper).mergeMessageMetadata(with(same(txn)),
					with(any(MessageId.class)), with(any(BdfDictionary.class)));
		}});

		incomingChunk(txn, chunkMessage, meta);

		assertTrue(fileDir.exists());
		runCommitTasks(txn);
		assertFalse(fileDir.exists());
	}

	@Test
	public void testOutgoingProgressCachesChunkIds() throws Exception {
		Transaction txn = new Transaction(null, true);
		Contact contact = getContact();
		Group group = getGroup(CLIENT_ID, MAJOR_VERSION);
		UniqueId fileId = new UniqueId(getRandomId());
		MessageId headerId = new MessageId(getRandomId());
		MessageId chunkId = new MessageId(getRandomId());
		BdfDictionary groupMeta = BdfDictionary.of(new BdfEntry(
				GROUP_KEY_CONTACT_ID, contact.getId().getInt()));
		FileTransferHeader header = new FileTransferHeader(headerId,
				group.getId(), 1, true, true, false, false, 0, fileId,
				"file.bin", "application/octet-stream", 1, 1);
		BdfDictionary headerMeta = headerMetadata(fileId, "file.bin", 1L, 1, 0);

		context.checking(new Expectations() {{
			exactly(2).of(clientHelper).getMessageMetadataAsDictionary(txn,
					headerId);
			will(returnValue(headerMeta));
			allowing(clientHelper).getGroupMetadataAsDictionary(txn,
					group.getId());
			will(returnValue(groupMeta));
			oneOf(clientHelper).getMessageIds(with(same(txn)),
					with(equal(group.getId())), with(any(BdfDictionary.class)));
			will(returnValue(Arrays.asList(chunkId)));
			exactly(2).of(db).getMessageStatus(txn, contact.getId(), chunkId);
			will(returnValue(new MessageStatus(chunkId, contact.getId(), true,
					true)));
		}});

		FileTransferProgress p1 = getOutgoingProgress(txn, header);
		FileTransferProgress p2 = getOutgoingProgress(txn, header);

		assertEquals(FileTransferProgress.State.COMPLETE, p1.getState());
		assertEquals(FileTransferProgress.State.COMPLETE, p2.getState());
	}

	@Test
	public void testGetMessageHeadersDefaultsLegacyReadMetadata()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		Contact contact = getContact();
		Group group = getGroup(CLIENT_ID, MAJOR_VERSION);
		MessageId localMessageId = new MessageId(getRandomId());
		MessageId remoteMessageId = new MessageId(getRandomId());
		Message localMessage = new Message(localMessageId, group.getId(), 1,
				new byte[] {1});
		Message remoteMessage = new Message(remoteMessageId, group.getId(), 2,
				new byte[] {2});
		Map<MessageId, BdfDictionary> metadata = new HashMap<>();
		metadata.put(localMessageId, legacyHeaderMetadata(
				new UniqueId(getRandomId()), true));
		metadata.put(remoteMessageId, legacyHeaderMetadata(
				new UniqueId(getRandomId()), false));
		Collection<MessageStatus> statuses = Arrays.asList(
				new MessageStatus(localMessageId, contact.getId(), true, true),
				new MessageStatus(remoteMessageId, contact.getId(), false, false));

		expectSendSetup(txn, contact, group);
		context.checking(new Expectations() {{
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn, group.getId());
			will(returnValue(metadata));
			oneOf(db).getMessageStatus(txn, contact.getId(), group.getId());
			will(returnValue(statuses));
			oneOf(clientHelper).getMessage(txn, localMessageId);
			will(returnValue(localMessage));
			oneOf(clientHelper).getMessage(txn, remoteMessageId);
			will(returnValue(remoteMessage));
		}});

		Collection<ConversationMessageHeader> headers =
				manager.getMessageHeaders(txn, contact.getId());

		for (ConversationMessageHeader header : headers) {
			if (header.getId().equals(localMessageId)) assertTrue(header.isRead());
			else if (header.getId().equals(remoteMessageId)) {
				assertFalse(header.isRead());
			} else fail();
		}
		assertEquals(2, headers.size());
	}

	@Test
	public void testRecalculateGroupCountDefaultsLegacyReadMetadata()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		GroupId groupId = new GroupId(getRandomId());
		Map<MessageId, BdfDictionary> metadata = new HashMap<>();
		metadata.put(new MessageId(getRandomId()), legacyHeaderMetadata(
				new UniqueId(getRandomId()), true));
		metadata.put(new MessageId(getRandomId()), legacyHeaderMetadata(
				new UniqueId(getRandomId()), false));

		context.checking(new Expectations() {{
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn, groupId);
			will(returnValue(metadata));
			oneOf(messageTracker).resetGroupCount(txn, groupId, 2, 1);
		}});

		recalculateGroupCount(txn, groupId);
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

	private void assembleFile(File fileDir, String fileName, int chunkTotal,
			long expectedSize)
			throws Exception {
		Method method = FileTransferManagerImpl.class.getDeclaredMethod(
				"assembleFile", UniqueId.class, File.class, String.class,
				int.class, long.class);
		method.setAccessible(true);
		method.invoke(manager, new UniqueId(getRandomId()), fileDir, fileName,
				chunkTotal, expectedSize);
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

	private BdfDictionary headerMetadata(UniqueId fileId, String fileName,
			long fileSize, int chunkTotal, int chunksReceived) {
		BdfDictionary meta = new BdfDictionary();
		meta.put(MSG_KEY_MSG_TYPE, MSG_TYPE_HEADER);
		meta.put(MSG_KEY_FILE_ID, fileId.getBytes());
		meta.put(MSG_KEY_FILE_NAME, fileName);
		meta.put(MSG_KEY_CONTENT_TYPE, "application/octet-stream");
		meta.put(MSG_KEY_FILE_SIZE, fileSize);
		meta.put(MSG_KEY_CHUNK_TOTAL, chunkTotal);
		meta.put(MSG_KEY_CHUNKS_RECEIVED, chunksReceived);
		meta.put(MSG_KEY_LOCAL, false);
		meta.put(MSG_KEY_READ, false);
		meta.put(MSG_KEY_TIMESTAMP, 1L);
		return meta;
	}

	private BdfDictionary legacyHeaderMetadata(UniqueId fileId, boolean local) {
		BdfDictionary meta = new BdfDictionary();
		meta.put(MSG_KEY_MSG_TYPE, MSG_TYPE_HEADER);
		meta.put(MSG_KEY_FILE_ID, fileId.getBytes());
		meta.put(MSG_KEY_FILE_NAME, "file.bin");
		meta.put(MSG_KEY_CONTENT_TYPE, "application/octet-stream");
		meta.put(MSG_KEY_FILE_SIZE, 0L);
		meta.put(MSG_KEY_CHUNK_TOTAL, 0);
		meta.put(MSG_KEY_CHUNKS_RECEIVED, 0);
		meta.put(MSG_KEY_LOCAL, local);
		meta.put(MSG_KEY_TIMESTAMP, 1L);
		return meta;
	}

	private Action runTransaction(Transaction txn) {
		return new Action() {
			@Override
			public Object invoke(Invocation invocation) throws Throwable {
				DbCallable<?, ?> callable =
						(DbCallable<?, ?>) invocation.getParameter(1);
				return callable.call(txn);
			}

			@Override
			public void describeTo(Description description) {
				description.appendText("runs transaction");
			}
		};
	}

	private void recalculateGroupCount(Transaction txn, GroupId groupId)
			throws Exception {
		Method method = FileTransferManagerImpl.class.getDeclaredMethod(
				"recalculateGroupCount", Transaction.class, GroupId.class);
		method.setAccessible(true);
		method.invoke(manager, txn, groupId);
	}

	private FileTransferProgress getOutgoingProgress(Transaction txn,
			FileTransferHeader header) throws Exception {
		Method method = FileTransferManagerImpl.class.getDeclaredMethod(
				"getOutgoingProgress", Transaction.class,
				FileTransferHeader.class);
		method.setAccessible(true);
		return (FileTransferProgress) method.invoke(manager, txn, header);
	}

	private FileTransferProgress getIncomingProgress(Transaction txn,
			FileTransferHeader header) throws Exception {
		Method method = FileTransferManagerImpl.class.getDeclaredMethod(
				"getIncomingProgress", Transaction.class,
				FileTransferHeader.class);
		method.setAccessible(true);
		return (FileTransferProgress) method.invoke(manager, txn, header);
	}

	private void runCommitTasks(Transaction txn) {
		for (CommitAction action : txn.getActions()) {
			action.accept(new CommitAction.Visitor() {
				@Override
				public void visit(EventAction a) {
					throw new AssertionError();
				}

				@Override
				public void visit(TaskAction a) {
					a.getTask().run();
				}
			});
		}
	}
}
