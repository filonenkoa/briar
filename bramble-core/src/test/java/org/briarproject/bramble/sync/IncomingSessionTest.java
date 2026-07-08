package org.briarproject.bramble.sync;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.bramble.api.data.MetadataEncoder;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.Metadata;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.PriorityHandler;
import org.briarproject.bramble.api.sync.SyncRecordReader;
import org.briarproject.bramble.test.BrambleMockTestCase;
import org.briarproject.bramble.test.DbExpectations;
import org.briarproject.bramble.test.ImmediateExecutor;
import org.junit.Test;

import java.util.concurrent.Executor;

import static org.briarproject.bramble.api.sync.MessageTransportMetadata.KEY_RECEIVED_VIA_TRANSPORT;
import static org.briarproject.bramble.api.sync.SyncConstants.MAX_MESSAGE_BODY_LENGTH;
import static org.briarproject.bramble.test.TestUtils.getContactId;
import static org.briarproject.bramble.test.TestUtils.getMessage;
import static org.briarproject.bramble.test.TestUtils.getRandomId;
import static org.briarproject.bramble.test.TestUtils.getTransportId;

public class IncomingSessionTest extends BrambleMockTestCase {

	private final DatabaseComponent db = context.mock(DatabaseComponent.class);
	private final MetadataEncoder metadataEncoder =
			context.mock(MetadataEncoder.class);
	private final EventBus eventBus = context.mock(EventBus.class);
	private final SyncRecordReader recordReader =
			context.mock(SyncRecordReader.class);
	private final PriorityHandler priorityHandler =
			context.mock(PriorityHandler.class);

	private final Executor dbExecutor = new ImmediateExecutor();
	private final ContactId contactId = getContactId();
	private final TransportId transportId = getTransportId();
	private final Message message = getMessage(new GroupId(getRandomId()),
			MAX_MESSAGE_BODY_LENGTH);
	private final Metadata encodedMetadata = new Metadata();

	@Test
	public void testReceiveMessageStoresReceivedViaTransport()
			throws Exception {
		IncomingSession session = new IncomingSession(db, metadataEncoder,
				dbExecutor, eventBus, contactId, transportId, recordReader,
				priorityHandler);

		Transaction txn = new Transaction(null, false);
		Metadata emptyMetadata = new Metadata();
		BdfDictionary transportMetadata = new BdfDictionary();
		transportMetadata.put(KEY_RECEIVED_VIA_TRANSPORT,
				transportId.getString());

		context.checking(new DbExpectations() {{
			oneOf(eventBus).addListener(session);
			oneOf(recordReader).eof();
			will(returnValue(false));
			oneOf(recordReader).hasAck();
			will(returnValue(false));
			oneOf(recordReader).hasMessage();
			will(returnValue(true));
			oneOf(recordReader).readMessage();
			will(returnValue(message));
			oneOf(db).transaction(with(false), withDbRunnable(txn));
			oneOf(db).receiveMessage(txn, contactId, message);
			will(returnValue(true));
			oneOf(db).getMessageMetadata(txn, message.getId());
			will(returnValue(emptyMetadata));
			oneOf(metadataEncoder).encode(transportMetadata);
			will(returnValue(encodedMetadata));
			oneOf(db).mergeMessageMetadata(txn, message.getId(), encodedMetadata);
			oneOf(recordReader).eof();
			will(returnValue(true));
			oneOf(eventBus).removeListener(session);
		}});

		session.run();
	}

	@Test
	public void testReceiveMessageDoesNotOverwriteReceivedViaTransport()
			throws Exception {
		IncomingSession session = new IncomingSession(db, metadataEncoder,
				dbExecutor, eventBus, contactId, transportId, recordReader,
				priorityHandler);

		Transaction txn = new Transaction(null, false);
		Metadata existingMetadata = new Metadata();
		existingMetadata.put(KEY_RECEIVED_VIA_TRANSPORT, new byte[] {1});

		context.checking(new DbExpectations() {{
			oneOf(eventBus).addListener(session);
			oneOf(recordReader).eof();
			will(returnValue(false));
			oneOf(recordReader).hasAck();
			will(returnValue(false));
			oneOf(recordReader).hasMessage();
			will(returnValue(true));
			oneOf(recordReader).readMessage();
			will(returnValue(message));
			oneOf(db).transaction(with(false), withDbRunnable(txn));
			oneOf(db).receiveMessage(txn, contactId, message);
			will(returnValue(true));
			oneOf(db).getMessageMetadata(txn, message.getId());
			will(returnValue(existingMetadata));
			oneOf(recordReader).eof();
			will(returnValue(true));
			oneOf(eventBus).removeListener(session);
		}});

		session.run();
	}

	@Test
	public void testReceiveMessageDoesNotStoreMetadataForIgnoredMessage()
			throws Exception {
		IncomingSession session = new IncomingSession(db, metadataEncoder,
				dbExecutor, eventBus, contactId, transportId, recordReader,
				priorityHandler);

		Transaction txn = new Transaction(null, false);

		context.checking(new DbExpectations() {{
			oneOf(eventBus).addListener(session);
			oneOf(recordReader).eof();
			will(returnValue(false));
			oneOf(recordReader).hasAck();
			will(returnValue(false));
			oneOf(recordReader).hasMessage();
			will(returnValue(true));
			oneOf(recordReader).readMessage();
			will(returnValue(message));
			oneOf(db).transaction(with(false), withDbRunnable(txn));
			oneOf(db).receiveMessage(txn, contactId, message);
			will(returnValue(false));
			oneOf(recordReader).eof();
			will(returnValue(true));
			oneOf(eventBus).removeListener(session);
		}});

		session.run();
	}
}
