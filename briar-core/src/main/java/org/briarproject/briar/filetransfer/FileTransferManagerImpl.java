package org.briarproject.briar.filetransfer;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.UniqueId;
import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.client.ContactGroupFactory;
import org.briarproject.bramble.api.contact.Contact;
import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.contact.ContactManager.ContactHook;
import org.briarproject.bramble.api.cleanup.CleanupHook;
import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.bramble.api.data.BdfEntry;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.data.MetadataParser;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.DatabaseConfig;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.db.Metadata;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.lifecycle.LifecycleManager.OpenDatabaseHook;
import org.briarproject.bramble.api.sync.Group;
import org.briarproject.bramble.api.sync.Group.Visibility;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.InvalidMessageException;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.api.sync.MessageStatus;
import org.briarproject.bramble.api.sync.validation.IncomingMessageHook;
import org.briarproject.bramble.api.versioning.ClientVersioningManager;
import org.briarproject.bramble.api.versioning.ClientVersioningManager.ClientVersioningHook;
import static org.briarproject.briar.api.autodelete.AutoDeleteConstants.NO_AUTO_DELETE_TIMER;
import org.briarproject.briar.api.client.MessageTracker;
import org.briarproject.briar.api.client.MessageTracker.GroupCount;
import org.briarproject.briar.api.conversation.ConversationManager;
import org.briarproject.briar.api.conversation.ConversationManager.ConversationClient;
import org.briarproject.briar.api.conversation.ConversationMessageHeader;
import org.briarproject.briar.api.conversation.DeletionResult;
import org.briarproject.briar.api.filetransfer.FileTransferConstants;
import org.briarproject.briar.api.filetransfer.FileTransferHeader;
import org.briarproject.briar.api.filetransfer.FileTransferManager;
import org.briarproject.briar.api.filetransfer.FileTransferProgress;
import org.briarproject.briar.api.filetransfer.FileTransferProgress.State;
import org.briarproject.briar.api.filetransfer.event.FileTransferReceivedEvent;
import static org.briarproject.briar.client.MessageTrackerConstants.MSG_KEY_READ;
import org.briarproject.bramble.util.StringUtils;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import java.util.logging.Logger;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import javax.inject.Inject;

import static java.util.Collections.emptyList;
import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.api.client.ContactGroupConstants.GROUP_KEY_CONTACT_ID;
import static org.briarproject.bramble.api.sync.validation.IncomingMessageHook.DeliveryAction.ACCEPT_DO_NOT_SHARE;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.CHUNK_SIZE;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_KEY_CHUNK_INDEX;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_KEY_CHUNKS_RECEIVED;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_KEY_CONTENT_TYPE;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_KEY_FILE_ID;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_KEY_FILE_NAME;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_KEY_FILE_SIZE;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_KEY_CHUNK_TOTAL;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_KEY_LOCAL;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_KEY_MSG_TYPE;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_KEY_TIMESTAMP;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_TYPE_CHUNK;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_TYPE_HEADER;

