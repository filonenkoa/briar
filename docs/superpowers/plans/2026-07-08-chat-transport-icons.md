# Chat Transport Icons Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Show Internet, WiFi, and Bluetooth connection status as compact icons next to the contact name in one-to-one chats.

**Architecture:** Add a small `ChatTransportState` value object for transport-state mapping and test it directly. Keep UI rendering in `ConversationActivity`, using the existing injected `ConnectionRegistry` and existing activity event listener to update icons from initial registry state plus connection open/close events.

**Tech Stack:** Java, Android XML layouts, vector drawables, Android resource strings/colors, JUnit/Robolectric/JMock, Gradle Android build.

---

## File Map

- Create `briar-android/src/main/java/org/briarproject/briar/android/conversation/ChatTransportState.java`: immutable transport state and `Transport` enum used by the activity.
- Create `briar-android/src/test/java/org/briarproject/briar/android/conversation/ChatTransportStateTest.java`: focused tests for transport ID mapping and multi-active state.
- Modify `briar-android/src/main/res/layout/activity_conversation.xml`: add the three-icon strip next to `contactName`.
- Add `briar-android/src/main/res/drawable/ic_transport_internet.xml`: Internet/Tor toolbar icon.
- Add `briar-android/src/main/res/drawable/ic_transport_bluetooth.xml`: Bluetooth toolbar icon.
- Reuse `briar-android/src/main/res/drawable/ic_wifi_tethering.xml`: WiFi/LAN toolbar icon.
- Modify `briar-android/src/main/res/values/strings.xml`: add transport state strings.
- Modify `briar-android/src/main/java/org/briarproject/briar/android/conversation/ConversationActivity.java`: bind icons, render active/inactive tints, update state from registry/events, and show tap toast.

## Task 1: Add Transport State Model And Tests

**Files:**
- Create: `briar-android/src/main/java/org/briarproject/briar/android/conversation/ChatTransportState.java`
- Create: `briar-android/src/test/java/org/briarproject/briar/android/conversation/ChatTransportStateTest.java`

- [ ] **Step 1: Add failing state tests**

Create `ChatTransportStateTest.java`:

```java
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
```

- [ ] **Step 2: Run test to verify failure**

Run:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :briar-android:testOfficialDebugUnitTest --tests 'org.briarproject.briar.android.conversation.ChatTransportStateTest' --console=plain
```

Expected: FAIL because `ChatTransportState` does not exist.

- [ ] **Step 3: Add state model**

Create `ChatTransportState.java`:

```java
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
```

- [ ] **Step 4: Run state tests**

Run:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :briar-android:testOfficialDebugUnitTest --tests 'org.briarproject.briar.android.conversation.ChatTransportStateTest' --console=plain
```

Expected: PASS.

- [ ] **Step 5: Commit**

Run:

```bash
git add briar-android/src/main/java/org/briarproject/briar/android/conversation/ChatTransportState.java briar-android/src/test/java/org/briarproject/briar/android/conversation/ChatTransportStateTest.java
git commit -m "test: cover chat transport state"
```

## Task 2: Add Toolbar Icon Resources And Layout

**Files:**
- Modify: `briar-android/src/main/res/layout/activity_conversation.xml`
- Add: `briar-android/src/main/res/drawable/ic_transport_internet.xml`
- Add: `briar-android/src/main/res/drawable/ic_transport_bluetooth.xml`
- Modify: `briar-android/src/main/res/values/strings.xml`

- [ ] **Step 1: Add strings**

In `strings.xml`, add near other conversation/status strings:

```xml
<string name="transport_status_internet_active">Internet active</string>
<string name="transport_status_internet_inactive">Internet inactive</string>
<string name="transport_status_wifi_active">WiFi active</string>
<string name="transport_status_wifi_inactive">WiFi inactive</string>
<string name="transport_status_bluetooth_active">Bluetooth active</string>
<string name="transport_status_bluetooth_inactive">Bluetooth inactive</string>
<string name="transport_status_summary">%1$s, %2$s, %3$s</string>
```

- [ ] **Step 2: Add Internet icon**

