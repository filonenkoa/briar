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
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_TYPE_CHUNK;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_TYPE_HEADER;
import static org.junit.Assert.assertEquals;
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

	private byte[] randomFileId() {
		byte[] b = new byte[UniqueId.LENGTH];
		new SecureRandom().nextBytes(b);
		return b;
	}

	private int expectedChunkTotal(long size) {
		return size == 0 ? 0 : (int) ((size + CHUNK_SIZE - 1) / CHUNK_SIZE);
	}
}
