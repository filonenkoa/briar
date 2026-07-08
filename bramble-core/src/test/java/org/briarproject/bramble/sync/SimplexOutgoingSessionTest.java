package org.briarproject.bramble.sync;

import org.briarproject.bramble.api.contact.ContactId;
import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.bramble.api.data.MetadataEncoder;
import org.briarproject.bramble.api.db.DatabaseComponent;
import org.briarproject.bramble.api.db.Metadata;
import org.briarproject.bramble.api.db.Transaction;
import org.briarproject.bramble.api.event.EventBus;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.bramble.api.sync.Ack;
import org.briarproject.bramble.api.sync.GroupId;
import org.briarproject.bramble.api.sync.Message;
import org.briarproject.bramble.api.sync.MessageId;
import org.briarproject.bramble.api.sync.SyncRecordWriter;
import org.briarproject.bramble.api.sync.Versions;
import org.briarproject.bramble.api.transport.StreamWriter;
import org.briarproject.bramble.test.BrambleMockTestCase;
import org.briarproject.bramble.test.DbExpectations;
import org.junit.Test;

import java.io.IOException;

import static java.util.Collections.singletonList;
import static org.briarproject.bramble.api.sync.SyncConstants.MAX_MESSAGE_BODY_LENGTH;
import static org.briarproject.bramble.api.sync.SyncConstants.MAX_MESSAGE_IDS;
import static org.briarproject.bramble.api.sync.MessageTransportMetadata.KEY_FIRST_SENT_VIA_TRANSPORT;
import static org.briarproject.bramble.sync.SimplexOutgoingSession.BATCH_CAPACITY;
import static org.briarproject.bramble.test.TestUtils.getContactId;
import static org.briarproject.bramble.test.TestUtils.getMessage;
import static org.briarproject.bramble.test.TestUtils.getRandomId;
import static org.briarproject.bramble.test.TestUtils.getTransportId;

public class SimplexOutgoingSessionTest extends BrambleMockTestCase {

	private static final int MAX_LATENCY = Integer.MAX_VALUE;

	private final DatabaseComponent db = context.mock(DatabaseComponent.class);
	private final MetadataEncoder metadataEncoder =
			context.mock(MetadataEncoder.class);
	private final EventBus eventBus = context.mock(EventBus.class);
	private final StreamWriter streamWriter = context.mock(StreamWriter.class);
	private final SyncRecordWriter recordWriter =
			context.mock(SyncRecordWriter.class);

	private final ContactId contactId = getContactId();
	private final TransportId transportId = getTransportId();
	private final Ack ack =
			new Ack(singletonList(new MessageId(getRandomId())));
	private final Message message = getMessage(new GroupId(getRandomId()),
			MAX_MESSAGE_BODY_LENGTH);
	private final Metadata encodedMetadata = new Metadata();

	@Test
	public void testNothingToSend() throws Exception {
		SimplexOutgoingSession session = new SimplexOutgoingSession(db,
				metadataEncoder, eventBus, contactId, transportId, MAX_LATENCY,
				streamWriter, recordWriter);

		Transaction noAckTxn = new Transaction(null, false);
		Transaction noMsgTxn = new Transaction(null, false);

		context.checking(new DbExpectations() {{
			// Add listener
			oneOf(eventBus).addListener(session);
			// Send the protocol versions
			oneOf(recordWriter).writeVersions(with(any(Versions.class)));
			// No acks to send
			oneOf(db).transactionWithNullableResult(with(false),
					withNullableDbCallable(noAckTxn));
			oneOf(db).generateAck(noAckTxn, contactId, MAX_MESSAGE_IDS);
			will(returnValue(null));
			// No messages to send
			oneOf(db).transactionWithNullableResult(with(false),
					withNullableDbCallable(noMsgTxn));
			oneOf(db).generateBatch(noMsgTxn, contactId,
					BATCH_CAPACITY, MAX_LATENCY);
			will(returnValue(null));
			// Send the end of stream marker
			oneOf(streamWriter).sendEndOfStream();
			// Remove listener
			oneOf(eventBus).removeListener(session);
		}});

		session.run();
	}

