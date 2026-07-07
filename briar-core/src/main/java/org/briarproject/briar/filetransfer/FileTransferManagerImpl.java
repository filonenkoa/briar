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
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
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
	private static final int GROUP_STATUS_SCAN_THRESHOLD = 1024;
	private static final long PENDING_DELETE_WAIT_MS = 60_000;

	private final DatabaseComponent db;
	private final ClientHelper clientHelper;
	private final ClientVersioningManager clientVersioningManager;
	private final ContactGroupFactory contactGroupFactory;
	private final MessageTracker messageTracker;
	private final ConversationManager conversationManager;
	private final MetadataParser metadataParser;
	private final EventBus eventBus;
	private final DatabaseConfig databaseConfig;
	private final Map<UniqueId, Collection<MessageId>> outgoingChunkIdCache =
			new ConcurrentHashMap<>();
	private final Set<DeletedChunk> pendingDeletedChunks =
			ConcurrentHashMap.newKeySet();
	private final Map<ChunkKey, Object> chunkLocks =
			new ConcurrentHashMap<>();

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

	private File getChunksDir(File fileDir) {
		return new File(fileDir, "chunks");
	}

	private File getAssembledDir(File fileDir) {
		return new File(fileDir, "assembled");
	}

	private File getChunkFile(File fileDir, int chunkIndex) {
		return new File(getChunksDir(fileDir), "chunk_" + chunkIndex);
	}

	private File getChunkTotalFile(File fileDir, int chunkIndex) {
		File chunk = getChunkFile(fileDir, chunkIndex);
		return new File(chunk.getParentFile(), chunk.getName() + ".total");
	}

	private File getAssembledFile(File fileDir, String fileName) {
		return new File(getAssembledDir(fileDir), fileName);
	}

	private boolean hasExpectedAssembledFile(File fileDir, BdfDictionary header)
			throws FormatException {
		String fileName = header.getString(MSG_KEY_FILE_NAME);
		long fileSize = header.getLong(MSG_KEY_FILE_SIZE);
		File assembled = getAssembledFile(fileDir, safeFileName(fileName));
		return assembled.exists() && assembled.length() == fileSize;
	}

	private String safeFileName(String fileName) {
		String name = new File(fileName).getName();
		if (name.isEmpty() || name.equals(".") || name.equals("..")) {
			return "file";
		}
		return name;
	}

	private int countExistingChunks(File fileDir, int chunkTotal) {
		int count = 0;
		for (int i = 0; i < chunkTotal; i++) {
			if (chunkExistsWithTotal(fileDir, i, chunkTotal)) count++;
		}
		return count;
	}

	private int countExistingChunks(UniqueId fileId, File fileDir,
			int chunkTotal) {
		return countExistingChunks(fileId, fileDir, chunkTotal,
				new ArrayList<>());
	}

	private int countExistingChunks(UniqueId fileId, File fileDir,
			int chunkTotal, Collection<DeletedChunk> deletedChunks) {
		int count = 0;
		for (int i = 0; i < chunkTotal; i++) {
			DeletedChunk chunk = new DeletedChunk(fileId, i, chunkTotal);
			synchronized (getChunkLock(chunk)) {
				if (!deletedChunks.contains(chunk) && !hasPendingDelete(chunk) &&
						chunkExistsWithTotal(fileDir, i, chunkTotal)) count++;
			}
		}
		return count;
	}

	private boolean chunkExistsWithTotal(File fileDir, int chunkIndex,
			int chunkTotal) {
		if (!getChunkFile(fileDir, chunkIndex).exists()) return false;
		File totalFile = getChunkTotalFile(fileDir, chunkIndex);
		if (!totalFile.exists()) return false;
		try (InputStream is = new FileInputStream(totalFile)) {
			if (totalFile.length() <= 0 || totalFile.length() > 16) return false;
			byte[] bytes = new byte[(int) totalFile.length()];
			int off = 0;
			while (off < bytes.length) {
				int read = is.read(bytes, off, bytes.length - off);
				if (read == -1) return false;
				off += read;
			}
			if (is.read() != -1) return false;
			String total = new String(bytes, StandardCharsets.UTF_8);
			return Integer.parseInt(total) == chunkTotal;
		} catch (IOException | NumberFormatException e) {
			return false;
		}
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
		Group g = getContactGroup(c);
		Set<UniqueId> fileIds = getFileIds(txn, g.getId());
		invalidateOutgoingProgressCache(fileIds);
		for (UniqueId fileId : fileIds) scheduleDeleteFileDir(txn, fileId);
		db.removeGroup(txn, g);
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
		int chunkTotal = metaDict.getInt(MSG_KEY_CHUNK_TOTAL);
		File fileDir = getFileDir(fileId);
		// Find the header message for this file and increment its received count
		BdfDictionary query = BdfDictionary.of(
				new BdfEntry(MSG_KEY_FILE_ID, fileId.getBytes()),
				new BdfEntry(MSG_KEY_MSG_TYPE, MSG_TYPE_HEADER));
		Map<MessageId, BdfDictionary> headers =
				clientHelper.getMessageMetadataAsDictionary(txn, groupId, query);
		boolean matchingHeader = false;
		for (Entry<MessageId, BdfDictionary> e : headers.entrySet()) {
			BdfDictionary h = e.getValue();
			int total = h.getInt(MSG_KEY_CHUNK_TOTAL);
			if (chunkTotal == total && chunkIndex < total) {
				matchingHeader = true;
				int received = h.getInt(MSG_KEY_CHUNKS_RECEIVED);
				if (received >= total) return;
				if (hasExpectedAssembledFile(fileDir, h)) {
					setChunksReceived(txn, e.getKey(), total);
					return;
				}
			}
		}
		if (!headers.isEmpty() && !matchingHeader) return;
		DeletedChunk chunk = new DeletedChunk(fileId, chunkIndex, chunkTotal);
		boolean duplicate;
		boolean stored = false;
		Object lock = getChunkLock(chunk);
		synchronized (lock) {
			waitForPendingDelete(chunk, lock);
			boolean diskChunk = chunkExistsWithTotal(fileDir, chunkIndex,
					chunkTotal);
			boolean liveChunk = diskChunk && hasLiveChunkMetadata(txn, groupId,
					fileId, chunkIndex, chunkTotal, m.getId());
			duplicate = diskChunk && liveChunk;
			if (!duplicate) {
				BdfList body = clientHelper.getMessageAsList(txn, m.getId());
				byte[] payload = body.getRaw(4);
				stored = writeChunk(fileDir, chunkIndex, chunkTotal, payload,
						matchingHeader, diskChunk);
			}
		}
		if (headers.isEmpty()) return;
		for (Entry<MessageId, BdfDictionary> e : headers.entrySet()) {
			BdfDictionary h = e.getValue();
			int received = h.getInt(MSG_KEY_CHUNKS_RECEIVED);
			int total = h.getInt(MSG_KEY_CHUNK_TOTAL);
			if (chunkTotal != total || chunkIndex >= total) continue;
			int newReceived = received;
			if (duplicate) {
				int actualReceived = countExistingChunks(fileId, fileDir, total);
				if (actualReceived > received) newReceived = actualReceived;
			} else if (stored) {
				newReceived = received + 1;
			}
			if (newReceived >= total) {
				if (tryAssemble(txn, groupId, fileId, e.getKey())) {
					setChunksReceived(txn, e.getKey(), total);
				} else {
					int actualReceived = countExistingChunks(fileId, fileDir,
							total);
					if (actualReceived != received) {
						setChunksReceived(txn, e.getKey(), actualReceived);
					}
				}
			} else if (newReceived > received) {
				setChunksReceived(txn, e.getKey(), newReceived);
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
		boolean read = metaDict.getBoolean(MSG_KEY_READ);
		int received = metaDict.getInt(MSG_KEY_CHUNKS_RECEIVED);
		File fileDir = getFileDir(fileId);
		int actualReceived = countExistingChunks(fileId, fileDir, chunkTotal);
		if (actualReceived > received) {
			if (actualReceived >= chunkTotal) {
				if (tryAssemble(txn, groupId, fileId, m.getId())) {
					setChunksReceived(txn, m.getId(), chunkTotal);
					received = chunkTotal;
				}
			} else {
				setChunksReceived(txn, m.getId(), actualReceived);
				received = actualReceived;
			}
		}
		File assembled = getAssembledFile(fileDir, safeFileName(fileName));
		if (assembled.exists() && assembled.length() == fileSize &&
				received < chunkTotal) {
			setChunksReceived(txn, m.getId(), chunkTotal);
			received = chunkTotal;
		}
		FileTransferHeader header = new FileTransferHeader(m.getId(), groupId,
				timestamp, local, read, false, false, NO_AUTO_DELETE_TIMER,
				fileId, fileName, contentType, fileSize, chunkTotal);
		ContactId contactId = getContactId(txn, groupId);
		txn.attach(new FileTransferReceivedEvent(header, contactId));
		conversationManager.trackIncomingMessage(txn, m);
		if (received >= chunkTotal &&
				!tryAssemble(txn, groupId, fileId, m.getId())) {
			actualReceived = countExistingChunks(fileId, fileDir, chunkTotal);
			if (actualReceived != received) {
				setChunksReceived(txn, m.getId(), actualReceived);
			}
		}
	}

	private boolean tryAssemble(Transaction txn, GroupId groupId, UniqueId fileId,
			MessageId headerMessageId) throws DbException, FormatException {
		BdfDictionary h =
				clientHelper.getMessageMetadataAsDictionary(txn, headerMessageId);
		String fileName = h.getString(MSG_KEY_FILE_NAME);
		int chunkTotal = h.getInt(MSG_KEY_CHUNK_TOTAL);
		long fileSize = h.getLong(MSG_KEY_FILE_SIZE);
		File fileDir = getFileDir(fileId);
		File assembled = getAssembledFile(fileDir, safeFileName(fileName));
		if (assembled.exists()) {
			if (assembled.length() == fileSize) {
				deleteRecursively(getChunksDir(fileDir));
				return true;
			}
			if (!assembled.delete()) throw new DbException();
		}
		return assembleFile(fileId, fileDir, fileName, chunkTotal, fileSize);
	}

	private void setChunksReceived(Transaction txn, MessageId messageId,
			int received) throws DbException, FormatException {
		BdfDictionary merge = new BdfDictionary();
		merge.put(MSG_KEY_CHUNKS_RECEIVED, received);
		clientHelper.mergeMessageMetadata(txn, messageId, merge);
	}

	private boolean assembleFile(UniqueId fileId, File fileDir, String fileName,
			int chunkTotal, long expectedSize)
			throws DbException {
		String safeName = safeFileName(fileName);
		File out = getAssembledFile(fileDir, safeName);
		if (out.exists() && out.length() == expectedSize) return true;
		if (out.exists() && !out.delete()) throw new DbException();
		try {
			File assembledDir = out.getParentFile();
			if (!assembledDir.exists() && !assembledDir.mkdirs()) {
				throw new IOException();
			}
			if (chunkTotal == 0) {
				if (expectedSize != 0) return false;
				if (!out.exists() && !out.createNewFile()) throw new IOException();
				return true;
			}
			for (int i = 0; i < chunkTotal; i++) {
				DeletedChunk chunk = new DeletedChunk(fileId, i, chunkTotal);
				synchronized (getChunkLock(chunk)) {
					if (hasPendingDelete(chunk) ||
							!chunkExistsWithTotal(fileDir, i, chunkTotal)) {
						return false;
					}
				}
			}
		} catch (IOException e) {
			throw new DbException(e);
		}
		File tmp = new File(out.getParentFile(), safeName + ".tmp");
		try (OutputStream os = new FileOutputStream(tmp)) {
			for (int i = 0; i < chunkTotal; i++) {
				DeletedChunk deletedChunk = new DeletedChunk(fileId, i,
						chunkTotal);
				synchronized (getChunkLock(deletedChunk)) {
					if (hasPendingDelete(deletedChunk) ||
							!chunkExistsWithTotal(fileDir, i, chunkTotal)) {
						tmp.delete();
						return false;
					}
					File chunk = getChunkFile(fileDir, i);
					try (InputStream is = new FileInputStream(chunk)) {
						copy(is, os);
					}
				}
			}
		} catch (IOException e) {
			tmp.delete();
			throw new DbException(e);
		}
		if (tmp.length() != expectedSize) {
			tmp.delete();
			deleteRecursively(getChunksDir(fileDir));
			return false;
		}
		if (!tmp.renameTo(out)) {
			tmp.delete();
			throw new DbException();
		}
		deleteRecursively(getChunksDir(fileDir));
		return true;
	}

	private void copy(InputStream in, OutputStream out) throws IOException {
		byte[] buf = new byte[8192];
		int n;
		while ((n = in.read(buf)) != -1) out.write(buf, 0, n);
	}

	private void deleteFileDir(UniqueId fileId) {
		deleteRecursively(getFileDir(fileId));
	}

	private void scheduleDeleteFileDir(Transaction txn, UniqueId fileId) {
		txn.attach(() -> deleteFileDir(fileId));
	}

	private void scheduleDeleteChunkFiles(Transaction txn, DeletedChunk chunk) {
		txn.attachSync(() -> {
			Object lock = getChunkLock(chunk);
			synchronized (lock) {
				pendingDeletedChunks.add(chunk);
				lock.notifyAll();
			}
		});
		txn.attach(() -> {
			Object lock = getChunkLock(chunk);
			synchronized (lock) {
				try {
					deleteChunkFiles(chunk.fileId, chunk.chunkIndex,
							chunk.chunkTotal);
				} finally {
					pendingDeletedChunks.remove(chunk);
					lock.notifyAll();
				}
			}
		});
	}

	private Object getChunkLock(DeletedChunk chunk) {
		ChunkKey key = new ChunkKey(chunk.fileId, chunk.chunkIndex);
		return chunkLocks.computeIfAbsent(key, c -> new Object());
	}

	private boolean hasPendingDelete(DeletedChunk chunk) {
		return pendingDeletedChunks.contains(chunk);
	}

	private void waitForPendingDelete(DeletedChunk chunk, Object lock)
			throws DbException {
		while (hasPendingDelete(chunk)) {
			try {
				lock.wait(PENDING_DELETE_WAIT_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				throw new DbException(e);
			}
		}
	}

	private boolean hasLiveChunkMetadata(Transaction txn, GroupId groupId,
			UniqueId fileId, int chunkIndex, int chunkTotal,
			MessageId currentMessageId)
			throws DbException, FormatException {
		BdfDictionary query = BdfDictionary.of(
				new BdfEntry(MSG_KEY_FILE_ID, fileId.getBytes()),
				new BdfEntry(MSG_KEY_MSG_TYPE, MSG_TYPE_CHUNK),
				new BdfEntry(MSG_KEY_CHUNK_INDEX, chunkIndex),
				new BdfEntry(MSG_KEY_CHUNK_TOTAL, chunkTotal));
		for (MessageId messageId : clientHelper.getMessageIds(txn, groupId,
				query)) {
			if (!messageId.equals(currentMessageId)) return true;
		}
		return false;
	}

	private boolean hasRemainingChunkMetadata(Transaction txn, GroupId groupId,
			DeletedChunk chunk, Set<MessageId> deleted)
			throws DbException, FormatException {
		BdfDictionary query = BdfDictionary.of(
				new BdfEntry(MSG_KEY_FILE_ID, chunk.fileId.getBytes()),
				new BdfEntry(MSG_KEY_MSG_TYPE, MSG_TYPE_CHUNK),
				new BdfEntry(MSG_KEY_CHUNK_INDEX, chunk.chunkIndex),
				new BdfEntry(MSG_KEY_CHUNK_TOTAL, chunk.chunkTotal));
		for (MessageId messageId : clientHelper.getMessageIds(txn, groupId,
				query)) {
			if (!deleted.contains(messageId)) return true;
		}
		return false;
	}

	private void deleteChunkFiles(UniqueId fileId, int chunkIndex,
			int chunkTotal) {
		File fileDir = getFileDir(fileId);
		if (!chunkExistsWithTotal(fileDir, chunkIndex, chunkTotal)) return;
		File chunk = getChunkFile(fileDir, chunkIndex);
		File total = getChunkTotalFile(fileDir, chunkIndex);
		chunk.delete();
		total.delete();
		File chunksDir = getChunksDir(fileDir);
		String[] files = chunksDir.list();
		if (files != null && files.length == 0) chunksDir.delete();
	}

	private void deleteRecursively(File f) {
		if (!f.exists()) return;
		if (f.isDirectory()) {
			File[] files = f.listFiles();
			if (files != null) {
				for (File child : files) deleteRecursively(child);
			}
		}
		f.delete();
	}

	private void deleteAfterFailedSend(File fileDir) {
		deleteRecursively(fileDir);
		if (fileDir.exists()) {
			LOG.info("Could not delete failed file transfer");
		}
	}

	private void writeBytes(File file, byte[] bytes) throws DbException {
		File dir = file.getParentFile();
		if (dir != null && !dir.exists() && !dir.mkdirs()) {
			throw new DbException();
		}
		try (OutputStream os = new FileOutputStream(file)) {
			os.write(bytes);
		} catch (IOException e) {
			throw new DbException(e);
		}
	}

	private boolean writeChunk(File fileDir, int chunkIndex, int chunkTotal,
			byte[] payload) throws DbException {
		return writeChunk(fileDir, chunkIndex, chunkTotal, payload, false, false);
	}

	private boolean writeChunk(File fileDir, int chunkIndex, int chunkTotal,
			byte[] payload, boolean replaceMismatchedTotal) throws DbException {
		return writeChunk(fileDir, chunkIndex, chunkTotal, payload,
				replaceMismatchedTotal, false);
	}

	private boolean writeChunk(File fileDir, int chunkIndex, int chunkTotal,
			byte[] payload, boolean replaceMismatchedTotal,
			boolean replaceExisting) throws DbException {
		File chunk = getChunkFile(fileDir, chunkIndex);
		File total = getChunkTotalFile(fileDir, chunkIndex);
		if (chunk.exists()) {
			if (chunkExistsWithTotal(fileDir, chunkIndex, chunkTotal) &&
					!replaceExisting) return false;
			if (total.exists() && !replaceMismatchedTotal && !replaceExisting) {
				return false;
			}
			if (total.exists() && !total.delete()) throw new DbException();
			if (!chunk.delete()) throw new DbException();
		} else if (total.exists() && !total.delete()) {
			throw new DbException();
		}
		File chunkTmp = new File(chunk.getParentFile(), chunk.getName() + ".tmp");
		File totalTmp = new File(total.getParentFile(), total.getName() + ".tmp");
		byte[] totalBytes = Integer.toString(chunkTotal)
				.getBytes(StandardCharsets.UTF_8);
		try {
			writeBytes(chunkTmp, payload);
			writeBytes(totalTmp, totalBytes);
			if (!totalTmp.renameTo(total)) throw new IOException();
			if (!chunkTmp.renameTo(chunk)) throw new IOException();
			return true;
		} catch (IOException | DbException e) {
			chunkTmp.delete();
			totalTmp.delete();
			if (!chunk.exists()) total.delete();
			if (e instanceof DbException) throw (DbException) e;
			throw new DbException(e);
		}
	}

	@Override
	public FileTransferHeader sendFile(ContactId c, String fileName,
			String contentType, long fileSize, InputStream in)
			throws DbException, IOException {
		UniqueId fileId = generateFileId();
		try {
			return db.transactionWithResult(false, txn -> sendFile(txn, c,
					fileName, contentType, fileSize, in, fileId));
		} catch (DbException | IOException e) {
			deleteAfterFailedSend(getFileDir(fileId));
			throw e;
		}
	}

	private FileTransferHeader sendFile(Transaction txn, ContactId c,
			String fileName, String contentType, long fileSize, InputStream in)
			throws DbException, IOException {
		return sendFile(txn, c, fileName, contentType, fileSize, in,
				generateFileId());
	}

	private FileTransferHeader sendFile(Transaction txn, ContactId c,
			String fileName, String contentType, long fileSize, InputStream in,
			UniqueId fileId) throws DbException, IOException {
		int chunkTotal = fileSize == 0 ? 0 :
				(int) ((fileSize + CHUNK_SIZE - 1) / CHUNK_SIZE);
		try {
			FileTransferValidator.validateHeaderFields(fileName, contentType,
					fileSize, chunkTotal);
		} catch (FormatException e) {
			throw new IOException(e);
		}
		GroupId groupId = getContactGroup(db.getContact(txn, c)).getId();
		File fileDir = getFileDir(fileId);
		long base = clockMillis();
		long headerTimestamp = base;
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
		try {
			byte[] buf = new byte[CHUNK_SIZE];
			long totalRead = 0;
			int chunkIndex = 0;
			while (totalRead < fileSize) {
				int max = (int) Math.min(CHUNK_SIZE, fileSize - totalRead);
				int n = readFully(in, buf, max);
				if (n < max) {
					throw new IOException("Expected " + fileSize +
							" bytes but read " + (totalRead + n));
				}
				byte[] payload = new byte[n];
				System.arraycopy(buf, 0, payload, 0, n);
				long timestamp = base + chunkIndex + 1;
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
				writeChunk(fileDir, chunkIndex, chunkTotal, payload);
				chunkIndex++;
				totalRead += n;
			}
			if (totalRead != fileSize) {
				throw new IOException("Expected " + fileSize +
						" bytes but read " + totalRead);
			}
			// Assemble the sender's own copy so getFile() works for the sender
			assembleFile(fileId, fileDir, fileName, chunkTotal, fileSize);
		} catch (DbException | IOException e) {
			deleteAfterFailedSend(fileDir);
			throw e;
		}
		return new FileTransferHeader(headerMessage.getId(), groupId,
				headerTimestamp, true, true, false, false, NO_AUTO_DELETE_TIMER,
				fileId, fileName, contentType, fileSize, chunkTotal);
	}

	private int readFully(InputStream in, byte[] buf, int max)
			throws IOException {
		int off = 0;
		while (off < max) {
			int n = in.read(buf, off, max - off);
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
		File assembled =
				getAssembledFile(fileDir, safeFileName(h.getFileName()));
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
		Collection<MessageId> chunkIds = getOutgoingChunkIds(txn, groupId, fileId);
		int transferredChunks = 0;
		if (chunkIds.size() <= GROUP_STATUS_SCAN_THRESHOLD) {
			for (MessageId id : chunkIds) {
				MessageStatus s = db.getMessageStatus(txn, contactId, id);
				if (s.isSent()) transferredChunks++;
			}
		} else {
			Collection<MessageId> chunkIdSet = chunkIds instanceof Set ?
					chunkIds : new HashSet<>(chunkIds);
			for (MessageStatus s : db.getMessageStatus(txn, contactId, groupId)) {
				if (chunkIdSet.contains(s.getMessageId()) && s.isSent()) {
					transferredChunks++;
				}
			}
		}
		int chunkTotal = h.getChunkTotal();
		State state =
				transferredChunks >= chunkTotal ? State.COMPLETE : State.TRANSFERRING;
		long transferred = Math.min(h.getFileSize(),
				(long) transferredChunks * CHUNK_SIZE);
		return new FileTransferProgress(state, transferred, h.getFileSize());
	}

	private Collection<MessageId> getOutgoingChunkIds(Transaction txn,
			GroupId groupId, UniqueId fileId) throws DbException {
		Collection<MessageId> chunkIds = outgoingChunkIdCache.get(fileId);
		if (chunkIds != null) return chunkIds;
		BdfDictionary query = BdfDictionary.of(
				new BdfEntry(MSG_KEY_FILE_ID, fileId.getBytes()),
				new BdfEntry(MSG_KEY_MSG_TYPE, MSG_TYPE_CHUNK));
		try {
			chunkIds = new HashSet<>(clientHelper.getMessageIds(txn, groupId,
					query));
			outgoingChunkIdCache.put(fileId, chunkIds);
			return chunkIds;
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	private void invalidateOutgoingProgressCache(Collection<UniqueId> fileIds) {
		for (UniqueId fileId : fileIds) outgoingChunkIdCache.remove(fileId);
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
		long transferred = Math.min(h.getFileSize(),
				(long) received * CHUNK_SIZE);
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
			boolean local = meta.getBoolean(MSG_KEY_LOCAL);
			boolean read = getReadFlag(meta);
			return buildHeader(txn, m, meta, local, read, false, false);
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

	private boolean getReadFlag(BdfDictionary meta) throws FormatException {
		Boolean read = meta.getOptionalBoolean(MSG_KEY_READ);
		return read == null ? meta.getBoolean(MSG_KEY_LOCAL) : read;
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
				boolean read = getReadFlag(meta);
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
		BdfDictionary meta = new BdfDictionary();
		meta.put(MSG_KEY_READ, read);
		try {
			clientHelper.mergeMessageMetadata(txn, m, meta);
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	@Override
	public DeletionResult deleteAllMessages(Transaction txn, ContactId c)
			throws DbException {
		GroupId g = getContactGroup(db.getContact(txn, c)).getId();
		Set<UniqueId> fileIds = getFileIds(txn, g);
		for (MessageId messageId : db.getMessageIds(txn, g)) {
			db.deleteMessage(txn, messageId);
			db.deleteMessageMetadata(txn, messageId);
		}
		invalidateOutgoingProgressCache(fileIds);
		for (UniqueId fileId : fileIds) scheduleDeleteFileDir(txn, fileId);
		messageTracker.initializeGroupCount(txn, g);
		return new DeletionResult();
	}

	private Set<UniqueId> getFileIds(Transaction txn, GroupId g)
			throws DbException {
		Set<UniqueId> fileIds = new HashSet<>();
		try {
			Map<MessageId, BdfDictionary> metadata =
					clientHelper.getMessageMetadataAsDictionary(txn, g);
			for (BdfDictionary meta : metadata.values()) {
				byte[] fileId = meta.getOptionalRaw(MSG_KEY_FILE_ID);
				if (fileId != null) fileIds.add(new UniqueId(fileId));
			}
		} catch (FormatException e) {
			throw new DbException(e);
		}
		return fileIds;
	}

	@Override
	public DeletionResult deleteMessages(Transaction txn, ContactId c,
			Set<MessageId> messageIds) throws DbException {
		GroupId g = getContactGroup(db.getContact(txn, c)).getId();
		deleteMessages(txn, g, messageIds);
		return new DeletionResult();
	}

	@Override
	public void deleteMessages(Transaction txn, GroupId g,
			Collection<MessageId> messageIds) throws DbException {
		try {
			Set<MessageId> deleted = new HashSet<>();
			Set<UniqueId> fileIdsToDelete = new HashSet<>();
			Set<UniqueId> chunkFileIds = new HashSet<>();
			Collection<DeletedChunk> deletedChunks = new ArrayList<>();
			for (MessageId m : messageIds) {
				deleteMessage(txn, g, m, deleted, fileIdsToDelete,
						chunkFileIds, deletedChunks);
			}
			for (UniqueId fileId : chunkFileIds) {
				Map<MessageId, BdfDictionary> remainingHeaders =
						getRemainingHeaders(txn, g, fileId, deleted);
				if (remainingHeaders.isEmpty()) {
					fileIdsToDelete.add(fileId);
				} else {
					repairDeletedChunks(txn, g, fileId, remainingHeaders,
							deletedChunks, deleted);
				}
			}
			invalidateOutgoingProgressCache(chunkFileIds);
			invalidateOutgoingProgressCache(fileIdsToDelete);
			for (UniqueId fileId : fileIdsToDelete) {
				scheduleDeleteFileDir(txn, fileId);
			}
			recalculateGroupCount(txn, g);
		} catch (FormatException e) {
			throw new DbException(e);
		}
	}

	private void deleteMessage(Transaction txn, GroupId g, MessageId m,
			Set<MessageId> deleted, Set<UniqueId> fileIdsToDelete,
			Set<UniqueId> chunkFileIds, Collection<DeletedChunk> deletedChunks)
			throws DbException, FormatException {
		if (deleted.contains(m)) return;
		BdfDictionary meta = clientHelper.getMessageMetadataAsDictionary(txn, m);
		if (MSG_TYPE_HEADER.equals(meta.getOptionalString(MSG_KEY_MSG_TYPE))) {
			UniqueId fileId = new UniqueId(meta.getRaw(MSG_KEY_FILE_ID));
			BdfDictionary query = BdfDictionary.of(
					new BdfEntry(MSG_KEY_FILE_ID, fileId.getBytes()));
			for (MessageId related : clientHelper.getMessageIds(txn, g, query)) {
				deleteMessageRows(txn, related, deleted);
			}
			fileIdsToDelete.add(fileId);
		} else {
			byte[] fileId = meta.getOptionalRaw(MSG_KEY_FILE_ID);
			if (fileId != null) {
				UniqueId id = new UniqueId(fileId);
				chunkFileIds.add(id);
				Integer index = meta.getOptionalInt(MSG_KEY_CHUNK_INDEX);
				Integer total = meta.getOptionalInt(MSG_KEY_CHUNK_TOTAL);
				if (index != null && total != null) {
					deletedChunks.add(new DeletedChunk(id, index, total));
				}
			}
			deleteMessageRows(txn, m, deleted);
		}
	}

	private Map<MessageId, BdfDictionary> getRemainingHeaders(Transaction txn,
			GroupId g,
			UniqueId fileId, Set<MessageId> deleted)
			throws DbException, FormatException {
		BdfDictionary query = BdfDictionary.of(
				new BdfEntry(MSG_KEY_FILE_ID, fileId.getBytes()),
				new BdfEntry(MSG_KEY_MSG_TYPE, MSG_TYPE_HEADER));
		Map<MessageId, BdfDictionary> headers =
				clientHelper.getMessageMetadataAsDictionary(txn, g, query);
		for (MessageId headerId : new ArrayList<>(headers.keySet())) {
			if (deleted.contains(headerId)) headers.remove(headerId);
		}
		return headers;
	}

	private void repairDeletedChunks(Transaction txn, GroupId groupId,
			UniqueId fileId,
			Map<MessageId, BdfDictionary> remainingHeaders,
			Collection<DeletedChunk> deletedChunks, Set<MessageId> deleted)
			throws DbException, FormatException {
		Collection<DeletedChunk> matching = new ArrayList<>();
		for (DeletedChunk chunk : deletedChunks) {
			if (chunk.fileId.equals(fileId)) matching.add(chunk);
		}
		if (matching.isEmpty()) return;
		File fileDir = getFileDir(fileId);
		Set<DeletedChunk> chunksToDelete = new HashSet<>();
		for (DeletedChunk chunk : matching) {
			if (!hasRemainingChunkMetadata(txn, groupId, chunk, deleted) &&
					chunksToDelete.add(chunk)) {
				scheduleDeleteChunkFiles(txn, chunk);
			}
		}
		for (Entry<MessageId, BdfDictionary> e : remainingHeaders.entrySet()) {
			BdfDictionary header = e.getValue();
			int chunkTotal = header.getInt(MSG_KEY_CHUNK_TOTAL);
			int received;
			if (hasExpectedAssembledFile(fileDir, header)) {
				received = chunkTotal;
			} else {
				received = countExistingChunks(fileId, fileDir, chunkTotal,
						chunksToDelete);
			}
			BdfDictionary merge = BdfDictionary.of(new BdfEntry(
					MSG_KEY_CHUNKS_RECEIVED, received));
			MessageId headerId = e.getKey();
			clientHelper.mergeMessageMetadata(txn, headerId, merge);
		}
	}

	private void deleteMessageRows(Transaction txn, MessageId m,
			Set<MessageId> deleted) throws DbException {
		if (deleted.add(m)) {
			db.deleteMessage(txn, m);
			db.deleteMessageMetadata(txn, m);
		}
	}

	private void recalculateGroupCount(Transaction txn, GroupId g)
			throws DbException, FormatException {
		Map<MessageId, BdfDictionary> metadata =
				clientHelper.getMessageMetadataAsDictionary(txn, g);
		int msgCount = 0;
		int unreadCount = 0;
		for (BdfDictionary meta : metadata.values()) {
			if (MSG_TYPE_HEADER.equals(meta.getOptionalString(MSG_KEY_MSG_TYPE))) {
				msgCount++;
				if (!getReadFlag(meta)) unreadCount++;
			}
		}
		messageTracker.resetGroupCount(txn, g, msgCount, unreadCount);
	}

	private static class DeletedChunk {

		private final UniqueId fileId;
		private final int chunkIndex;
		private final int chunkTotal;

		private DeletedChunk(UniqueId fileId, int chunkIndex, int chunkTotal) {
			this.fileId = fileId;
			this.chunkIndex = chunkIndex;
			this.chunkTotal = chunkTotal;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof DeletedChunk)) return false;
			DeletedChunk that = (DeletedChunk) o;
			return chunkIndex == that.chunkIndex &&
					chunkTotal == that.chunkTotal &&
					fileId.equals(that.fileId);
		}

		@Override
		public int hashCode() {
			return Objects.hash(fileId, chunkIndex, chunkTotal);
		}
	}

	private static class ChunkKey {

		private final UniqueId fileId;
		private final int chunkIndex;

		private ChunkKey(UniqueId fileId, int chunkIndex) {
			this.fileId = fileId;
			this.chunkIndex = chunkIndex;
		}

		@Override
		public boolean equals(Object o) {
			if (this == o) return true;
			if (!(o instanceof ChunkKey)) return false;
			ChunkKey that = (ChunkKey) o;
			return chunkIndex == that.chunkIndex && fileId.equals(that.fileId);
		}

		@Override
		public int hashCode() {
			return Objects.hash(fileId, chunkIndex);
		}
	}
}