Create `ic_transport_internet.xml`:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
	android:width="24dp"
	android:height="24dp"
	android:viewportWidth="24"
	android:viewportHeight="24">
	<path
		android:fillColor="@android:color/white"
		android:pathData="M12,2C6.48,2 2,6.48 2,12s4.48,10 10,10 10,-4.48 10,-10S17.52,2 12,2zM18.93,8h-2.95c-0.32,-1.25 -0.82,-2.39 -1.47,-3.31C16.38,5.33 17.93,6.51 18.93,8zM12,4.04c0.83,1.2 1.48,2.53 1.82,3.96h-3.64C10.52,6.57 11.17,5.24 12,4.04zM4.26,14C4.09,13.36 4,12.69 4,12s0.09,-1.36 0.26,-2h3.36C7.54,10.66 7.5,11.33 7.5,12s0.04,1.34 0.12,2H4.26zM5.07,16h2.95c0.32,1.25 0.82,2.39 1.47,3.31C7.62,18.67 6.07,17.49 5.07,16zM8.02,8H5.07c1,-1.49 2.55,-2.67 4.42,-3.31C8.84,5.61 8.34,6.75 8.02,8zM12,19.96c-0.83,-1.2 -1.48,-2.53 -1.82,-3.96h3.64C13.48,17.43 12.83,18.76 12,19.96zM14.25,14h-4.5C9.66,13.34 9.6,12.67 9.6,12s0.06,-1.34 0.15,-2h4.5c0.09,0.66 0.15,1.33 0.15,2s-0.06,1.34 -0.15,2zM14.51,19.31c0.65,-0.92 1.15,-2.06 1.47,-3.31h2.95C17.93,17.49 16.38,18.67 14.51,19.31zM16.38,14c0.08,-0.66 0.12,-1.33 0.12,-2s-0.04,-1.34 -0.12,-2h3.36c0.17,0.64 0.26,1.31 0.26,2s-0.09,1.36 -0.26,2h-3.36z" />
</vector>
```

- [ ] **Step 3: Add Bluetooth icon**

Create `ic_transport_bluetooth.xml`:

```xml
<vector xmlns:android="http://schemas.android.com/apk/res/android"
	android:width="24dp"
	android:height="24dp"
	android:viewportWidth="24"
	android:viewportHeight="24">
	<path
		android:fillColor="@android:color/white"
		android:pathData="M17.71,7.71L12,2h-1v7.59L6.41,5 5,6.41 10.59,12 5,17.59 6.41,19 11,14.41V22h1l5.71,-5.71L13.41,12l4.3,-4.29zM13,5.83l1.88,1.88L13,9.59V5.83zM14.88,16.29L13,18.17v-3.76l1.88,1.88z" />
</vector>
```

- [ ] **Step 4: Add icon strip to toolbar layout**

In `activity_conversation.xml`, replace the current `contactName` view with a weighted contact name followed by icon strip:

```xml
<com.vanniktech.emoji.EmojiTextView
	android:id="@+id/contactName"
	style="@style/TextAppearance.AppCompat.Widget.ActionBar.Title.Inverse"
	android:layout_width="0dp"
	android:layout_height="match_parent"
	android:layout_marginStart="@dimen/margin_medium"
	android:layout_marginLeft="@dimen/margin_medium"
	android:layout_weight="1"
	android:ellipsize="end"
	android:gravity="center_vertical"
	android:maxLines="1"
	android:textColor="@color/action_bar_text"
	tools:text="Contact Name of someone who chose a long name" />

<LinearLayout
	android:id="@+id/transportStatus"
	android:layout_width="wrap_content"
	android:layout_height="match_parent"
	android:layout_marginStart="6dp"
	android:layout_marginLeft="6dp"
	android:gravity="center_vertical"
	android:orientation="horizontal">

	<ImageView
		android:id="@+id/transportInternet"
		android:layout_width="18dp"
		android:layout_height="18dp"
		android:layout_marginEnd="3dp"
		android:layout_marginRight="3dp"
		android:src="@drawable/ic_transport_internet" />

	<ImageView
		android:id="@+id/transportWifi"
		android:layout_width="18dp"
		android:layout_height="18dp"
		android:layout_marginEnd="3dp"
		android:layout_marginRight="3dp"
		android:src="@drawable/ic_wifi_tethering" />

	<ImageView
		android:id="@+id/transportBluetooth"
		android:layout_width="18dp"
		android:layout_height="18dp"
		android:src="@drawable/ic_transport_bluetooth" />

