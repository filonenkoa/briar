package org.briarproject.briar.api.filetransfer;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.db.DbException;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.api.conversation.ConversationManager.ConversationClient;
import org.briarproject.nullsafety.NotNullByDefault;

import java.io.IOException;
import java.io.InputStream;

@NotNullByDefault
public interface FileTransferManager extends ConversationClient,
		FileTransferConstants {

	/**
	 * Stores the given file as a series of chunk messages in the contact's
	 * file-transfer group and returns a header describing the transfer.
	 *
	 * @param fileName    the original file name (for display)
	 * @param contentType the MIME type of the file
	 * @param fileSize    the total size of the file in bytes
	 * @param in          a stream with the file contents (caller closes)
	 */
	FileTransferHeader sendFile(ContactId c, String fileName,
			String contentType, long fileSize, InputStream in)
			throws DbException, IOException;

	/**
	 * Returns the reassembled file contents for the given transfer, or null
	 * if the transfer is not yet complete. The caller must close the stream.
	 */
	InputStream getFile(FileTransferHeader h) throws DbException, IOException;

	/**
	 * Returns the current progress of the given transfer.
	 */
	FileTransferProgress getProgress(FileTransferHeader h) throws DbException;

	/**
	 * Returns the header for the given message id, or null if it is not a
	 * file-transfer header.
	 */
	FileTransferHeader getFileTransferHeader(MessageId m) throws DbException;
}
