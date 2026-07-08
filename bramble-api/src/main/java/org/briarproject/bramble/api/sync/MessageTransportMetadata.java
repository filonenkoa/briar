package org.briarproject.bramble.api.sync;

import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.concurrent.Immutable;

/** Metadata keys for recording the transport used to transfer message bodies. */
@Immutable
@NotNullByDefault
public final class MessageTransportMetadata {

	/**
	 * Key for the transport used to receive an incoming message body. The value
	 * is a BDF-encoded transport ID string.
	 */
	public static final String KEY_RECEIVED_VIA_TRANSPORT =
			"org.briarproject.bramble.receivedViaTransport";
	/**
	 * Key for the first transport used to send an outgoing message body. The
	 * value is a BDF-encoded transport ID string and should not be overwritten
	 * by retransmission.
	 */
	public static final String KEY_FIRST_SENT_VIA_TRANSPORT =
			"org.briarproject.bramble.firstSentViaTransport";

	private MessageTransportMetadata() {
	}
}
