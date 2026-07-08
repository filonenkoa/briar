package org.briarproject.briar.android.conversation;

import android.view.View;
import android.widget.ImageView;

import org.briarproject.bramble.api.Pair;
import org.briarproject.bramble.api.UniqueId;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.briar.R;
import org.briarproject.briar.android.attachment.AttachmentItem;
import org.briarproject.briar.api.filetransfer.FileTransferHeader;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import androidx.lifecycle.Lifecycle;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.LifecycleRegistry;

import static org.briarproject.bramble.test.TestUtils.getRandomId;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 21)
public class ConversationAdapterTest {

	@Test
	public void testGetsFileItemByMessageId() {
		ConversationAdapter adapter = new ConversationAdapter(
				RuntimeEnvironment.application, new NoopConversationListener(),
				new TestLifecycleOwner());
		ConversationFileItem item = new ConversationFileItem(
				R.layout.list_item_conversation_file_out, header(), null);

		adapter.add(item);

		Pair<Integer, ConversationFileItem> pair = adapter.getFileItem(item.getId());
		assertNotNull(pair);
		assertEquals(0, pair.getFirst().intValue());
		assertEquals(item, pair.getSecond());
	}

	private FileTransferHeader header() {
		return new FileTransferHeader(new MessageId(getRandomId()),
				new GroupId(getRandomId()), 42L, true, false, false, false, 0,
				new UniqueId(getRandomId()), "photo.jpg", "image/jpeg", 12L, 1);
	}

	private static class TestLifecycleOwner implements LifecycleOwner {

		private final LifecycleRegistry registry = new LifecycleRegistry(this);

		private TestLifecycleOwner() {
			registry.setCurrentState(Lifecycle.State.RESUMED);
		}

		@Override
		public Lifecycle getLifecycle() {
			return registry;
		}
	}

	private static class NoopConversationListener implements ConversationListener {

		@Override
		public void respondToRequest(ConversationRequestItem item, boolean accept) {
		}

		@Override
		public void openRequestedShareable(ConversationRequestItem item) {
		}

		@Override
		public void onAttachmentClicked(View view,
				ConversationMessageItem messageItem, AttachmentItem attachmentItem) {
		}

		@Override
		public void onFileClicked(ConversationFileItem item) {
		}

		@Override
		public void onFileStopClicked(ConversationFileItem item) {
		}

		@Override
		public void onFilePreviewRequested(ConversationFileItem item,
				ImageView imageView) {
		}

		@Override
		public void onAutoDeleteTimerNoticeClicked() {
		}

		@Override
		public void onLinkClick(String url) {
		}
	}
}
