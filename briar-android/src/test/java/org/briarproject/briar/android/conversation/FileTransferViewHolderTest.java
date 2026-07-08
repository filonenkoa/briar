package org.briarproject.briar.android.conversation;

import org.junit.Test;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class FileTransferViewHolderTest {

	@Test
	public void testImageMimeTypeShowsPreview() {
		assertTrue(FileTransferViewHolder.shouldShowImagePreview(
				"image/jpeg", "file.bin"));
	}

	@Test
	public void testGenericMimeTypeWithImageExtensionShowsPreview() {
		assertTrue(FileTransferViewHolder.shouldShowImagePreview(
				"application/octet-stream", "photo.JPG"));
		assertTrue(FileTransferViewHolder.shouldShowImagePreview(
				"application/octet-stream", "screenshot.png"));
	}

	@Test
	public void testGenericMimeTypeWithNonImageExtensionDoesNotShowPreview() {
		assertFalse(FileTransferViewHolder.shouldShowImagePreview(
				"application/octet-stream", "document.pdf"));
	}

	@Test
	public void testSpecificNonImageMimeTypeDoesNotShowPreview() {
		assertFalse(FileTransferViewHolder.shouldShowImagePreview(
				"application/pdf", "photo.jpg"));
	}
}
