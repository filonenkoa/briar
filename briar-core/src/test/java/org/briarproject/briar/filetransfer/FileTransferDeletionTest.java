package org.briarproject.briar.filetransfer;

import org.briarproject.bramble.api.UniqueId;
import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.client.ContactGroupFactory;
import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.bramble.api.data.MetadataParser;
import org.briarproject.bramble.api.db.CommitAction;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DatabaseConfig;
import org.briarproject.bramble.api.db.EventAction;
import org.briarproject.bramble.api.db.TaskAction;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.sync.Group;
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
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_KEY_TIMESTAMP;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_TYPE_CHUNK;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_TYPE_HEADER;
import static org.briarproject.briar.client.MessageTrackerConstants.MSG_KEY_READ;
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
			oneOf(clientHelper).getMessageIds(with(same(txn)),
					with(equal(group.getId())), with(any(BdfDictionary.class)));
			will(returnValue(Collections.emptyList()));
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
		BdfDictionary meta = new BdfDictionary();
		meta.put(MSG_KEY_MSG_TYPE, MSG_TYPE_HEADER);
		meta.put(MSG_KEY_FILE_ID, fileId.getBytes());
		meta.put(MSG_KEY_FILE_NAME, "file.bin");
		meta.put(MSG_KEY_CONTENT_TYPE, "application/octet-stream");
		meta.put(MSG_KEY_FILE_SIZE, 2L);
		meta.put(MSG_KEY_CHUNK_TOTAL, 1);
		meta.put(MSG_KEY_CHUNKS_RECEIVED, 1);
		meta.put(MSG_KEY_LOCAL, true);
		meta.put(MSG_KEY_READ, read);
		meta.put(MSG_KEY_TIMESTAMP, 1L);
		return meta;
	}

	private BdfDictionary chunkMetadata(UniqueId fileId) {
		BdfDictionary meta = new BdfDictionary();
		meta.put(MSG_KEY_MSG_TYPE, MSG_TYPE_CHUNK);
		meta.put(MSG_KEY_FILE_ID, fileId.getBytes());
		meta.put(MSG_KEY_CHUNK_INDEX, 0);
		meta.put(MSG_KEY_CHUNK_TOTAL, 1);
		meta.put(MSG_KEY_LOCAL, true);
		meta.put(MSG_KEY_TIMESTAMP, 2L);
		return meta;
	}

	private void writeChunkFiles(File fileDir) throws Exception {
		File chunk = new File(new File(fileDir, "chunks"), "chunk_0");
		assertTrue(chunk.getParentFile().mkdirs());
		writeBytes(chunk, new byte[] {1, 2});
		writeBytes(new File(chunk.getParentFile(), chunk.getName() + ".total"),
				new byte[] {'1'});
	}

	private File getFileDir(UniqueId fileId) throws Exception {
		Method method = FileTransferManagerImpl.class.getDeclaredMethod(
				"getFileDir", UniqueId.class);
		method.setAccessible(true);
		return (File) method.invoke(manager, fileId);
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
