package org.briarproject.briar.android.conversation;

import org.briarproject.bramble.api.plugin.TransportId;
import org.junit.Test;

import static org.briarproject.briar.android.conversation.ChatTransportState.Transport.BLUETOOTH;
import static org.briarproject.briar.android.conversation.ChatTransportState.Transport.INTERNET;
import static org.briarproject.briar.android.conversation.ChatTransportState.Transport.WIFI;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertSame;
import static org.junit.Assert.assertTrue;

public class ChatTransportStateTest {

	@Test
	public void testStartsInactive() {
		ChatTransportState state = ChatTransportState.empty();

		assertFalse(state.isActive(INTERNET));
		assertFalse(state.isActive(WIFI));
		assertFalse(state.isActive(BLUETOOTH));
	}

	@Test
	public void testMapsKnownTransportIds() {
		assertSame(INTERNET, ChatTransportState.Transport.fromId(
				org.briarproject.bramble.api.plugin.TorConstants.ID));
		assertSame(WIFI, ChatTransportState.Transport.fromId(
				org.briarproject.bramble.api.plugin.LanTcpConstants.ID));
		assertSame(BLUETOOTH, ChatTransportState.Transport.fromId(
				org.briarproject.bramble.api.plugin.BluetoothConstants.ID));
	}

	@Test
	public void testIgnoresUnknownTransportId() {
		ChatTransportState state = ChatTransportState.empty()
				.withTransport(new TransportId("example.unknown"), true);

		assertFalse(state.isActive(INTERNET));
		assertFalse(state.isActive(WIFI));
		assertFalse(state.isActive(BLUETOOTH));
	}

	@Test
	public void testCanRepresentMultipleActiveTransports() {
		ChatTransportState state = ChatTransportState.empty()
				.withTransport(
						org.briarproject.bramble.api.plugin.TorConstants.ID,
						true)
				.withTransport(
						org.briarproject.bramble.api.plugin.LanTcpConstants.ID,
						true)
				.withTransport(
						org.briarproject.bramble.api.plugin.BluetoothConstants.ID,
						true);

		assertTrue(state.isActive(INTERNET));
		assertTrue(state.isActive(WIFI));
		assertTrue(state.isActive(BLUETOOTH));
	}

	@Test
	public void testCanDeactivateOneTransport() {
		ChatTransportState state = ChatTransportState.empty()
				.withTransport(
						org.briarproject.bramble.api.plugin.TorConstants.ID,
						true)
				.withTransport(
						org.briarproject.bramble.api.plugin.LanTcpConstants.ID,
						true)
				.withTransport(
						org.briarproject.bramble.api.plugin.TorConstants.ID,
						false);

		assertFalse(state.isActive(INTERNET));
		assertTrue(state.isActive(WIFI));
		assertFalse(state.isActive(BLUETOOTH));
	}
}
