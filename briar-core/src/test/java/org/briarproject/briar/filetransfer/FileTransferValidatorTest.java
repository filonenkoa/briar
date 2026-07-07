package org.briarproject.briar.filetransfer;

import org.briarproject.bramble.api.FormatException;
import org.briarproject.bramble.api.UniqueId;
import org.briarproject.bramble.api.client.BdfMessageContext;
import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.bramble.api.data.BdfList;
import org.briarproject.bramble.api.sync.InvalidMessageException;
import org.briarproject.bramble.test.ValidatorTestCase;
import org.junit.Test;

import java.security.SecureRandom;

import static org.briarproject.briar.api.filetransfer.FileTransferConstants.CHUNK_SIZE;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MAX_CHUNK_TOTAL;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MAX_FILE_SIZE;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_KEY_CHUNK_TOTAL;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_KEY_FILE_ID;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_KEY_LOCAL;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_KEY_MSG_TYPE;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_KEY_TIMESTAMP;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_KEY_TRANSFER_STATE;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_TYPE_CONTROL;
import static org.briarproject.briar.client.MessageTrackerConstants.MSG_KEY_READ;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_TYPE_CHUNK;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_TYPE_HEADER;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.TRANSFER_STATE_CANCELLED_BY_SENDER;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.TRANSFER_STATE_REJECTED_BY_RECEIVER;
import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.fail;

public class FileTransferValidatorTest extends ValidatorTestCase {

	private final FileTransferValidator validator =
			new FileTransferValidator(clientHelper, metadataEncoder, clock);

	@Test
	public void testRejectsHeaderWithInconsistentChunkTotal() throws Exception {
		BdfList body = BdfList.of(MSG_TYPE_HEADER, randomFileId(), "file.bin",
				"application/octet-stream", (long) CHUNK_SIZE * 2, 99);

		assertInvalid(body);
	}

	@Test
	public void testRejectsHeaderLargerThanMaximumFileSize() throws Exception {
		long fileSize = MAX_FILE_SIZE + 1;
		BdfList body = BdfList.of(MSG_TYPE_HEADER, randomFileId(), "file.bin",
				"application/octet-stream", fileSize,
				expectedChunkTotal(fileSize));

		assertInvalid(body);
	}

	@Test
	public void testAcceptsZeroByteHeaderWithNoChunks() throws Exception {
		BdfList body = BdfList.of(MSG_TYPE_HEADER, randomFileId(), "file.bin",
				"application/octet-stream", 0L, 0);

		assertValid(body);
	}

	@Test
	public void testHeaderMetadataIsUnread() throws Exception {
		BdfList body = BdfList.of(MSG_TYPE_HEADER, randomFileId(), "file.bin",
				"application/octet-stream", 0L, 0);

		BdfDictionary meta = assertValid(body);

		assertFalse(meta.getBoolean(MSG_KEY_READ));
	}

	@Test
	public void testRejectsHeaderWithUnsafeFileName() throws Exception {
		String[] unsafeNames = {"../evil.txt", "/tmp/evil.txt",
				"dir\\evil.txt", "", ".", ".."};
		for (String fileName : unsafeNames) {
			BdfList body = BdfList.of(MSG_TYPE_HEADER, randomFileId(), fileName,
					"application/octet-stream", 0L, 0);

			assertInvalid(body);
		}
	}

	@Test
	public void testRejectsChunkWithIndexOutsideTotal() throws Exception {
		BdfList body = BdfList.of(MSG_TYPE_CHUNK, randomFileId(), 3, 3,
				new byte[] {1, 2, 3});

		assertInvalid(body);
	}

	@Test
	public void testRejectsChunkWithZeroLengthPayload() throws Exception {
		BdfList body = BdfList.of(MSG_TYPE_CHUNK, randomFileId(), 0, 1,
				new byte[0]);

		assertInvalid(body);
	}

	@Test
	public void testRejectsChunkWithZeroTotal() throws Exception {
		BdfList body = BdfList.of(MSG_TYPE_CHUNK, randomFileId(), 0, 0,
				new byte[] {1, 2, 3});

		assertInvalid(body);
	}

	@Test
	public void testRejectsChunkWithTotalAboveMaximum() throws Exception {
		BdfList body = BdfList.of(MSG_TYPE_CHUNK, randomFileId(), 0,
				MAX_CHUNK_TOTAL + 1, new byte[] {1, 2, 3});

		assertInvalid(body);
	}

	@Test
	public void testAcceptsChunkWithIndexInsideTotal() throws Exception {
		BdfList body = BdfList.of(MSG_TYPE_CHUNK, randomFileId(), 2, 3,
				new byte[] {1, 2, 3});

		BdfDictionary meta = assertValid(body);
		assertEquals(3, meta.getInt(MSG_KEY_CHUNK_TOTAL).intValue());
		assertFalse(meta.containsKey(MSG_KEY_READ));
	}

	@Test
	public void testAcceptsCancelControlMessage() throws Exception {
		byte[] fileId = randomFileId();
		BdfList body = BdfList.of(MSG_TYPE_CONTROL, fileId,
				TRANSFER_STATE_CANCELLED_BY_SENDER);

		BdfDictionary meta = assertValid(body);

		assertControlMetadata(meta, fileId,
				TRANSFER_STATE_CANCELLED_BY_SENDER);
	}

	@Test
	public void testAcceptsRejectControlMessage() throws Exception {
		byte[] fileId = randomFileId();
		BdfList body = BdfList.of(MSG_TYPE_CONTROL, fileId,
				TRANSFER_STATE_REJECTED_BY_RECEIVER);

		BdfDictionary meta = assertValid(body);

		assertControlMetadata(meta, fileId,
				TRANSFER_STATE_REJECTED_BY_RECEIVER);
	}

	@Test
	public void testRejectsControlMessageWithUnknownState() throws Exception {
		BdfList body = BdfList.of(MSG_TYPE_CONTROL, randomFileId(),
				"unknown");

		assertInvalid(body);
	}

	private BdfDictionary assertValid(BdfList body) throws Exception {
		BdfMessageContext context =
				validator.validateMessage(message, group, body);
		return context.getDictionary();
	}

	private void assertInvalid(BdfList body) throws Exception {
		try {
			validator.validateMessage(message, group, body);
			fail();
		} catch (FormatException | InvalidMessageException expected) {
			// Expected.
		}
	}

	private void assertControlMetadata(BdfDictionary meta, byte[] fileId,
			String transferState) throws FormatException {
		assertEquals(MSG_TYPE_CONTROL, meta.getString(MSG_KEY_MSG_TYPE));
		assertArrayEquals(fileId, meta.getRaw(MSG_KEY_FILE_ID));
		assertEquals(transferState, meta.getString(MSG_KEY_TRANSFER_STATE));
		assertFalse(meta.getBoolean(MSG_KEY_LOCAL));
		assertEquals(timestamp, meta.getLong(MSG_KEY_TIMESTAMP).longValue());
	}

	private byte[] randomFileId() {
		byte[] b = new byte[UniqueId.LENGTH];
		new SecureRandom().nextBytes(b);
		return b;
	}

	private int expectedChunkTotal(long size) {
		return size == 0 ? 0 : (int) ((size + CHUNK_SIZE - 1) / CHUNK_SIZE);
	}
}
