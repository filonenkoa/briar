package org.briarproject.briar.android.conversation;

import org.briarproject.bramble.api.plugin.BluetoothConstants;
import org.briarproject.bramble.api.plugin.LanTcpConstants;
import org.briarproject.bramble.api.plugin.TorConstants;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.briar.R;
import org.junit.Test;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

public class MessageTransportUiTest {

	@Test
	public void testIncomingTransportDescriptions() {
		assertEquals(R.string.message_received_via_internet,
				MessageTransportUi.getContentDescription(TorConstants.ID, true));
		assertEquals(R.string.message_received_via_wifi,
				MessageTransportUi.getContentDescription(LanTcpConstants.ID, true));
		assertEquals(R.string.message_received_via_bluetooth,
				MessageTransportUi.getContentDescription(BluetoothConstants.ID,
						true));
	}

	@Test
	public void testOutgoingTransportDescriptions() {
		assertEquals(R.string.message_sent_via_internet,
				MessageTransportUi.getContentDescription(TorConstants.ID, false));
		assertEquals(R.string.message_sent_via_wifi,
				MessageTransportUi.getContentDescription(LanTcpConstants.ID, false));
		assertEquals(R.string.message_sent_via_bluetooth,
				MessageTransportUi.getContentDescription(BluetoothConstants.ID,
						false));
	}

	@Test
	public void testKnownTransportHasIcon() {
		assertTrue(MessageTransportUi.isKnown(TorConstants.ID));
		assertTrue(MessageTransportUi.isKnown(LanTcpConstants.ID));
		assertTrue(MessageTransportUi.isKnown(BluetoothConstants.ID));
	}

	@Test
	public void testUnknownTransportIsHidden() {
		assertFalse(MessageTransportUi.isKnown(null));
		assertFalse(MessageTransportUi.isKnown(new TransportId("example.unknown")));
	}
}
