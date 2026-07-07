package org.briarproject.briar.android.conversation;

import android.app.Application;

import org.briarproject.bramble.api.UniqueId;
import org.briarproject.bramble.api.contact.ContactManager;
import org.briarproject.bramble.api.db.TransactionManager;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.event.EventListener;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.settings.SettingsManager;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.api.system.AndroidExecutor;
import org.briarproject.bramble.test.BrambleMockTestCase;
import org.briarproject.bramble.test.ImmediateExecutor;
import org.briarproject.briar.android.AndroidExecutorTestImpl;
import org.briarproject.briar.android.attachment.AttachmentCreator;
import org.briarproject.briar.android.attachment.AttachmentRetriever;
import org.briarproject.briar.api.autodelete.AutoDeleteManager;
import org.briarproject.briar.api.conversation.ConversationManager;
import org.briarproject.briar.api.filetransfer.FileTransferHeader;
import org.briarproject.briar.api.filetransfer.FileTransferManager;
import org.briarproject.briar.api.filetransfer.FileTransferProgress;
import org.briarproject.briar.api.filetransfer.FileTransferProgress.State;
import org.briarproject.briar.api.identity.AuthorManager;
import org.briarproject.briar.api.messaging.MessagingManager;
import org.briarproject.briar.api.messaging.PrivateMessageFactory;
import org.jmock.Expectations;
import org.jmock.imposters.ByteBuddyClassImposteriser;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.concurrent.Executor;

import static org.briarproject.bramble.test.TestUtils.getRandomId;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 21)
public class ConversationViewModelTest extends BrambleMockTestCase {

	private final LifecycleManager lifecycleManager =
			context.mock(LifecycleManager.class);
	private final TransactionManager db = context.mock(TransactionManager.class);
	private final EventBus eventBus = context.mock(EventBus.class);
	private final MessagingManager messagingManager =
			context.mock(MessagingManager.class);
	private final ContactManager contactManager =
			context.mock(ContactManager.class);
	private final AuthorManager authorManager = context.mock(AuthorManager.class);
	private final SettingsManager settingsManager =
			context.mock(SettingsManager.class);
	private final PrivateMessageFactory privateMessageFactory =
			context.mock(PrivateMessageFactory.class);
	private final AttachmentRetriever attachmentRetriever =
			context.mock(AttachmentRetriever.class);
	private final AttachmentCreator attachmentCreator =
			context.mock(AttachmentCreator.class);
	private final AutoDeleteManager autoDeleteManager =
			context.mock(AutoDeleteManager.class);
	private final ConversationManager conversationManager =
			context.mock(ConversationManager.class);
	private final FileTransferManager fileTransferManager =
			context.mock(FileTransferManager.class);

	private final ConversationViewModel viewModel;

	public ConversationViewModelTest() {
		context.setImposteriser(ByteBuddyClassImposteriser.INSTANCE);
		Application app = context.mock(Application.class);
		context.checking(new Expectations() {{
			oneOf(eventBus).addListener(with(any(EventListener.class)));
		}});
		Executor dbExecutor = new ImmediateExecutor();
		AndroidExecutor androidExecutor = new AndroidExecutorTestImpl(dbExecutor);
		viewModel = new ConversationViewModel(app, dbExecutor, lifecycleManager,
				db, androidExecutor, eventBus, messagingManager, contactManager,
				authorManager, settingsManager, privateMessageFactory,
				attachmentRetriever, attachmentCreator, autoDeleteManager,
				conversationManager, fileTransferManager);
	}

	@Test
	public void testStopLocalFileTransferCancels() throws Exception {
		FileTransferHeader h = header(true);

		context.checking(new Expectations() {{
			oneOf(lifecycleManager).waitForDatabase();
			oneOf(fileTransferManager).getProgress(h);
			will(returnValue(progress(State.TRANSFERRING)));
			oneOf(fileTransferManager).cancelFileTransfer(h);
		}});

		viewModel.stopFileTransfer(h);
	}

	@Test
	public void testStopRemoteFileTransferRejects() throws Exception {
		FileTransferHeader h = header(false);

		context.checking(new Expectations() {{
			oneOf(lifecycleManager).waitForDatabase();
			oneOf(fileTransferManager).getProgress(h);
			will(returnValue(progress(State.TRANSFERRING)));
			oneOf(fileTransferManager).rejectFileTransfer(h);
		}});

		viewModel.stopFileTransfer(h);
	}

	@Test
	public void testStopCompleteFileTransferDoesNothing() throws Exception {
		FileTransferHeader h = header(true);

		context.checking(new Expectations() {{
			oneOf(lifecycleManager).waitForDatabase();
			oneOf(fileTransferManager).getProgress(h);
			will(returnValue(progress(State.COMPLETE)));
			never(fileTransferManager).cancelFileTransfer(h);
			never(fileTransferManager).rejectFileTransfer(h);
		}});

		viewModel.stopFileTransfer(h);
	}

	@Test
	public void testStopCancelledFileTransferDoesNothing() throws Exception {
		FileTransferHeader h = header(true);

		context.checking(new Expectations() {{
			oneOf(lifecycleManager).waitForDatabase();
			oneOf(fileTransferManager).getProgress(h);
			will(returnValue(progress(State.CANCELLED)));
			never(fileTransferManager).cancelFileTransfer(h);
			never(fileTransferManager).rejectFileTransfer(h);
		}});

		viewModel.stopFileTransfer(h);
	}

	@Test
	public void testStopRejectedFileTransferDoesNothing() throws Exception {
		FileTransferHeader h = header(false);

		context.checking(new Expectations() {{
			oneOf(lifecycleManager).waitForDatabase();
			oneOf(fileTransferManager).getProgress(h);
			will(returnValue(progress(State.REJECTED)));
			never(fileTransferManager).cancelFileTransfer(h);
			never(fileTransferManager).rejectFileTransfer(h);
		}});

		viewModel.stopFileTransfer(h);
	}

	@Test
	public void testStopErroredFileTransferDoesNothing() throws Exception {
		FileTransferHeader h = header(false);

		context.checking(new Expectations() {{
			oneOf(lifecycleManager).waitForDatabase();
			oneOf(fileTransferManager).getProgress(h);
			will(returnValue(progress(State.ERROR)));
			never(fileTransferManager).cancelFileTransfer(h);
			never(fileTransferManager).rejectFileTransfer(h);
		}});

		viewModel.stopFileTransfer(h);
	}

	private FileTransferProgress progress(State state) {
		return new FileTransferProgress(state, 0, 12L);
	}

	private FileTransferHeader header(boolean local) {
		return new FileTransferHeader(new MessageId(getRandomId()),
				new GroupId(getRandomId()), 42L, local, false, false, false, 0,
				new UniqueId(getRandomId()), "file.txt", "text/plain", 12L, 1);
	}
}