@Immutable
@NotNullByDefault
class FileTransferManagerImpl implements FileTransferManager, IncomingMessageHook,
		ConversationClient, OpenDatabaseHook, ContactHook,
		ClientVersioningHook, CleanupHook {

	private static final Logger LOG =
			getLogger(FileTransferManagerImpl.class.getName());
	private static final String STORAGE_SUBDIR = "filetransfer";

	private final DatabaseComponent db;
	private final ClientHelper clientHelper;
	private final ClientVersioningManager clientVersioningManager;
	private final ContactGroupFactory contactGroupFactory;
	private final MessageTracker messageTracker;
	private final ConversationManager conversationManager;
	private final MetadataParser metadataParser;
	private final EventBus eventBus;
	private final DatabaseConfig databaseConfig;

	@Inject
	FileTransferManagerImpl(
			DatabaseComponent db,
			ClientHelper clientHelper,
			ClientVersioningManager clientVersioningManager,
			ContactGroupFactory contactGroupFactory,
			MessageTracker messageTracker,
			ConversationManager conversationManager,
			MetadataParser metadataParser,
			EventBus eventBus,
			DatabaseConfig databaseConfig) {
		this.db = db;
		this.clientHelper = clientHelper;
		this.clientVersioningManager = clientVersioningManager;
		this.contactGroupFactory = contactGroupFactory;
		this.messageTracker = messageTracker;
		this.conversationManager = conversationManager;
		this.metadataParser = metadataParser;
		this.eventBus = eventBus;
		this.databaseConfig = databaseConfig;
	}

	private File getStorageDir() {
		return new File(databaseConfig.getDatabaseDirectory(), STORAGE_SUBDIR);
	}

	private File getFileDir(UniqueId fileId) {
		return new File(getStorageDir(),
				StringUtils.toHexString(fileId.getBytes()));
	}

	private File getChunkFile(File fileDir, int chunkIndex) {
		return new File(fileDir, "chunk_" + chunkIndex);
	}

	private File getAssembledFile(File fileDir, String fileName) {
		return new File(fileDir, fileName);
	}

	private UniqueId generateFileId() {
		byte[] bytes = new byte[UniqueId.LENGTH];
		new SecureRandom().nextBytes(bytes);
		return new UniqueId(bytes);
	}

	@Override
	public GroupCount getGroupCount(Transaction txn, ContactId contactId)
			throws DbException {
		Contact contact = db.getContact(txn, contactId);
		GroupId groupId = getContactGroup(contact).getId();
		return messageTracker.getGroupCount(txn, groupId);
	}

	@Override
	public void onDatabaseOpened(Transaction txn) throws DbException {
		Group localGroup =
				contactGroupFactory.createLocalGroup(CLIENT_ID, MAJOR_VERSION);
		if (db.containsGroup(txn, localGroup.getId())) return;
		db.addGroup(txn, localGroup);
		for (Contact c : db.getContacts(txn)) addingContact(txn, c);
	}

	@Override
	public void addingContact(Transaction txn, Contact c) throws DbException {
		Group g = getContactGroup(c);
		db.addGroup(txn, g);
		Visibility client = clientVersioningManager.getClientVisibility(txn,
				c.getId(), CLIENT_ID, MAJOR_VERSION);
		db.setGroupVisibility(txn, c.getId(), g.getId(), client);
		clientHelper.setContactId(txn, g.getId(), c.getId());
		messageTracker.initializeGroupCount(txn, g.getId());
	}

	@Override
	public Group getContactGroup(Contact c) {
		return contactGroupFactory.createContactGroup(CLIENT_ID, MAJOR_VERSION,
				c);
	}

	@Override
	public void removingContact(Transaction txn, Contact c) throws DbException {
		db.removeGroup(txn, getContactGroup(c));
	}

	@Override
	public void onClientVisibilityChanging(Transaction txn, Contact c,
			Visibility v) throws DbException {
		Group g = getContactGroup(c);
		db.setGroupVisibility(txn, c.getId(), g.getId(), v);
	}

	@Override
	public DeliveryAction incomingMessage(Transaction txn, Message m,
			Metadata meta) throws DbException, InvalidMessageException {
		try {
			BdfDictionary metaDict = metadataParser.parse(meta);
			String messageType = metaDict.getString(MSG_KEY_MSG_TYPE);
			if (MSG_TYPE_CHUNK.equals(messageType)) {
				incomingChunk(txn, m, metaDict);
			} else if (MSG_TYPE_HEADER.equals(messageType)) {
				incomingHeader(txn, m, metaDict);
			} else {
				throw new InvalidMessageException();
			}
		} catch (FormatException e) {
			throw new InvalidMessageException(e);
		}
		return ACCEPT_DO_NOT_SHARE;
	}

	private void incomingChunk(Transaction txn, Message m, BdfDictionary metaDict)
			throws DbException, FormatException {
		GroupId groupId = m.getGroupId();
		UniqueId fileId = new UniqueId(metaDict.getRaw(MSG_KEY_FILE_ID));
		int chunkIndex = metaDict.getInt(MSG_KEY_CHUNK_INDEX);
		// Write the payload to disk
		BdfList body = clientHelper.getMessageAsList(txn, m.getId());
		byte[] payload = body.getRaw(4);
		File fileDir = getFileDir(fileId);
		fileDir.mkdirs();
		writeBytes(getChunkFile(fileDir, chunkIndex), payload);
		// Find the header message for this file and increment its received count
		BdfDictionary query = BdfDictionary.of(
				new BdfEntry(MSG_KEY_FILE_ID, fileId.getBytes()),
				new BdfEntry(MSG_KEY_MSG_TYPE, MSG_TYPE_HEADER));
		Map<MessageId, BdfDictionary> headers =
				clientHelper.getMessageMetadataAsDictionary(txn, groupId, query);
		for (Entry<MessageId, BdfDictionary> e : headers.entrySet()) {
			BdfDictionary h = e.getValue();
			int received = h.getInt(MSG_KEY_CHUNKS_RECEIVED);
			int total = h.getInt(MSG_KEY_CHUNK_TOTAL);
			BdfDictionary merge = new BdfDictionary();
			merge.put(MSG_KEY_CHUNKS_RECEIVED, received + 1);
			clientHelper.mergeMessageMetadata(txn, e.getKey(), merge);
			if (received + 1 >= total) {
				tryAssemble(txn, groupId, fileId, e.getKey());
			}
		}
	}

	private void incomingHeader(Transaction txn, Message m,
			BdfDictionary metaDict) throws DbException, FormatException {
		GroupId groupId = m.getGroupId();
		long timestamp = metaDict.getLong(MSG_KEY_TIMESTAMP);
		boolean local = metaDict.getBoolean(MSG_KEY_LOCAL);
		UniqueId fileId = new UniqueId(metaDict.getRaw(MSG_KEY_FILE_ID));
		String fileName = metaDict.getString(MSG_KEY_FILE_NAME);
		String contentType = metaDict.getString(MSG_KEY_CONTENT_TYPE);
		long fileSize = metaDict.getLong(MSG_KEY_FILE_SIZE);
		int chunkTotal = metaDict.getInt(MSG_KEY_CHUNK_TOTAL);
		int received = metaDict.getInt(MSG_KEY_CHUNKS_RECEIVED);
		FileTransferHeader header = new FileTransferHeader(m.getId(), groupId,
				timestamp, local, false, false, false, NO_AUTO_DELETE_TIMER,
				fileId, fileName, contentType, fileSize, chunkTotal);
		ContactId contactId = getContactId(txn, groupId);
		txn.attach(new FileTransferReceivedEvent(header, contactId));
		conversationManager.trackIncomingMessage(txn, m);
		if (received >= chunkTotal) {
			tryAssemble(txn, groupId, fileId, m.getId());
		}
	}

	private void tryAssemble(Transaction txn, GroupId groupId, UniqueId fileId,
			MessageId headerMessageId) throws DbException, FormatException {
		BdfDictionary h =
				clientHelper.getMessageMetadataAsDictionary(txn, headerMessageId);
		String fileName = h.getString(MSG_KEY_FILE_NAME);
		int chunkTotal = h.getInt(MSG_KEY_CHUNK_TOTAL);
		File fileDir = getFileDir(fileId);
		File assembled = getAssembledFile(fileDir, fileName);
		if (assembled.exists()) return;
		assembleFile(fileDir, fileName, chunkTotal);
	}

	private void assembleFile(File fileDir, String fileName, int chunkTotal)
			throws DbException {
		File out = getAssembledFile(fileDir, fileName);
		if (out.exists()) return;
		try (OutputStream os = new FileOutputStream(out)) {
			for (int i = 0; i < chunkTotal; i++) {
				File chunk = getChunkFile(fileDir, i);
				if (!chunk.exists()) return; // chunks missing, try later
				try (InputStream is = new FileInputStream(chunk)) {
					copy(is, os);
				}
			}
		} catch (IOException e) {
			throw new DbException(e);
		}
	}

	private void copy(InputStream in, OutputStream out) throws IOException {
		byte[] buf = new byte[8192];
		int n;
		while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
	}

	private void writeBytes(File file, byte[] bytes) throws DbException {
		try (OutputStream os = new FileOutputStream(file)) {
			os.write(bytes);
		} catch (IOException e) {
			throw new DbException(e);
		}
	}

	@Override
	public FileTransferHeader sendFile(ContactId c, String fileName,
			String contentType, long fileSize, InputStream in)
			throws DbException, IOException {
		return db.transactionWithResult(false,
				txn -> sendFile(txn, c, fileName, contentType, fileSize, in));
	}

	private FileTransferHeader sendFile(Transaction txn, ContactId c,
			String fileName, String contentType, long fileSize, InputStream in)
			throws DbException, IOException {
		GroupId groupId = getContactGroup(db.getContact(txn, c)).getId();
		UniqueId fileId = generateFileId();
		int chunkTotal = fileSize == 0 ? 0
				: (int) Math.ceil((double) fileSize / CHUNK_SIZE);
		File fileDir = getFileDir(fileId);
		fileDir.mkdirs();
		long base = clockMillis();
		byte[] buf = new byte[CHUNK_SIZE];
		long totalRead = 0;
		int chunkIndex = 0;
		while (totalRead < fileSize) {
			int n = readFully(in, buf);
			if (n <= 0) break;
			byte[] payload = new byte[n];
			System.arraycopy(buf, 0, payload, 0, n);
			long timestamp = base + chunkIndex;
			BdfList body = BdfList.of(MSG_TYPE_CHUNK, fileId.getBytes(),
					chunkIndex, chunkTotal, payload);
			Message m = clientHelper.createMessage(groupId, timestamp, body);
			BdfDictionary meta = new BdfDictionary();
			meta.put(MSG_KEY_MSG_TYPE, MSG_TYPE_CHUNK);
			meta.put(MSG_KEY_FILE_ID, fileId.getBytes());
			meta.put(MSG_KEY_CHUNK_INDEX, chunkIndex);
			meta.put(MSG_KEY_CHUNK_TOTAL, chunkTotal);
			meta.put(MSG_KEY_LOCAL, true);
			meta.put(MSG_KEY_TIMESTAMP, timestamp);
			clientHelper.addLocalMessage(txn, m, meta, true, false);
			writeBytes(getChunkFile(fileDir, chunkIndex), payload);
			chunkIndex++;
			totalRead += n;
		}
		// Assemble the sender's own copy so getFile() works for the sender
		assembleFile(fileDir, fileName, chunkTotal);
		// Create the single header message
		long headerTimestamp = base + chunkTotal;
		BdfList headerBody = BdfList.of(MSG_TYPE_HEADER, fileId.getBytes(),
				fileName, contentType, fileSize, chunkTotal);
		Message headerMessage =
				clientHelper.createMessage(groupId, headerTimestamp, headerBody);
		BdfDictionary headerMeta = new BdfDictionary();
		headerMeta.put(MSG_KEY_MSG_TYPE, MSG_TYPE_HEADER);
		headerMeta.put(MSG_KEY_FILE_ID, fileId.getBytes());
		headerMeta.put(MSG_KEY_FILE_NAME, fileName);
		headerMeta.put(MSG_KEY_CONTENT_TYPE, contentType);
		headerMeta.put(MSG_KEY_FILE_SIZE, fileSize);
		headerMeta.put(MSG_KEY_CHUNK_TOTAL, chunkTotal);
		headerMeta.put(MSG_KEY_CHUNKS_RECEIVED, 0);
		headerMeta.put(MSG_KEY_LOCAL, true);
		headerMeta.put(MSG_KEY_READ, true);
		headerMeta.put(MSG_KEY_TIMESTAMP, headerTimestamp);
		clientHelper.addLocalMessage(txn, headerMessage, headerMeta, true,
				false);
		conversationManager.trackOutgoingMessage(txn, headerMessage);
		return new FileTransferHeader(headerMessage.getId(), groupId,
				headerTimestamp, true, true, false, false, NO_AUTO_DELETE_TIMER,
				fileId, fileName, contentType, fileSize, chunkTotal);
	}

	private int readFully(InputStream in, byte[] buf) throws IOException {
		int off = 0;
		while (off < buf.length) {
			int n = in.read(buf, off, buf.length - off);
			if (n < 0) break;
			off += n;
		}
		return off;
	}

	private long clockMillis() {
		return System.currentTimeMillis();
	}

	@Override
	@Nullable
	public InputStream getFile(FileTransferHeader h)
			throws DbException, IOException {
		UniqueId fileId = h.getFileId();
		File fileDir = getFileDir(fileId);
		File assembled = getAssembledFile(fileDir, h.getFileName());
		if (!assembled.exists()) return null;
		return new FileInputStream(assembled);
	}

	@Override
	public FileTransferProgress getProgress(FileTransferHeader h)
			throws DbException {
		if (h.isLocal()) {
			return db.transactionWithResult(true,
					txn -> getOutgoingProgress(txn, h));
		}
		return db.transactionWithResult(true,
				txn -> getIncomingProgress(txn, h));
	}

	private FileTransferProgress getOutgoingProgress(Transaction txn,
			FileTransferHeader h) throws DbException {
		GroupId groupId = h.getGroupId();
		ContactId contactId = getContactId(txn, groupId);
		UniqueId fileId = h.getFileId();
		BdfDictionary query = BdfDictionary.of(
				new BdfEntry(MSG_KEY_FILE_ID, fileId.getBytes()),
				new BdfEntry(MSG_KEY_MSG_TYPE, MSG_TYPE_CHUNK));
		Collection<MessageId> chunkIds;
		try {
			chunkIds =
					clientHelper.getMessageIds(txn, groupId, query);
		} catch (FormatException e) {
			throw new DbException(e);
		}
		int transferredChunks = 0;
		for (MessageId id : chunkIds) {
			MessageStatus status = db.getMessageStatus(txn, contactId, id);
			if (status.isSent()) transferredChunks++;
		}
		int chunkTotal = h.getChunkTotal();
		State state =
				transferredChunks >= chunkTotal ? State.COMPLETE : State.TRANSFERRING;
		long transferred = (long) transferredChunks * CHUNK_SIZE;
		return new FileTransferProgress(state, transferred, h.getFileSize());
	}

	private FileTransferProgress getIncomingProgress(Transaction txn,
			FileTransferHeader h) throws DbException {
		UniqueId fileId = h.getFileId();
		GroupId groupId = h.getGroupId();
		BdfDictionary query = BdfDictionary.of(
				new BdfEntry(MSG_KEY_FILE_ID, fileId.getBytes()),
				new BdfEntry(MSG_KEY_MSG_TYPE, MSG_TYPE_HEADER));
		int received = 0;
		try {
			Map<MessageId, BdfDictionary> headers =
					clientHelper.getMessageMetadataAsDictionary(txn, groupId,
							query);
			for (BdfDictionary hd : headers.values()) {
				received = hd.getInt(MSG_KEY_CHUNKS_RECEIVED);
			}
		} catch (FormatException e) {
			throw new DbException(e);
		}
		int chunkTotal = h.getChunkTotal();
		State state =
				received >= chunkTotal ? State.COMPLETE : State.TRANSFERRING;
		long transferred = (long) received * CHUNK_SIZE;
		return new FileTransferProgress(state, transferred, h.getFileSize());
	}

	@Override
	@Nullable
	public FileTransferHeader getFileTransferHeader(MessageId m)
			throws DbException {
		return db.transactionWithResult(true,
				txn -> getFileTransferHeader(txn, m));
	}

	private FileTransferHeader getFileTransferHeader(Transaction txn,
			MessageId m) throws DbException {
		try {
			BdfDictionary meta =
					clientHelper.getMessageMetadataAsDictionary(txn, m);
			if (!MSG_TYPE_HEADER.equals(meta.getString(MSG_KEY_MSG_TYPE)))
				return null;
			return buildHeader(txn, m, meta, true, true, false, false);
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	private FileTransferHeader buildHeader(Transaction txn, MessageId id,
			BdfDictionary meta, boolean local, boolean read, boolean sent,
			boolean seen) throws DbException, FormatException {
		GroupId groupId = clientHelper.getMessage(txn, id).getGroupId();
		long timestamp = meta.getLong(MSG_KEY_TIMESTAMP);
		UniqueId fileId = new UniqueId(meta.getRaw(MSG_KEY_FILE_ID));
		String fileName = meta.getString(MSG_KEY_FILE_NAME);
		String contentType = meta.getString(MSG_KEY_CONTENT_TYPE);
		long fileSize = meta.getLong(MSG_KEY_FILE_SIZE);
		int chunkTotal = meta.getInt(MSG_KEY_CHUNK_TOTAL);
		return new FileTransferHeader(id, groupId, timestamp, local, read, sent,
				seen, NO_AUTO_DELETE_TIMER, fileId, fileName, contentType,
				fileSize, chunkTotal);
	}

	@Override
	public Collection<ConversationMessageHeader> getMessageHeaders(
			Transaction txn, ContactId c) throws DbException {
		Map<MessageId, BdfDictionary> metadata;
		Collection<MessageStatus> statuses;
		GroupId g;
		try {
			g = getContactGroup(db.getContact(txn, c)).getId();
			metadata = clientHelper.getMessageMetadataAsDictionary(txn, g);
			statuses = db.getMessageStatus(txn, c, g);
		} catch (FormatException e) {
			throw new DbException(e);
		}
		Collection<ConversationMessageHeader> headers = new ArrayList<>();
		for (MessageStatus s : statuses) {
			MessageId id = s.getMessageId();
			BdfDictionary meta = metadata.get(id);
			if (meta == null) continue;
			try {
				if (!MSG_TYPE_HEADER.equals(meta.getString(MSG_KEY_MSG_TYPE)))
					continue;
				boolean local = meta.getBoolean(MSG_KEY_LOCAL);
				boolean read = meta.getBoolean(MSG_KEY_READ, true);
				headers.add(buildHeader(txn, id, meta, local, read,
						s.isSent(), s.isSeen()));
			} catch (FormatException e) {
				throw new DbException(e);
			}
		}
		return headers;
	}

	@Override
	public Set<MessageId> getMessageIds(Transaction txn, ContactId c)
			throws DbException {
		GroupId g = getContactGroup(db.getContact(txn, c)).getId();
		Set<MessageId> result = new HashSet<>();
		try {
			Map<MessageId, BdfDictionary> messages =
					clientHelper.getMessageMetadataAsDictionary(txn, g);
			for (Entry<MessageId, BdfDictionary> entry : messages.entrySet()) {
				if (MSG_TYPE_HEADER.equals(
						entry.getValue().getString(MSG_KEY_MSG_TYPE))) {
					result.add(entry.getKey());
				}
			}
		} catch (FormatException e) {
			throw new DbException(e);
		}
		return result;
	}

	private ContactId getContactId(Transaction txn, GroupId g)
			throws DbException {
		try {
			BdfDictionary meta =
					clientHelper.getGroupMetadataAsDictionary(txn, g);
			return new ContactId(meta.getInt(GROUP_KEY_CONTACT_ID));
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	public void setReadFlag(Transaction txn, GroupId g, MessageId m,
			boolean read) throws DbException {
		messageTracker.setReadFlag(txn, g, m, read);
	}

	@Override
	public DeletionResult deleteAllMessages(Transaction txn, ContactId c)
			throws DbException {
		GroupId g = getContactGroup(db.getContact(txn, c)).getId();
		for (MessageId messageId : db.getMessageIds(txn, g)) {
			db.deleteMessage(txn, messageId);
			db.deleteMessageMetadata(txn, messageId);
		}
		messageTracker.initializeGroupCount(txn, g);
		return new DeletionResult();
	}

	@Override
	public DeletionResult deleteMessages(Transaction txn, ContactId c,
			Set<MessageId> messageIds) throws DbException {
		GroupId g = getContactGroup(db.getContact(txn, c)).getId();
		for (MessageId m : messageIds) {
			db.deleteMessage(txn, m);
			db.deleteMessageMetadata(txn, m);
		}
		messageTracker.resetGroupCount(txn, g, 0, 0);
		return new DeletionResult();
	}

	@Override
	public void deleteMessages(Transaction txn, GroupId g,
			Collection<MessageId> messageIds) throws DbException {
		for (MessageId m : messageIds) {
			db.deleteMessage(txn, m);
			db.deleteMessageMetadata(txn, m);
		}
	}
}
