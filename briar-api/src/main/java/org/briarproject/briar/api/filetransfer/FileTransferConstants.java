package org.briarproject.briar.api.filetransfer;

import org.briarproject.bramble.api.sync.ClientId;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.api.UniqueId;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public interface FileTransferConstants {

	ClientId CLIENT_ID =
			new ClientId("org.briarproject.briar.filetransfer");
	int MAJOR_VERSION = 0;
	int MINOR_VERSION = 1;

	/**
	 * Maximum payload size of a single chunk. Must stay well below the sync
	 * protocol's 32 KiB message body limit to leave room for the BDF
	 * descriptor.
	 */
	int CHUNK_SIZE = 16 * 1024;
	long MAX_FILE_SIZE = 10L * 1024 * 1024 * 1024;
	int MAX_CHUNK_TOTAL = (int) ((MAX_FILE_SIZE + CHUNK_SIZE - 1) /
			CHUNK_SIZE);

	// Message type stored in metadata
	String MSG_KEY_MSG_TYPE = "type";
	String MSG_TYPE_HEADER = "header";
	String MSG_TYPE_CHUNK = "chunk";
	String MSG_TYPE_CONTROL = "control";

	// Header message metadata
	String MSG_KEY_FILE_ID = "fileId";
	String MSG_KEY_FILE_NAME = "fileName";
	String MSG_KEY_CONTENT_TYPE = "contentType";
	String MSG_KEY_FILE_SIZE = "fileSize";
	String MSG_KEY_CHUNK_TOTAL = "chunkTotal";
	String MSG_KEY_CHUNKS_RECEIVED = "chunksReceived";

	// Chunk message metadata
	String MSG_KEY_CHUNK_INDEX = "chunkIndex";

	// Control message metadata
	String MSG_KEY_TRANSFER_STATE = "transferState";
	String TRANSFER_STATE_CANCELLED_BY_SENDER = "cancelled_by_sender";
	String TRANSFER_STATE_REJECTED_BY_RECEIVER = "rejected_by_receiver";

	// Shared metadata
	String MSG_KEY_LOCAL = "local";
	String MSG_KEY_TIMESTAMP = "timestamp";
}
