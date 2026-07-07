package org.briarproject.briar.api.filetransfer;

import org.briarproject.bramble.api.UniqueId;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.api.conversation.ConversationMessageHeader;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public class FileTransferHeader extends ConversationMessageHeader {

	private final UniqueId fileId;
	private final String fileName;
	private final String contentType;
	private final long fileSize;
	private final int chunkTotal;

	public FileTransferHeader(MessageId id, GroupId groupId, long timestamp,
			boolean local, boolean read, boolean sent, boolean seen,
			long autoDeleteTimer, UniqueId fileId, String fileName,
			String contentType, long fileSize, int chunkTotal) {
		super(id, groupId, timestamp, local, read, sent, seen,
				autoDeleteTimer);
		this.fileId = fileId;
		this.fileName = fileName;
		this.contentType = contentType;
		this.fileSize = fileSize;
		this.chunkTotal = chunkTotal;
	}

	public UniqueId getFileId() {
		return fileId;
	}

	public String getFileName() {
		return fileName;
	}

	public String getContentType() {
		return contentType;
	}

	public long getFileSize() {
		return fileSize;
	}

	public int getChunkTotal() {
		return chunkTotal;
	}

	@Override
	public <T> T accept(
			org.briarproject.briar.api.conversation.ConversationMessageVisitor<T> v) {
		return v.visitFileTransferHeader(this);
	}
}