</LinearLayout>
```

Also change the parent `LinearLayout` inside the toolbar from `wrap_content` to `match_parent` width so the weighted name can ellipsize before the icons:

```xml
android:layout_width="match_parent"
```

- [ ] **Step 5: Run resource compile**

Run:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :briar-android:compileOfficialDebugJavaWithJavac --console=plain
```

Expected: PASS.

- [ ] **Step 6: Commit**

Run:

```bash
git add briar-android/src/main/res/layout/activity_conversation.xml briar-android/src/main/res/drawable/ic_transport_internet.xml briar-android/src/main/res/drawable/ic_transport_bluetooth.xml briar-android/src/main/res/values/strings.xml
git commit -m "feat: add chat transport icons"
```

## Task 3: Wire Transport Icons In ConversationActivity

**Files:**
- Modify: `briar-android/src/main/java/org/briarproject/briar/android/conversation/ConversationActivity.java`

- [ ] **Step 1: Add imports and fields**

Add imports:

```java
import android.content.res.ColorStateList;

import org.briarproject.bramble.api.plugin.event.ConnectionClosedEvent;
import org.briarproject.bramble.api.plugin.event.ConnectionOpenedEvent;

import androidx.core.widget.ImageViewCompat;

import static org.briarproject.briar.android.conversation.ChatTransportState.Transport.BLUETOOTH;
import static org.briarproject.briar.android.conversation.ChatTransportState.Transport.INTERNET;
import static org.briarproject.briar.android.conversation.ChatTransportState.Transport.WIFI;
```

Add fields near the toolbar fields:

```java
private View transportStatus;
private ImageView transportInternet;
private ImageView transportWifi;
private ImageView transportBluetooth;
private ColorStateList transportActiveTint;
private ColorStateList transportInactiveTint;
private ChatTransportState transportState = ChatTransportState.empty();
```

- [ ] **Step 2: Bind icon views**

In `onCreate()`, after `toolbarTitle = toolbar.findViewById(R.id.contactName);`, add:

```java
transportStatus = toolbar.findViewById(R.id.transportStatus);
transportInternet = toolbar.findViewById(R.id.transportInternet);
transportWifi = toolbar.findViewById(R.id.transportWifi);
transportBluetooth = toolbar.findViewById(R.id.transportBluetooth);
transportActiveTint = ColorStateList.valueOf(ContextCompat.getColor(this,
		R.color.action_bar_text));
transportInactiveTint = ColorStateList.valueOf(ContextCompat.getColor(this,
		R.color.briar_gray_300));
transportStatus.setOnClickListener(v -> Toast.makeText(this,
		getTransportStatusText(), LENGTH_SHORT).show());
displayTransportStatus();
```

- [ ] **Step 3: Add initial registry state and rendering methods**

Add these methods near `displayContactOnlineStatus()`:

```java
@UiThread
private void displayTransportStatus() {
	transportState = ChatTransportState.empty()
			.withTransport(org.briarproject.bramble.api.plugin.TorConstants.ID,
					connectionRegistry.isConnected(contactId,
							org.briarproject.bramble.api.plugin.TorConstants.ID))
			.withTransport(org.briarproject.bramble.api.plugin.LanTcpConstants.ID,
					connectionRegistry.isConnected(contactId,
							org.briarproject.bramble.api.plugin.LanTcpConstants.ID))
			.withTransport(org.briarproject.bramble.api.plugin.BluetoothConstants.ID,
					connectionRegistry.isConnected(contactId,
							org.briarproject.bramble.api.plugin.BluetoothConstants.ID));
	renderTransportStatus();
}

@UiThread
private void renderTransportStatus() {
	setTransportTint(transportInternet, transportState.isActive(INTERNET));
	setTransportTint(transportWifi, transportState.isActive(WIFI));
	setTransportTint(transportBluetooth, transportState.isActive(BLUETOOTH));
	transportStatus.setContentDescription(getTransportStatusText());
}

@UiThread
private void setTransportTint(ImageView view, boolean active) {
	ImageViewCompat.setImageTintList(view,
			active ? transportActiveTint : transportInactiveTint);
}

private String getTransportStatusText() {
	String internet = getString(transportState.isActive(INTERNET) ?
			R.string.transport_status_internet_active :
			R.string.transport_status_internet_inactive);
	String wifi = getString(transportState.isActive(WIFI) ?
			R.string.transport_status_wifi_active :
			R.string.transport_status_wifi_inactive);
	String bluetooth = getString(transportState.isActive(BLUETOOTH) ?
			R.string.transport_status_bluetooth_active :
			R.string.transport_status_bluetooth_inactive);
	return getString(R.string.transport_status_summary, internet, wifi,
			bluetooth);
}
```

