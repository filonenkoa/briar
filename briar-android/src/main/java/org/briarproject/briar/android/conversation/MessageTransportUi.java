package org.briarproject.briar.android.conversation;

import org.briarproject.bramble.api.plugin.BluetoothConstants;
import org.briarproject.bramble.api.plugin.LanTcpConstants;
import org.briarproject.bramble.api.plugin.TorConstants;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.briar.R;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;

@Immutable
@NotNullByDefault
class MessageTransportUi {

	@DrawableRes
	static int getIcon(TransportId t) {
		if (TorConstants.ID.equals(t)) return R.drawable.ic_transport_internet;
		if (LanTcpConstants.ID.equals(t)) return R.drawable.ic_wifi_tethering;
		if (BluetoothConstants.ID.equals(t)) {
			return R.drawable.ic_transport_bluetooth;
		}
		return 0;
	}

	@StringRes
	static int getContentDescription(TransportId t, boolean incoming) {
		if (TorConstants.ID.equals(t)) return incoming ?
				R.string.message_received_via_internet :
				R.string.message_sent_via_internet;
		if (LanTcpConstants.ID.equals(t)) return incoming ?
				R.string.message_received_via_wifi :
				R.string.message_sent_via_wifi;
		if (BluetoothConstants.ID.equals(t)) return incoming ?
				R.string.message_received_via_bluetooth :
				R.string.message_sent_via_bluetooth;
		return 0;
	}

	static boolean isKnown(@Nullable TransportId t) {
		return t != null && getIcon(t) != 0;
	}
}