	@Test
	public void testSomethingToSend() throws Exception {
		SimplexOutgoingSession session = new SimplexOutgoingSession(db,
				metadataEncoder, eventBus, contactId, transportId, MAX_LATENCY,
				streamWriter, recordWriter);

		Transaction ackTxn = new Transaction(null, false);
		Transaction noAckTxn = new Transaction(null, false);
		Transaction msgTxn = new Transaction(null, false);
		Transaction metaTxn = new Transaction(null, false);
		Transaction noMsgTxn = new Transaction(null, false);
		Metadata emptyMetadata = new Metadata();
		BdfDictionary transportMetadata = new BdfDictionary();
		transportMetadata.put(KEY_FIRST_SENT_VIA_TRANSPORT,
				transportId.getString());

		context.checking(new DbExpectations() {{
			// Add listener
			oneOf(eventBus).addListener(session);
			// Send the protocol versions
			oneOf(recordWriter).writeVersions(with(any(Versions.class)));
			// One ack to send
			oneOf(db).transactionWithNullableResult(with(false),
					withNullableDbCallable(ackTxn));
			oneOf(db).generateAck(ackTxn, contactId, MAX_MESSAGE_IDS);
			will(returnValue(ack));
			oneOf(recordWriter).writeAck(ack);
			// No more acks
			oneOf(db).transactionWithNullableResult(with(false),
					withNullableDbCallable(noAckTxn));
			oneOf(db).generateAck(noAckTxn, contactId, MAX_MESSAGE_IDS);
			will(returnValue(null));
			// One message to send
			oneOf(db).transactionWithNullableResult(with(false),
					withNullableDbCallable(msgTxn));
			oneOf(db).generateBatch(msgTxn, contactId,
					BATCH_CAPACITY, MAX_LATENCY);
			will(returnValue(singletonList(message)));
			oneOf(recordWriter).writeMessage(message);
			oneOf(db).transaction(with(false), withDbRunnable(metaTxn));
			oneOf(db).getMessageMetadata(metaTxn, message.getId());
			will(returnValue(emptyMetadata));
			oneOf(metadataEncoder).encode(transportMetadata);
			will(returnValue(encodedMetadata));
			oneOf(db).mergeMessageMetadata(metaTxn, message.getId(),
					encodedMetadata);
			// No more messages
			oneOf(db).transactionWithNullableResult(with(false),
					withNullableDbCallable(noMsgTxn));
			oneOf(db).generateBatch(noMsgTxn, contactId,
					BATCH_CAPACITY, MAX_LATENCY);
			will(returnValue(null));
			// Send the end of stream marker
			oneOf(streamWriter).sendEndOfStream();
			// Remove listener
			oneOf(eventBus).removeListener(session);
		}});

		session.run();
	}

	@Test
	public void testSendMessagesStoresFirstSentTransport() throws Exception {
		SimplexOutgoingSession session = new SimplexOutgoingSession(db,
				metadataEncoder, eventBus, contactId, transportId, MAX_LATENCY,
				streamWriter, recordWriter);

		Transaction noAckTxn = new Transaction(null, false);
		Transaction msgTxn = new Transaction(null, false);
		Transaction metaTxn = new Transaction(null, false);
		Transaction noMsgTxn = new Transaction(null, false);
		Metadata emptyMetadata = new Metadata();
		BdfDictionary transportMetadata = new BdfDictionary();
		transportMetadata.put(KEY_FIRST_SENT_VIA_TRANSPORT,
				transportId.getString());

		context.checking(new DbExpectations() {{
			oneOf(eventBus).addListener(session);
			oneOf(recordWriter).writeVersions(with(any(Versions.class)));
			oneOf(db).transactionWithNullableResult(with(false),
					withNullableDbCallable(noAckTxn));
			oneOf(db).generateAck(noAckTxn, contactId, MAX_MESSAGE_IDS);
			will(returnValue(null));
			oneOf(db).transactionWithNullableResult(with(false),
					withNullableDbCallable(msgTxn));
			oneOf(db).generateBatch(msgTxn, contactId,
					BATCH_CAPACITY, MAX_LATENCY);
			will(returnValue(singletonList(message)));
			oneOf(recordWriter).writeMessage(message);
			oneOf(db).transaction(with(false), withDbRunnable(metaTxn));
			oneOf(db).getMessageMetadata(metaTxn, message.getId());
			will(returnValue(emptyMetadata));
			oneOf(metadataEncoder).encode(transportMetadata);
			will(returnValue(encodedMetadata));
			oneOf(db).mergeMessageMetadata(metaTxn, message.getId(),
					encodedMetadata);
			oneOf(db).transactionWithNullableResult(with(false),
					withNullableDbCallable(noMsgTxn));
			oneOf(db).generateBatch(noMsgTxn, contactId,
					BATCH_CAPACITY, MAX_LATENCY);
			will(returnValue(null));
			oneOf(streamWriter).sendEndOfStream();
			oneOf(eventBus).removeListener(session);
		}});

		session.run();
	}

