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
import org.briarproject.bramble.api.sync.SyncRecordWriter;
import org.briarproject.bramble.api.sync.Versions;
import org.briarproject.bramble.api.system.Clock;
import org.briarproject.bramble.api.transport.StreamWriter;
import org.briarproject.bramble.test.BrambleMockTestCase;
import org.briarproject.bramble.test.DbExpectations;
import org.hamcrest.Description;
import org.jmock.api.Action;
import org.jmock.api.Invocation;
import org.junit.Test;

import java.io.IOException;
import java.util.ArrayDeque;
import java.util.Queue;
import java.util.concurrent.Executor;

import static java.util.Collections.singletonList;
import static org.briarproject.bramble.api.sync.MessageTransportMetadata.KEY_FIRST_SENT_VIA_TRANSPORT;
import static org.briarproject.bramble.api.sync.SyncConstants.MAX_MESSAGE_BODY_LENGTH;
import static org.briarproject.bramble.api.sync.SyncConstants.MAX_MESSAGE_IDS;
import static org.briarproject.bramble.test.TestUtils.getContactId;
import static org.briarproject.bramble.test.TestUtils.getMessage;
import static org.briarproject.bramble.test.TestUtils.getRandomId;
import static org.briarproject.bramble.test.TestUtils.getTransportId;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class DuplexOutgoingSessionTest extends BrambleMockTestCase {

	private static final int MAX_LATENCY = Integer.MAX_VALUE;
	private static final int MAX_IDLE_TIME = Integer.MAX_VALUE;

	private final DatabaseComponent db = context.mock(DatabaseComponent.class);
	private final MetadataEncoder metadataEncoder =
			context.mock(MetadataEncoder.class);
	private final EventBus eventBus = context.mock(EventBus.class);
	private final Clock clock = context.mock(Clock.class);
	private final StreamWriter streamWriter = context.mock(StreamWriter.class);
	private final SyncRecordWriter recordWriter =
			context.mock(SyncRecordWriter.class);

	private final DeferringExecutor dbExecutor = new DeferringExecutor();
	private final ContactId contactId = getContactId();
	private final TransportId transportId = getTransportId();
	private final Message message = getMessage(new GroupId(getRandomId()),
			MAX_MESSAGE_BODY_LENGTH);
	private final Metadata encodedMetadata = new Metadata();

	@Test
	public void testSendMessagesStoresFirstSentTransport() throws Exception {
		DuplexOutgoingSession[] session = new DuplexOutgoingSession[1];
		session[0] = createSession();

		Transaction noAckTxn = new Transaction(null, false);
		Transaction msgTxn = new Transaction(null, false);
		Transaction noOfferTxn = new Transaction(null, false);
		Transaction noRequestTxn = new Transaction(null, false);
		Transaction metaTxn = new Transaction(null, false);
		Metadata emptyMetadata = new Metadata();
		BdfDictionary transportMetadata = new BdfDictionary();
		transportMetadata.put(KEY_FIRST_SENT_VIA_TRANSPORT,
				transportId.getString());

		context.checking(new DbExpectations() {{
			allowing(clock).currentTimeMillis();
			will(returnValue(0L));
			oneOf(eventBus).addListener(session[0]);
			oneOf(recordWriter).writeVersions(with(any(Versions.class)));
			oneOf(db).transactionWithNullableResult(with(false),
					withNullableDbCallable(noAckTxn));
			oneOf(db).generateAck(noAckTxn, contactId, MAX_MESSAGE_IDS);
			will(returnValue(null));
			oneOf(db).transactionWithNullableResult(with(false),
					withNullableDbCallable(msgTxn));
			oneOf(db).generateRequestedBatch(msgTxn, contactId,
					DuplexOutgoingSessionTest.batchCapacity(), MAX_LATENCY);
			will(returnValue(singletonList(message)));
			oneOf(db).getNextSendTime(msgTxn, contactId, MAX_LATENCY);
			will(returnValue(Long.MAX_VALUE));
			oneOf(db).transactionWithNullableResult(with(false),
					withNullableDbCallable(noOfferTxn));
			oneOf(db).generateOffer(noOfferTxn, contactId, MAX_MESSAGE_IDS,
					MAX_LATENCY);
			will(returnValue(null));
			oneOf(db).getNextSendTime(noOfferTxn, contactId, MAX_LATENCY);
			will(returnValue(Long.MAX_VALUE));
			oneOf(db).transactionWithNullableResult(with(false),
					withNullableDbCallable(noRequestTxn));
			oneOf(db).generateRequest(noRequestTxn, contactId, MAX_MESSAGE_IDS);
			will(returnValue(null));
			oneOf(recordWriter).writeMessage(message);
			will(new DeferAndInterruptAction(dbExecutor, session[0]));
			oneOf(streamWriter).sendEndOfStream();
			oneOf(eventBus).removeListener(session[0]);
		}});

		session[0].run();
		assertTrue(dbExecutor.hasDeferredTasks());

		context.checking(new DbExpectations() {{
			oneOf(db).transaction(with(false), withDbRunnable(metaTxn));
			oneOf(db).getMessageMetadata(metaTxn, message.getId());
			will(returnValue(emptyMetadata));
			oneOf(metadataEncoder).encode(transportMetadata);
			will(returnValue(encodedMetadata));
			oneOf(db).mergeMessageMetadata(metaTxn, message.getId(),
					encodedMetadata);
		}});

		dbExecutor.runNextDeferredTask();
	}

	@Test
	public void testSendMessagesDoesNotOverwriteFirstSentTransport()
			throws Exception {
		DuplexOutgoingSession[] session = new DuplexOutgoingSession[1];
		session[0] = createSession();

		Transaction noAckTxn = new Transaction(null, false);
		Transaction msgTxn = new Transaction(null, false);
		Transaction noOfferTxn = new Transaction(null, false);
		Transaction noRequestTxn = new Transaction(null, false);
		Transaction metaTxn = new Transaction(null, false);
		Metadata existingMetadata = new Metadata();
		existingMetadata.put(KEY_FIRST_SENT_VIA_TRANSPORT, new byte[] {1});

		context.checking(new DbExpectations() {{
			allowing(clock).currentTimeMillis();
			will(returnValue(0L));
			oneOf(eventBus).addListener(session[0]);
			oneOf(recordWriter).writeVersions(with(any(Versions.class)));
			oneOf(db).transactionWithNullableResult(with(false),
					withNullableDbCallable(noAckTxn));
			oneOf(db).generateAck(noAckTxn, contactId, MAX_MESSAGE_IDS);
			will(returnValue(null));
			oneOf(db).transactionWithNullableResult(with(false),
					withNullableDbCallable(msgTxn));
			oneOf(db).generateRequestedBatch(msgTxn, contactId,
					DuplexOutgoingSessionTest.batchCapacity(), MAX_LATENCY);
			will(returnValue(singletonList(message)));
			oneOf(db).getNextSendTime(msgTxn, contactId, MAX_LATENCY);
			will(returnValue(Long.MAX_VALUE));
			oneOf(db).transactionWithNullableResult(with(false),
					withNullableDbCallable(noOfferTxn));
			oneOf(db).generateOffer(noOfferTxn, contactId, MAX_MESSAGE_IDS,
					MAX_LATENCY);
			will(returnValue(null));
			oneOf(db).getNextSendTime(noOfferTxn, contactId, MAX_LATENCY);
			will(returnValue(Long.MAX_VALUE));
			oneOf(db).transactionWithNullableResult(with(false),
					withNullableDbCallable(noRequestTxn));
			oneOf(db).generateRequest(noRequestTxn, contactId, MAX_MESSAGE_IDS);
			will(returnValue(null));
			oneOf(recordWriter).writeMessage(message);
			will(new DeferAndInterruptAction(dbExecutor, session[0]));
			oneOf(streamWriter).sendEndOfStream();
			oneOf(eventBus).removeListener(session[0]);
		}});

		session[0].run();
		assertTrue(dbExecutor.hasDeferredTasks());

		context.checking(new DbExpectations() {{
			oneOf(db).transaction(with(false), withDbRunnable(metaTxn));
			oneOf(db).getMessageMetadata(metaTxn, message.getId());
			will(returnValue(existingMetadata));
		}});

		dbExecutor.runNextDeferredTask();
	}

	@Test(expected = IOException.class)
	public void testSendMessagesDoesNotStoreFirstSentTransportIfWriteFails()
			throws Exception {
		DuplexOutgoingSession[] session = new DuplexOutgoingSession[1];
		session[0] = createSession();

		Transaction noAckTxn = new Transaction(null, false);
		Transaction msgTxn = new Transaction(null, false);
		Transaction noOfferTxn = new Transaction(null, false);
		Transaction noRequestTxn = new Transaction(null, false);

		context.checking(new DbExpectations() {{
			allowing(clock).currentTimeMillis();
			will(returnValue(0L));
			oneOf(eventBus).addListener(session[0]);
			oneOf(recordWriter).writeVersions(with(any(Versions.class)));
			oneOf(db).transactionWithNullableResult(with(false),
					withNullableDbCallable(noAckTxn));
			oneOf(db).generateAck(noAckTxn, contactId, MAX_MESSAGE_IDS);
			will(returnValue(null));
			oneOf(db).transactionWithNullableResult(with(false),
					withNullableDbCallable(msgTxn));
			oneOf(db).generateRequestedBatch(msgTxn, contactId,
					DuplexOutgoingSessionTest.batchCapacity(), MAX_LATENCY);
			will(returnValue(singletonList(message)));
			oneOf(db).getNextSendTime(msgTxn, contactId, MAX_LATENCY);
			will(returnValue(Long.MAX_VALUE));
			oneOf(db).transactionWithNullableResult(with(false),
					withNullableDbCallable(noOfferTxn));
			oneOf(db).generateOffer(noOfferTxn, contactId, MAX_MESSAGE_IDS,
					MAX_LATENCY);
			will(returnValue(null));
			oneOf(db).getNextSendTime(noOfferTxn, contactId, MAX_LATENCY);
			will(returnValue(Long.MAX_VALUE));
			oneOf(db).transactionWithNullableResult(with(false),
					withNullableDbCallable(noRequestTxn));
			oneOf(db).generateRequest(noRequestTxn, contactId, MAX_MESSAGE_IDS);
			will(returnValue(null));
			oneOf(recordWriter).writeMessage(message);
			will(throwException(new IOException()));
			oneOf(eventBus).removeListener(session[0]);
		}});

		try {
			session[0].run();
		} finally {
			assertFalse(dbExecutor.hasDeferredTasks());
		}
	}

	private DuplexOutgoingSession createSession() {
		return new DuplexOutgoingSession(db, metadataEncoder, dbExecutor,
				eventBus, clock, contactId, transportId, MAX_LATENCY,
				MAX_IDLE_TIME, streamWriter, recordWriter, null);
	}

	private static int batchCapacity() {
		return (org.briarproject.bramble.api.record.Record.RECORD_HEADER_BYTES +
				org.briarproject.bramble.api.sync.SyncConstants.MAX_MESSAGE_LENGTH) * 2;
	}

	private static class DeferringExecutor implements Executor {

		private final Queue<Runnable> deferred = new ArrayDeque<>();
		private boolean defer = false;

		@Override
		public void execute(Runnable command) {
			if (defer) deferred.add(command);
			else command.run();
		}

		private void deferNewTasks() {
			defer = true;
		}

		private boolean hasDeferredTasks() {
			return !deferred.isEmpty();
		}

		private void runNextDeferredTask() {
			deferred.remove().run();
		}
	}

	private static class DeferAndInterruptAction implements Action {

		private final DeferringExecutor executor;
		private final DuplexOutgoingSession session;

		private DeferAndInterruptAction(DeferringExecutor executor,
				DuplexOutgoingSession session) {
			this.executor = executor;
			this.session = session;
		}

		@Override
		public Object invoke(Invocation invocation) {
			executor.deferNewTasks();
			session.interrupt();
			return null;
		}

		@Override
		public void describeTo(Description description) {
			description.appendText("defers new tasks and interrupts session");
		}
	}
}
