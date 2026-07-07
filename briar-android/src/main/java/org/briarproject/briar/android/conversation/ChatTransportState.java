package org.briarproject.briar.android.conversation;

import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
class ChatTransportState {

	enum Transport {
		INTERNET,
		WIFI,
		BLUETOOTH;

		@Nullable
		static Transport fromId(TransportId id) {
			if (org.briarproject.bramble.api.plugin.TorConstants.ID.equals(id)) {
				return INTERNET;
			}
			if (org.briarproject.bramble.api.plugin.LanTcpConstants.ID.equals(id)) {
				return WIFI;
			}
			if (org.briarproject.bramble.api.plugin.BluetoothConstants.ID.equals(id)) {
				return BLUETOOTH;
			}
			return null;
		}
	}

	private final boolean internetActive;
	private final boolean wifiActive;
	private final boolean bluetoothActive;

	private ChatTransportState(boolean internetActive, boolean wifiActive,
			boolean bluetoothActive) {
		this.internetActive = internetActive;
		this.wifiActive = wifiActive;
		this.bluetoothActive = bluetoothActive;
	}

	static ChatTransportState empty() {
		return new ChatTransportState(false, false, false);
	}

	ChatTransportState withTransport(TransportId id, boolean active) {
		Transport transport = Transport.fromId(id);
		if (transport == null) return this;
		return withTransport(transport, active);
	}

	ChatTransportState withTransport(Transport transport, boolean active) {
		if (transport == Transport.INTERNET) {
			return new ChatTransportState(active, wifiActive, bluetoothActive);
		} else if (transport == Transport.WIFI) {
			return new ChatTransportState(internetActive, active, bluetoothActive);
		} else if (transport == Transport.BLUETOOTH) {
			return new ChatTransportState(internetActive, wifiActive, active);
		}
		throw new AssertionError();
	}

	boolean isActive(Transport transport) {
		if (transport == Transport.INTERNET) return internetActive;
		if (transport == Transport.WIFI) return wifiActive;
		if (transport == Transport.BLUETOOTH) return bluetoothActive;
		throw new AssertionError();
	}
}