	@Test
	public void testSendMessagesDoesNotOverwriteFirstSentTransport()
			throws Exception {
		SimplexOutgoingSession session = new SimplexOutgoingSession(db,
				metadataEncoder, eventBus, contactId, transportId, MAX_LATENCY,
				streamWriter, recordWriter);

		Transaction noAckTxn = new Transaction(null, false);
		Transaction msgTxn = new Transaction(null, false);
		Transaction metaTxn = new Transaction(null, false);
		Transaction noMsgTxn = new Transaction(null, false);
		Metadata existingMetadata = new Metadata();
		existingMetadata.put(KEY_FIRST_SENT_VIA_TRANSPORT, new byte[] {1});

		context.checking(new DbExpectations() {{
			oneOf(eventBus).addListener(session);
			oneOf(recordWriter).writeVersions(with(any(Versions.class)));
			oneOf(db).transactionWithNullableResult(with(false),
					withNullableDbCallable(noAckTxn));
			oneOf(db).generateAck(noAckTxn, contactId, MAX_MESSAGE_IDS);
			will(returnValue(null));
			oneOf(db).transactionWithNullableResult(with(false),
					withNullableDbCallable(msgTxn));
			oneOf(db).generateBatch(msgTxn, contactId,
					BATCH_CAPACITY, MAX_LATENCY);
			will(returnValue(singletonList(message)));
			oneOf(recordWriter).writeMessage(message);
			oneOf(db).transaction(with(false), withDbRunnable(metaTxn));
			oneOf(db).getMessageMetadata(metaTxn, message.getId());
			will(returnValue(existingMetadata));
			oneOf(db).transactionWithNullableResult(with(false),
					withNullableDbCallable(noMsgTxn));
			oneOf(db).generateBatch(noMsgTxn, contactId,
					BATCH_CAPACITY, MAX_LATENCY);
			will(returnValue(null));
			oneOf(streamWriter).sendEndOfStream();
			oneOf(eventBus).removeListener(session);
		}});

		session.run();
	}

	@Test(expected = IOException.class)
	public void testSendMessagesDoesNotStoreFirstSentTransportIfWriteFails()
			throws Exception {
		SimplexOutgoingSession session = new SimplexOutgoingSession(db,
				metadataEncoder, eventBus, contactId, transportId, MAX_LATENCY,
				streamWriter, recordWriter);

		Transaction noAckTxn = new Transaction(null, false);
		Transaction msgTxn = new Transaction(null, false);

		context.checking(new DbExpectations() {{
			oneOf(eventBus).addListener(session);
			oneOf(recordWriter).writeVersions(with(any(Versions.class)));
			oneOf(db).transactionWithNullableResult(with(false),
					withNullableDbCallable(noAckTxn));
			oneOf(db).generateAck(noAckTxn, contactId, MAX_MESSAGE_IDS);
			will(returnValue(null));
			oneOf(db).transactionWithNullableResult(with(false),
					withNullableDbCallable(msgTxn));
			oneOf(db).generateBatch(msgTxn, contactId,
					BATCH_CAPACITY, MAX_LATENCY);
			will(returnValue(singletonList(message)));
			oneOf(recordWriter).writeMessage(message);
			will(throwException(new IOException()));
			oneOf(eventBus).removeListener(session);
		}});

		session.run();
	}
}
