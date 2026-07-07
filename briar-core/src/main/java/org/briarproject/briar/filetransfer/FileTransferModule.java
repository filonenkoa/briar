package org.briarproject.briar.filetransfer;

import org.briarproject.bramble.api.data.MetadataEncoder;
import org.briarproject.bramble.api.lifecycle.LifecycleManager;
import org.briarproject.bramble.api.sync.validation.ValidationManager;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.bramble.api.versioning.ClientVersioningManager;
import org.briarproject.briar.api.conversation.ConversationManager;

import org.briarproject.bramble.api.contact.ContactManager;
import org.briarproject.bramble.api.client.ClientHelper;
import org.briarproject.bramble.api.cleanup.CleanupManager;

import org.briarproject.briar.api.filetransfer.FileTransferManager;

import javax.inject.Inject;
import javax.inject.Singleton;

import dagger.Module;
import dagger.Provides;

import static org.briarproject.briar.api.filetransfer.FileTransferManager.CLIENT_ID;
import static org.briarproject.briar.api.filetransfer.FileTransferManager.MAJOR_VERSION;
import static org.briarproject.briar.api.filetransfer.FileTransferManager.MINOR_VERSION;

@Module
public class FileTransferModule {

	public static class EagerSingletons {
		@Inject
		FileTransferManager fileTransferManager;
		@Inject
		FileTransferValidator fileTransferValidator;
	}

	@Provides
	@Singleton
	FileTransferValidator getValidator(ValidationManager validationManager,
			ClientHelper clientHelper, MetadataEncoder metadataEncoder,
			Clock clock) {
		FileTransferValidator validator =
				new FileTransferValidator(clientHelper, metadataEncoder, clock);
		validationManager.registerMessageValidator(CLIENT_ID, MAJOR_VERSION,
				validator);
		return validator;
	}

	@Provides
	@Singleton
	FileTransferManager getFileTransferManager(LifecycleManager lifecycleManager,
			ContactManager contactManager, ValidationManager validationManager,
			ConversationManager conversationManager,
			ClientVersioningManager clientVersioningManager,
			CleanupManager cleanupManager,
			FileTransferManagerImpl fileTransferManager) {
		lifecycleManager.registerOpenDatabaseHook(fileTransferManager);
		contactManager.registerContactHook(fileTransferManager);
		validationManager.registerIncomingMessageHook(CLIENT_ID, MAJOR_VERSION,
				fileTransferManager);
		conversationManager.registerConversationClient(fileTransferManager);
		clientVersioningManager.registerClient(CLIENT_ID, MAJOR_VERSION,
				MINOR_VERSION, fileTransferManager);
		cleanupManager.registerCleanupHook(CLIENT_ID, MAJOR_VERSION,
				fileTransferManager);
		return fileTransferManager;
	}
}
