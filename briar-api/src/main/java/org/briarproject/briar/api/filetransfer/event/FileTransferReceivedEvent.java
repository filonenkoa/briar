package org.briarproject.briar.api.filetransfer.event;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.briar.api.conversation.event.ConversationMessageReceivedEvent;
import org.briarproject.briar.api.filetransfer.FileTransferHeader;
import org.briarproject.nullsafety.NotNullByDefault;

@NotNullByDefault
public class FileTransferReceivedEvent
		extends ConversationMessageReceivedEvent<FileTransferHeader> {

	public FileTransferReceivedEvent(FileTransferHeader messageHeader,
			ContactId contactId) {
		super(messageHeader, contactId);
	}
}
