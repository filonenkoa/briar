package org.briarproject.briar.filetransfer;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.UniqueId;
import org.briarproject.bramble.api.client.BdfMessageContext;
import org.briarproject.bramble.api.client.BdfMessageValidator;
import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.data.MetadataEncoder;
import org.briarproject.bramble.api.sync.Group;
import org.briarproject.bramble.api.sync.InvalidMessageException;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.MessageContext;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.briar.api.filetransfer.FileTransferConstants;
import org.briarproject.nullsafety.NotNullByDefault;

import java.util.logging.Logger;

import javax.annotation.concurrent.Immutable;

import static java.util.logging.Logger.getLogger;
import static org.briarproject.bramble.util.ValidationUtils.checkLength;
import static org.briarproject.bramble.util.ValidationUtils.checkSize;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.CHUNK_SIZE;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MAX_CHUNK_TOTAL;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MAX_FILE_SIZE;
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

@Immutable
@NotNullByDefault
class FileTransferValidator extends BdfMessageValidator {

	private static final Logger LOG =
			getLogger(FileTransferValidator.class.getName());

	FileTransferValidator(ClientHelper clientHelper,
			MetadataEncoder metadataEncoder, Clock clock) {
		super(clientHelper, metadataEncoder, clock);
	}

	@Override
	protected BdfMessageContext validateMessage(Message m, Group g,
			BdfList body) throws InvalidMessageException, FormatException {
		String messageType = body.getString(0);
		BdfDictionary meta;
		if (MSG_TYPE_HEADER.equals(messageType)) {
			meta = validateHeader(m, body);
		} else if (MSG_TYPE_CHUNK.equals(messageType)) {
			meta = validateChunk(m, body);
		} else {
			throw new InvalidMessageException("Unknown message type");
		}
		return new BdfMessageContext(meta);
	}

	private BdfDictionary validateHeader(Message m, BdfList body)
			throws FormatException {
		// type(String), fileId(byte[]), fileName(String),
		// contentType(String), fileSize(Long), chunkTotal(Integer)
		checkSize(body, 6);
		byte[] fileId = body.getRaw(1);
		checkLength(fileId, UniqueId.LENGTH);
		String fileName = body.getString(2);
		checkLength(fileName, 1, 1024);
		String contentType = body.getString(3);
		checkLength(contentType, 1, 1024);
		long fileSize = body.getLong(4);
		if (fileSize < 0) throw new FormatException();
		if (fileSize > MAX_FILE_SIZE) throw new FormatException();
		int chunkTotal = body.getInt(5);
		if (chunkTotal < 0) throw new FormatException();
		if (chunkTotal > MAX_CHUNK_TOTAL) throw new FormatException();
		if (chunkTotal != expectedChunkTotal(fileSize))
			throw new FormatException();
		BdfDictionary meta = new BdfDictionary();
		meta.put(MSG_KEY_MSG_TYPE, MSG_TYPE_HEADER);
		meta.put(MSG_KEY_FILE_ID, fileId);
		meta.put(MSG_KEY_FILE_NAME, fileName);
		meta.put(MSG_KEY_CONTENT_TYPE, contentType);
		meta.put(MSG_KEY_FILE_SIZE, fileSize);
		meta.put(MSG_KEY_CHUNK_TOTAL, chunkTotal);
		meta.put(MSG_KEY_CHUNKS_RECEIVED, 0);
		meta.put(MSG_KEY_LOCAL, false);
		meta.put(MSG_KEY_TIMESTAMP, m.getTimestamp());
		return meta;
	}

	private BdfDictionary validateChunk(Message m, BdfList body)
			throws FormatException {
		// type(String), fileId(byte[]), chunkIndex(Integer),
		// chunkTotal(Integer), payload(byte[])
		checkSize(body, 5);
		byte[] fileId = body.getRaw(1);
		checkLength(fileId, UniqueId.LENGTH);
		int chunkIndex = body.getInt(2);
		int chunkTotal = body.getInt(3);
		if (chunkTotal <= 0 || chunkTotal > MAX_CHUNK_TOTAL)
			throw new FormatException();
		if (chunkIndex < 0 || chunkIndex >= chunkTotal)
			throw new FormatException();
		byte[] payload = body.getRaw(4);
		checkLength(payload, 1, CHUNK_SIZE);
		BdfDictionary meta = new BdfDictionary();
		meta.put(MSG_KEY_MSG_TYPE, MSG_TYPE_CHUNK);
		meta.put(MSG_KEY_FILE_ID, fileId);
		meta.put(MSG_KEY_CHUNK_INDEX, chunkIndex);
		meta.put(MSG_KEY_CHUNK_TOTAL, chunkTotal);
		meta.put(MSG_KEY_LOCAL, false);
		meta.put(MSG_KEY_TIMESTAMP, m.getTimestamp());
		return meta;
	}

	private int expectedChunkTotal(long fileSize) {
		return fileSize == 0 ? 0 :
				(int) ((fileSize + CHUNK_SIZE - 1) / CHUNK_SIZE);
	}
}
