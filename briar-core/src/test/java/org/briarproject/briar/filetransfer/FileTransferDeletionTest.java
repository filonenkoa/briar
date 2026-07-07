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
import org.briarproject.bramble.api.db.CommitAction;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DatabaseConfig;
import org.briarproject.bramble.api.db.DbRunnable;
import org.briarproject.bramble.api.db.EventAction;
import org.briarproject.bramble.api.db.TaskAction;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.sync.Group;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.api.versioning.ClientVersioningManager;
import org.briarproject.bramble.test.BrambleMockTestCase;
import org.briarproject.briar.api.client.MessageTracker;
import org.briarproject.briar.api.conversation.ConversationManager;
import org.briarproject.briar.api.filetransfer.FileTransferHeader;
import org.hamcrest.Description;
import org.jmock.Expectations;
import org.jmock.api.Action;
import org.jmock.api.Invocation;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.File;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.briarproject.bramble.test.TestUtils.deleteTestDirectory;
import static org.briarproject.bramble.test.TestUtils.getContact;
import static org.briarproject.bramble.test.TestUtils.getGroup;
import static org.briarproject.bramble.test.TestUtils.getRandomId;
import static org.briarproject.bramble.test.TestUtils.getTestDirectory;
import static org.briarproject.bramble.test.TestUtils.writeBytes;
import static org.briarproject.bramble.api.client.ContactGroupConstants.GROUP_KEY_CONTACT_ID;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.CLIENT_ID;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MAJOR_VERSION;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MINOR_VERSION;
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
import static org.briarproject.briar.client.MessageTrackerConstants.MSG_KEY_READ;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FileTransferDeletionTest extends BrambleMockTestCase {

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
	public void testDeletingHeaderDeletesChunksAndFiles() throws Exception {
		Transaction txn = new Transaction(null, false);
		Contact contact = getContact();
		Group group = getGroup(CLIENT_ID, MAJOR_VERSION);
		UniqueId fileId = new UniqueId(getRandomId());
		UniqueId remainingFileId = new UniqueId(getRandomId());
		MessageId headerId = new MessageId(getRandomId());
		MessageId chunkId = new MessageId(getRandomId());
		MessageId remainingHeaderId = new MessageId(getRandomId());
		File fileDir = getFileDir(fileId);
		writeChunkFiles(fileDir);

		BdfDictionary headerMeta = headerMetadata(fileId, true);
		BdfDictionary remainingMeta = headerMetadata(remainingFileId, true);
		Set<MessageId> related = new HashSet<>(Arrays.asList(headerId, chunkId));
		Map<MessageId, BdfDictionary> remaining = new HashMap<>();
		remaining.put(remainingHeaderId, remainingMeta);

		expectContactGroup(txn, contact, group);
		context.checking(new Expectations() {{
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn, headerId);
			will(returnValue(headerMeta));
			oneOf(clientHelper).getMessageIds(with(same(txn)),
					with(equal(group.getId())), with(any(BdfDictionary.class)));
			will(returnValue(related));
			oneOf(db).deleteMessage(txn, headerId);
			oneOf(db).deleteMessageMetadata(txn, headerId);
			oneOf(db).deleteMessage(txn, chunkId);
			oneOf(db).deleteMessageMetadata(txn, chunkId);
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					group.getId());
			will(returnValue(remaining));
			oneOf(messageTracker).resetGroupCount(txn, group.getId(), 1, 0);
		}});

		manager.deleteMessages(txn, contact.getId(),
				Collections.singleton(headerId));

		assertTrue(fileDir.exists());
		runCommitTasks(txn);

		assertFalse(fileDir.exists());
	}

	@Test
	public void testDeleteAllMessagesDeletesTransferDirectories()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		Contact contact = getContact();
		Group group = getGroup(CLIENT_ID, MAJOR_VERSION);
		UniqueId fileId1 = new UniqueId(getRandomId());
		UniqueId fileId2 = new UniqueId(getRandomId());
		MessageId headerId1 = new MessageId(getRandomId());
		MessageId headerId2 = new MessageId(getRandomId());
		MessageId chunkId = new MessageId(getRandomId());
		File fileDir1 = getFileDir(fileId1);
		File fileDir2 = getFileDir(fileId2);
		writeChunkFiles(fileDir1);
		writeChunkFiles(fileDir2);

		Map<MessageId, BdfDictionary> metadata = new HashMap<>();
		metadata.put(headerId1, headerMetadata(fileId1, true));
		metadata.put(headerId2, headerMetadata(fileId2, true));
		metadata.put(chunkId, chunkMetadata(fileId1));

		expectContactGroup(txn, contact, group);
		context.checking(new Expectations() {{
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					group.getId());
			will(returnValue(metadata));
			oneOf(db).getMessageIds(txn, group.getId());
			will(returnValue(Arrays.asList(headerId1, chunkId, headerId2)));
			oneOf(db).deleteMessage(txn, headerId1);
			oneOf(db).deleteMessageMetadata(txn, headerId1);
			oneOf(db).deleteMessage(txn, chunkId);
			oneOf(db).deleteMessageMetadata(txn, chunkId);
			oneOf(db).deleteMessage(txn, headerId2);
			oneOf(db).deleteMessageMetadata(txn, headerId2);
			oneOf(messageTracker).initializeGroupCount(txn, group.getId());
		}});

		manager.deleteAllMessages(txn, contact.getId());

		assertTrue(fileDir1.exists());
		assertTrue(fileDir2.exists());
		runCommitTasks(txn);

		assertFalse(fileDir1.exists());
		assertFalse(fileDir2.exists());
	}

	@Test
	public void testDeleteAllMessagesDeletesOrphanChunkDirectory()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		Contact contact = getContact();
		Group group = getGroup(CLIENT_ID, MAJOR_VERSION);
		UniqueId fileId = new UniqueId(getRandomId());
		MessageId chunkId = new MessageId(getRandomId());
		File fileDir = getFileDir(fileId);
		writeChunkFiles(fileDir);

		Map<MessageId, BdfDictionary> metadata = new HashMap<>();
		metadata.put(chunkId, chunkMetadata(fileId));

		expectContactGroup(txn, contact, group);
		context.checking(new Expectations() {{
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					group.getId());
			will(returnValue(metadata));
			oneOf(db).getMessageIds(txn, group.getId());
			will(returnValue(Collections.singletonList(chunkId)));
			oneOf(db).deleteMessage(txn, chunkId);
			oneOf(db).deleteMessageMetadata(txn, chunkId);
			oneOf(messageTracker).initializeGroupCount(txn, group.getId());
		}});

		manager.deleteAllMessages(txn, contact.getId());

		assertTrue(fileDir.exists());
		runCommitTasks(txn);

		assertFalse(fileDir.exists());
	}

	@Test
	public void testDeletingChunkDeletesOrphanChunkDirectory()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		Contact contact = getContact();
		Group group = getGroup(CLIENT_ID, MAJOR_VERSION);
		UniqueId fileId = new UniqueId(getRandomId());
		MessageId chunkId = new MessageId(getRandomId());
		File fileDir = getFileDir(fileId);
		writeChunkFiles(fileDir);
		Map<MessageId, BdfDictionary> remaining = new HashMap<>();

		expectContactGroup(txn, contact, group);
		context.checking(new Expectations() {{
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn, chunkId);
			will(returnValue(chunkMetadata(fileId)));
			oneOf(db).deleteMessage(txn, chunkId);
			oneOf(db).deleteMessageMetadata(txn, chunkId);
			oneOf(clientHelper).getMessageMetadataAsDictionary(with(same(txn)),
					with(equal(group.getId())), with(any(BdfDictionary.class)));
			will(returnValue(remaining));
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					group.getId());
			will(returnValue(remaining));
			oneOf(messageTracker).resetGroupCount(txn, group.getId(), 0, 0);
		}});

		manager.deleteMessages(txn, contact.getId(),
				Collections.singleton(chunkId));

		assertTrue(fileDir.exists());
		runCommitTasks(txn);

		assertFalse(fileDir.exists());
	}

	@Test
	public void testDeletingChunkWithHeaderDeletesChunkFilesAndRepairsCount()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		Contact contact = getContact();
		Group group = getGroup(CLIENT_ID, MAJOR_VERSION);
		UniqueId fileId = new UniqueId(getRandomId());
		MessageId chunkId = new MessageId(getRandomId());
		MessageId headerId = new MessageId(getRandomId());
		File fileDir = getFileDir(fileId);
		writeChunkFiles(fileDir, 0, 2);
		writeChunkFiles(fileDir, 1, 2);
		Map<MessageId, BdfDictionary> remaining = new HashMap<>();
		remaining.put(headerId, headerMetadata(fileId, true, 2, 2));
		BdfDictionary received = BdfDictionary.of(new BdfEntry(
				MSG_KEY_CHUNKS_RECEIVED, 1));

		expectContactGroup(txn, contact, group);
		context.checking(new Expectations() {{
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn, chunkId);
			will(returnValue(chunkMetadata(fileId, 0, 2)));
			oneOf(db).deleteMessage(txn, chunkId);
			oneOf(db).deleteMessageMetadata(txn, chunkId);
			oneOf(clientHelper).getMessageMetadataAsDictionary(with(same(txn)),
					with(equal(group.getId())), with(any(BdfDictionary.class)));
			will(returnValue(remaining));
			oneOf(clientHelper).getMessageIds(with(same(txn)),
					with(equal(group.getId())), with(any(BdfDictionary.class)));
			will(returnValue(Collections.emptyList()));
			oneOf(clientHelper).mergeMessageMetadata(txn, headerId, received);
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					group.getId());
			will(returnValue(remaining));
			oneOf(messageTracker).resetGroupCount(txn, group.getId(), 1, 0);
		}});

		manager.deleteMessages(txn, contact.getId(),
				Collections.singleton(chunkId));

		assertTrue(getChunkFile(fileDir, 0).exists());
		assertTrue(getChunkFile(fileDir, 1).exists());
		assertEquals(2, countExistingChunks(fileId, fileDir, 2));
		runCommitTasks(txn);

		assertFalse(getChunkFile(fileDir, 0).exists());
		assertFalse(getChunkTotalFile(fileDir, 0).exists());
		assertTrue(getChunkFile(fileDir, 1).exists());
		assertTrue(fileDir.exists());
	}

	@Test
	public void testDeletingChunkWithAssembledFileKeepsCompleteCount()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		Contact contact = getContact();
		Group group = getGroup(CLIENT_ID, MAJOR_VERSION);
		UniqueId fileId = new UniqueId(getRandomId());
		MessageId chunkId = new MessageId(getRandomId());
		MessageId headerId = new MessageId(getRandomId());
		File fileDir = getFileDir(fileId);
		File assembled = getAssembledFile(fileDir, "file.bin");
		assertTrue(assembled.getParentFile().mkdirs());
		writeBytes(assembled, new byte[] {1, 2});
		Map<MessageId, BdfDictionary> remaining = new HashMap<>();
		remaining.put(headerId, headerMetadata(fileId, true, 2, 2));
		BdfDictionary complete = BdfDictionary.of(new BdfEntry(
				MSG_KEY_CHUNKS_RECEIVED, 2));

		expectContactGroup(txn, contact, group);
		context.checking(new Expectations() {{
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn, chunkId);
			will(returnValue(chunkMetadata(fileId, 0, 2)));
			oneOf(db).deleteMessage(txn, chunkId);
			oneOf(db).deleteMessageMetadata(txn, chunkId);
			oneOf(clientHelper).getMessageMetadataAsDictionary(with(same(txn)),
					with(equal(group.getId())), with(any(BdfDictionary.class)));
			will(returnValue(remaining));
			oneOf(clientHelper).getMessageIds(with(same(txn)),
					with(equal(group.getId())), with(any(BdfDictionary.class)));
			will(returnValue(Collections.emptyList()));
			oneOf(clientHelper).mergeMessageMetadata(txn, headerId, complete);
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					group.getId());
			will(returnValue(remaining));
			oneOf(messageTracker).resetGroupCount(txn, group.getId(), 1, 0);
		}});

		manager.deleteMessages(txn, contact.getId(),
				Collections.singleton(chunkId));
	}

	@Test
	public void testDeletingWrongTotalChunkDoesNotDeleteReplacementChunk()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		Contact contact = getContact();
		Group group = getGroup(CLIENT_ID, MAJOR_VERSION);
		UniqueId fileId = new UniqueId(getRandomId());
		MessageId chunkId = new MessageId(getRandomId());
		MessageId headerId = new MessageId(getRandomId());
		File fileDir = getFileDir(fileId);
		writeChunkFiles(fileDir, 0, 2);
		Map<MessageId, BdfDictionary> remaining = new HashMap<>();
		remaining.put(headerId, headerMetadata(fileId, true, 2, 1));
		BdfDictionary received = BdfDictionary.of(new BdfEntry(
				MSG_KEY_CHUNKS_RECEIVED, 1));

		expectContactGroup(txn, contact, group);
		context.checking(new Expectations() {{
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn, chunkId);
			will(returnValue(chunkMetadata(fileId, 0, 3)));
			oneOf(db).deleteMessage(txn, chunkId);
			oneOf(db).deleteMessageMetadata(txn, chunkId);
			oneOf(clientHelper).getMessageMetadataAsDictionary(with(same(txn)),
					with(equal(group.getId())), with(any(BdfDictionary.class)));
			will(returnValue(remaining));
			oneOf(clientHelper).getMessageIds(with(same(txn)),
					with(equal(group.getId())), with(any(BdfDictionary.class)));
			will(returnValue(Collections.emptyList()));
			oneOf(clientHelper).mergeMessageMetadata(txn, headerId, received);
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					group.getId());
			will(returnValue(remaining));
			oneOf(messageTracker).resetGroupCount(txn, group.getId(), 1, 0);
		}});

		manager.deleteMessages(txn, contact.getId(),
				Collections.singleton(chunkId));
		runCommitTasks(txn);

		assertTrue(getChunkFile(fileDir, 0).exists());
		assertTrue(getChunkTotalFile(fileDir, 0).exists());
	}

	@Test
	public void testDeletingDuplicateChunkMetadataKeepsChunkFile()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		Contact contact = getContact();
		Group group = getGroup(CLIENT_ID, MAJOR_VERSION);
		UniqueId fileId = new UniqueId(getRandomId());
		MessageId chunkId = new MessageId(getRandomId());
		MessageId remainingChunkId = new MessageId(getRandomId());
		MessageId headerId = new MessageId(getRandomId());
		File fileDir = getFileDir(fileId);
		writeChunkFiles(fileDir, 0, 2);
		Map<MessageId, BdfDictionary> remaining = new HashMap<>();
		remaining.put(headerId, headerMetadata(fileId, true, 2, 1));
		BdfDictionary received = BdfDictionary.of(new BdfEntry(
				MSG_KEY_CHUNKS_RECEIVED, 1));

		expectContactGroup(txn, contact, group);
		context.checking(new Expectations() {{
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn, chunkId);
			will(returnValue(chunkMetadata(fileId, 0, 2)));
			oneOf(db).deleteMessage(txn, chunkId);
			oneOf(db).deleteMessageMetadata(txn, chunkId);
			oneOf(clientHelper).getMessageMetadataAsDictionary(with(same(txn)),
					with(equal(group.getId())), with(any(BdfDictionary.class)));
			will(returnValue(remaining));
			oneOf(clientHelper).getMessageIds(with(same(txn)),
					with(equal(group.getId())), with(any(BdfDictionary.class)));
			will(returnValue(Collections.singletonList(remainingChunkId)));
			oneOf(clientHelper).mergeMessageMetadata(txn, headerId, received);
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					group.getId());
			will(returnValue(remaining));
			oneOf(messageTracker).resetGroupCount(txn, group.getId(), 1, 0);
		}});

		manager.deleteMessages(txn, contact.getId(),
				Collections.singleton(chunkId));
		runCommitTasks(txn);

		assertTrue(getChunkFile(fileDir, 0).exists());
		assertTrue(getChunkTotalFile(fileDir, 0).exists());
	}

	@Test
	public void testCancelFileTransferDoesNotSendControlToOldPeer()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		Contact contact = getContact();
		Group group = getGroup(CLIENT_ID, MAJOR_VERSION);
		UniqueId fileId = new UniqueId(getRandomId());
		MessageId headerId = new MessageId(getRandomId());
		File fileDir = getFileDir(fileId);
		writeChunkFiles(fileDir);
		FileTransferHeader header = new FileTransferHeader(headerId,
				group.getId(), 1, true, true, false, false, 0, fileId,
				"file.bin", "application/octet-stream", 2L, 1);
		BdfDictionary groupMeta = BdfDictionary.of(new BdfEntry(
				GROUP_KEY_CONTACT_ID, contact.getId().getInt()));
		BdfDictionary terminal = BdfDictionary.of(new BdfEntry(
				MSG_KEY_TRANSFER_STATE, TRANSFER_STATE_CANCELLED_BY_SENDER));

		context.checking(new Expectations() {{
			oneOf(db).transaction(with(false), with(any(DbRunnable.class)));
			will(runDbRunnable(txn));
			oneOf(clientHelper).mergeMessageMetadata(txn, headerId, terminal);
			oneOf(clientHelper).getGroupMetadataAsDictionary(txn, group.getId());
			will(returnValue(groupMeta));
			oneOf(clientVersioningManager).getClientMinorVersion(txn,
					contact.getId(), CLIENT_ID, MAJOR_VERSION);
			will(returnValue(0));
			never(clientHelper).createMessage(with(equal(group.getId())),
					with(any(Long.class)), with(any(BdfList.class)));
		}});

		manager.cancelFileTransfer(header);

		assertTrue(fileDir.exists());
		runCommitTasks(txn);
		assertFalse(fileDir.exists());
	}

	@Test
	public void testCancelFileTransferSendsControlToMinorOnePeer()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		Contact contact = getContact();
		Group group = getGroup(CLIENT_ID, MAJOR_VERSION);
		UniqueId fileId = new UniqueId(getRandomId());
		MessageId headerId = new MessageId(getRandomId());
		Message control = new Message(new MessageId(getRandomId()),
				group.getId(), 2, new byte[] {1});
		FileTransferHeader header = new FileTransferHeader(headerId,
				group.getId(), 1, true, true, false, false, 0, fileId,
				"file.bin", "application/octet-stream", 2L, 1);
		BdfDictionary groupMeta = BdfDictionary.of(new BdfEntry(
				GROUP_KEY_CONTACT_ID, contact.getId().getInt()));
		BdfDictionary terminal = BdfDictionary.of(new BdfEntry(
				MSG_KEY_TRANSFER_STATE, TRANSFER_STATE_CANCELLED_BY_SENDER));
		BdfList body = BdfList.of(MSG_TYPE_CONTROL, fileId.getBytes(),
				TRANSFER_STATE_CANCELLED_BY_SENDER);

		context.checking(new Expectations() {{
			oneOf(db).transaction(with(false), with(any(DbRunnable.class)));
			will(runDbRunnable(txn));
			oneOf(clientHelper).mergeMessageMetadata(txn, headerId, terminal);
			oneOf(clientHelper).getGroupMetadataAsDictionary(txn, group.getId());
			will(returnValue(groupMeta));
			oneOf(clientVersioningManager).getClientMinorVersion(txn,
					contact.getId(), CLIENT_ID, MAJOR_VERSION);
			will(returnValue(MINOR_VERSION));
			oneOf(clientHelper).createMessage(with(equal(group.getId())),
					with(any(Long.class)), with(equal(body)));
			will(returnValue(control));
			oneOf(clientHelper).addLocalMessage(with(same(txn)),
					with(same(control)), with(any(BdfDictionary.class)), with(true),
					with(false));
			will(assertControlMetadata(fileId,
					TRANSFER_STATE_CANCELLED_BY_SENDER));
		}});

		manager.cancelFileTransfer(header);
	}

	@Test
	public void testRejectFileTransferSendsControlToMinorOnePeer()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		Contact contact = getContact();
		Group group = getGroup(CLIENT_ID, MAJOR_VERSION);
		UniqueId fileId = new UniqueId(getRandomId());
		MessageId headerId = new MessageId(getRandomId());
		Message control = new Message(new MessageId(getRandomId()),
				group.getId(), 2, new byte[] {1});
		FileTransferHeader header = new FileTransferHeader(headerId,
				group.getId(), 1, false, false, false, false, 0, fileId,
				"file.bin", "application/octet-stream", 2L, 1);
		BdfDictionary groupMeta = BdfDictionary.of(new BdfEntry(
				GROUP_KEY_CONTACT_ID, contact.getId().getInt()));
		BdfDictionary terminal = BdfDictionary.of(new BdfEntry(
				MSG_KEY_TRANSFER_STATE, TRANSFER_STATE_REJECTED_BY_RECEIVER));
		BdfList body = BdfList.of(MSG_TYPE_CONTROL, fileId.getBytes(),
				TRANSFER_STATE_REJECTED_BY_RECEIVER);

		context.checking(new Expectations() {{
			oneOf(db).transaction(with(false), with(any(DbRunnable.class)));
			will(runDbRunnable(txn));
			oneOf(clientHelper).mergeMessageMetadata(txn, headerId, terminal);
			oneOf(clientHelper).getGroupMetadataAsDictionary(txn, group.getId());
			will(returnValue(groupMeta));
			oneOf(clientVersioningManager).getClientMinorVersion(txn,
					contact.getId(), CLIENT_ID, MAJOR_VERSION);
			will(returnValue(MINOR_VERSION));
			oneOf(clientHelper).createMessage(with(equal(group.getId())),
					with(any(Long.class)), with(equal(body)));
			will(returnValue(control));
			oneOf(clientHelper).addLocalMessage(with(same(txn)),
					with(same(control)), with(any(BdfDictionary.class)), with(true),
					with(false));
			will(assertControlMetadata(fileId,
					TRANSFER_STATE_REJECTED_BY_RECEIVER));
		}});

		manager.rejectFileTransfer(header);
	}

	@Test
	public void testRemovingContactDeletesTransferDirectories()
			throws Exception {
		Transaction txn = new Transaction(null, false);
		Contact contact = getContact();
		Group group = getGroup(CLIENT_ID, MAJOR_VERSION);
		UniqueId headerFileId = new UniqueId(getRandomId());
		UniqueId chunkFileId = new UniqueId(getRandomId());
		MessageId headerId = new MessageId(getRandomId());
		MessageId chunkId = new MessageId(getRandomId());
		File headerFileDir = getFileDir(headerFileId);
		File chunkFileDir = getFileDir(chunkFileId);
		writeChunkFiles(headerFileDir);
		writeChunkFiles(chunkFileDir);
		Map<MessageId, BdfDictionary> metadata = new HashMap<>();
		metadata.put(headerId, headerMetadata(headerFileId, true));
		metadata.put(chunkId, chunkMetadata(chunkFileId));

		context.checking(new Expectations() {{
			oneOf(contactGroupFactory).createContactGroup(CLIENT_ID,
					MAJOR_VERSION, contact);
			will(returnValue(group));
			oneOf(clientHelper).getMessageMetadataAsDictionary(txn,
					group.getId());
			will(returnValue(metadata));
			oneOf(db).removeGroup(txn, group);
		}});

		manager.removingContact(txn, contact);

		assertTrue(headerFileDir.exists());
		assertTrue(chunkFileDir.exists());
		runCommitTasks(txn);

		assertFalse(headerFileDir.exists());
		assertFalse(chunkFileDir.exists());
	}

	private void expectContactGroup(Transaction txn, Contact contact, Group group)
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

	private BdfDictionary headerMetadata(UniqueId fileId, boolean read) {
		return headerMetadata(fileId, read, 1, 1);
	}

	private BdfDictionary headerMetadata(UniqueId fileId, boolean read,
			int chunkTotal, int chunksReceived) {
		BdfDictionary meta = new BdfDictionary();
		meta.put(MSG_KEY_MSG_TYPE, MSG_TYPE_HEADER);
		meta.put(MSG_KEY_FILE_ID, fileId.getBytes());
		meta.put(MSG_KEY_FILE_NAME, "file.bin");
		meta.put(MSG_KEY_CONTENT_TYPE, "application/octet-stream");
		meta.put(MSG_KEY_FILE_SIZE, 2L);
		meta.put(MSG_KEY_CHUNK_TOTAL, chunkTotal);
		meta.put(MSG_KEY_CHUNKS_RECEIVED, chunksReceived);
		meta.put(MSG_KEY_LOCAL, true);
		meta.put(MSG_KEY_READ, read);
		meta.put(MSG_KEY_TIMESTAMP, 1L);
		return meta;
	}

	private BdfDictionary chunkMetadata(UniqueId fileId) {
		return chunkMetadata(fileId, 0, 1);
	}

	private BdfDictionary chunkMetadata(UniqueId fileId, int chunkIndex,
			int chunkTotal) {
		BdfDictionary meta = new BdfDictionary();
		meta.put(MSG_KEY_MSG_TYPE, MSG_TYPE_CHUNK);
		meta.put(MSG_KEY_FILE_ID, fileId.getBytes());
		meta.put(MSG_KEY_CHUNK_INDEX, chunkIndex);
		meta.put(MSG_KEY_CHUNK_TOTAL, chunkTotal);
		meta.put(MSG_KEY_LOCAL, true);
		meta.put(MSG_KEY_TIMESTAMP, 2L);
		return meta;
	}

	private void writeChunkFiles(File fileDir) throws Exception {
		writeChunkFiles(fileDir, 0, 1);
	}

	private void writeChunkFiles(File fileDir, int chunkIndex, int chunkTotal)
			throws Exception {
		File chunk = getChunkFile(fileDir, chunkIndex);
		assertTrue(chunk.getParentFile().exists() ||
				chunk.getParentFile().mkdirs());
		writeBytes(chunk, new byte[] {1, 2});
		writeBytes(getChunkTotalFile(fileDir, chunkIndex),
				Integer.toString(chunkTotal).getBytes("UTF-8"));
	}

	private File getFileDir(UniqueId fileId) throws Exception {
		Method method = FileTransferManagerImpl.class.getDeclaredMethod(
				"getFileDir", UniqueId.class);
		method.setAccessible(true);
		return (File) method.invoke(manager, fileId);
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

	private File getAssembledFile(File fileDir, String fileName)
			throws Exception {
		Method method = FileTransferManagerImpl.class.getDeclaredMethod(
				"getAssembledFile", File.class, String.class);
		method.setAccessible(true);
		return (File) method.invoke(manager, fileDir, fileName);
	}

	private int countExistingChunks(UniqueId fileId, File fileDir, int chunkTotal)
			throws Exception {
		Method method = FileTransferManagerImpl.class.getDeclaredMethod(
				"countExistingChunks", UniqueId.class, File.class, int.class);
		method.setAccessible(true);
		return (Integer) method.invoke(manager, fileId, fileDir, chunkTotal);
	}

	private Action runDbRunnable(Transaction txn) {
		return new Action() {
			@Override
			public Object invoke(Invocation invocation) throws Throwable {
				DbRunnable<?> runnable =
						(DbRunnable<?>) invocation.getParameter(1);
				runnable.run(txn);
				return null;
			}

			@Override
			public void describeTo(Description description) {
				description.appendText("runs transaction");
			}
		};
	}

	private Action assertControlMetadata(UniqueId fileId, String transferState) {
		return new Action() {
			@Override
			public Object invoke(Invocation invocation) throws Throwable {
				BdfDictionary meta = (BdfDictionary) invocation.getParameter(2);
				assertEquals(MSG_TYPE_CONTROL, meta.getString(MSG_KEY_MSG_TYPE));
				assertEquals(fileId, new UniqueId(meta.getRaw(MSG_KEY_FILE_ID)));
				assertEquals(transferState,
						meta.getString(MSG_KEY_TRANSFER_STATE));
				assertTrue(meta.getBoolean(MSG_KEY_LOCAL));
				meta.getLong(MSG_KEY_TIMESTAMP);
				return null;
			}

			@Override
			public void describeTo(Description description) {
				description.appendText("asserts control metadata");
			}
		};
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