- [ ] **Step 4: Update start and connection events**

In `onStart()`, after `displayContactOnlineStatus();`, add:

```java
displayTransportStatus();
```

In `eventOccurred(Event e)`, add branches before `ContactConnectedEvent`:

```java
} else if (e instanceof ConnectionOpenedEvent) {
	ConnectionOpenedEvent c = (ConnectionOpenedEvent) e;
	if (c.getContactId().equals(contactId)) {
		runOnUiThreadUnlessDestroyed(() -> {
			transportState = transportState.withTransport(c.getTransportId(), true);
			renderTransportStatus();
		});
	}
} else if (e instanceof ConnectionClosedEvent) {
	ConnectionClosedEvent c = (ConnectionClosedEvent) e;
	if (c.getContactId().equals(contactId)) {
		runOnUiThreadUnlessDestroyed(() -> {
			transportState = transportState.withTransport(c.getTransportId(), false);
			renderTransportStatus();
		});
	}
```

Keep the existing `ContactConnectedEvent` and `ContactDisconnectedEvent` branches unchanged for the avatar online/offline dot.

- [ ] **Step 5: Run Android test and compile**

Run:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :briar-android:testOfficialDebugUnitTest --tests 'org.briarproject.briar.android.conversation.ChatTransportStateTest' --console=plain
```

Expected: PASS.

Run:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :briar-android:compileOfficialDebugJavaWithJavac --console=plain
```

Expected: PASS.

- [ ] **Step 6: Commit**

Run:

```bash
git add briar-android/src/main/java/org/briarproject/briar/android/conversation/ConversationActivity.java
git commit -m "feat: show chat transport status"
```

## Task 4: Full Verification And Push

**Files:**
- No code files unless verification reveals a defect.

- [ ] **Step 1: Run focused tests**

Run:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :briar-android:testOfficialDebugUnitTest --tests 'org.briarproject.briar.android.conversation.ChatTransportStateTest' --tests 'org.briarproject.briar.android.conversation.ConversationViewModelTest' --console=plain
```

Expected: PASS.

- [ ] **Step 2: Build debug APK**

Run:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :briar-android:assembleOfficialDebug --console=plain
```

Expected: PASS and APK at `briar-android/build/outputs/apk/official/debug/briar-android-official-debug.apk`.

- [ ] **Step 3: Check whitespace and status**

Run:

```bash
git diff --check
git status --short --branch
```

Expected: `git diff --check` prints no output. Status shows a clean worktree on `filonenkoa/no_relogin` ahead of origin by the transport-icon commits.

- [ ] **Step 4: Push**

Run:

```bash
git push
```

Expected: branch `filonenkoa/no_relogin` updates on `github.com:filonenkoa/briar.git`.

## Self-Review

- Spec coverage: the plan adds all three icons next to the contact name, keeps them always visible, colors active and inactive states, supports multiple active transports, preserves avatar status, maps Tor/LAN/Bluetooth IDs, uses registry initial state and connection events, adds content description and tap toast, and verifies build/tests.
- Placeholder scan: no TBD/TODO placeholders remain; exact resource names, paths, and commands are specified.
- Type consistency: `ChatTransportState`, `Transport.INTERNET`, `Transport.WIFI`, `Transport.BLUETOOTH`, resource IDs, and event classes are named consistently across tasks.
