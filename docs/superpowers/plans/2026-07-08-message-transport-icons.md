# Message Transport Icons Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist and display the transport used to receive incoming private text messages and the first transport used to send outgoing private text messages.

**Architecture:** Store per-message transport metadata in Bramble sync/database metadata because the transport is known at sync-session time. Expose the nullable transport ID through Briar private message headers, then render it in Android conversation rows with the existing Internet/Wi-Fi/Bluetooth icon mapping.

**Tech Stack:** Java, Android XML views, JUnit/JMock, Gradle modules `bramble-api`, `bramble-core`, `briar-api`, `briar-core`, `briar-android`.

---

## File Structure

- Create `bramble-api/src/main/java/org/briarproject/bramble/api/sync/MessageTransportMetadata.java`: owns shared metadata key names for transport IDs.
- Modify `bramble-core/src/main/java/org/briarproject/bramble/sync/IncomingSession.java`: receive sessions carry a `TransportId` and store received-via metadata after receiving a message.
- Modify `bramble-core/src/main/java/org/briarproject/bramble/sync/SyncSessionFactoryImpl.java`: pass transport IDs into incoming sessions.
- Modify `bramble-core/src/main/java/org/briarproject/bramble/connection/IncomingDuplexSyncConnection.java` and `IncomingSimplexSyncConnection.java`: create incoming sessions with their existing `transportId`.
- Modify `bramble-core/src/main/java/org/briarproject/bramble/sync/SimplexOutgoingSession.java` and `DuplexOutgoingSession.java`: store first-sent-via metadata for message bodies selected for sending, without overwriting existing metadata.
- Add or extend Bramble sync tests around metadata persistence.
- Modify `briar-api/src/main/java/org/briarproject/briar/api/messaging/PrivateMessageHeader.java`: add nullable `TransportId transportId` getter.
- Modify `briar-core/src/main/java/org/briarproject/briar/messaging/MessagingManagerImpl.java`: decode the Bramble metadata into private message headers.
- Modify Android conversation item/view-holder/layout resources to render a small per-message transport icon.
- Add Android unit tests for transport mapping/content descriptions.

## Task 1: Bramble Metadata Contract

**Files:**
- Create: `bramble-api/src/main/java/org/briarproject/bramble/api/sync/MessageTransportMetadata.java`
- Test: add focused unit tests near existing Bramble API/core tests if a suitable test package exists; otherwise test via Task 2 sync tests.

- [ ] **Step 1: Write the metadata key contract**

Create `MessageTransportMetadata.java`:

```java
package org.briarproject.bramble.api.sync;

import org.briarproject.nullsafety.NotNullByDefault;
import javax.annotation.concurrent.Immutable;

@Immutable
@NotNullByDefault
public final class MessageTransportMetadata {

	public static final String KEY_RECEIVED_VIA_TRANSPORT =
			"org.briarproject.bramble.receivedViaTransport";
	public static final String KEY_FIRST_SENT_VIA_TRANSPORT =
			"org.briarproject.bramble.firstSentViaTransport";

	private MessageTransportMetadata() {
	}
}
```

- [ ] **Step 2: Compile the API module**

Run:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :bramble-api:compileJava --console=plain
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Commit this task only if requested**

If committing is requested, stage only this file:

```bash
git add bramble-api/src/main/java/org/briarproject/bramble/api/sync/MessageTransportMetadata.java
git commit -m "feat: add message transport metadata keys"
```

## Task 2: Persist Transport Metadata in Sync Sessions

**Files:**
- Modify: `bramble-api/src/main/java/org/briarproject/bramble/api/sync/SyncSessionFactory.java`
- Modify: `bramble-core/src/main/java/org/briarproject/bramble/sync/IncomingSession.java`
- Modify: `bramble-core/src/main/java/org/briarproject/bramble/sync/SyncSessionFactoryImpl.java`
- Modify: `bramble-core/src/main/java/org/briarproject/bramble/connection/IncomingDuplexSyncConnection.java`
- Modify: `bramble-core/src/main/java/org/briarproject/bramble/connection/IncomingSimplexSyncConnection.java`
- Modify: `bramble-core/src/main/java/org/briarproject/bramble/sync/SimplexOutgoingSession.java`
- Modify: `bramble-core/src/main/java/org/briarproject/bramble/sync/DuplexOutgoingSession.java`
- Test: `bramble-core/src/test/java/org/briarproject/bramble/sync/SimplexOutgoingSessionTest.java`
- Test: add `bramble-core/src/test/java/org/briarproject/bramble/sync/IncomingSessionTest.java` if no existing incoming-session test covers `receiveMessage()`.

- [ ] **Step 1: Write failing outgoing first-sent tests**

Add tests proving metadata is written as BDF-encoded string values, because Briar reads message metadata through `ClientHelper.getMessageMetadataAsDictionary()`:

```java
@Test
public void testSendMessagesStoresFirstSentTransport() throws Exception {
	// Arrange a local visible message selected by generateBatch().
	// Expect db.getMessageMetadata(txn, messageId) to return metadata without
	// KEY_FIRST_SENT_VIA_TRANSPORT.
	// Expect db.mergeMessageMetadata(txn, messageId, encoded metadata whose
	// BDF dictionary value is LanTcpConstants.ID.getString()).
	// Run sendMessages().
}

@Test
public void testSendMessagesDoesNotOverwriteFirstSentTransport()
		throws Exception {
	// Arrange a local visible message selected by generateBatch().
	// Expect db.getMessageMetadata(txn, messageId) to return metadata with
	// KEY_FIRST_SENT_VIA_TRANSPORT already present.
	// Expect no db.mergeMessageMetadata() call for that message.
	// Run sendMessages() over LanTcpConstants.ID.
}
```

Use the existing mocking style in `SimplexOutgoingSessionTest.java`. The key behavioral assertions are the `getMessageMetadata()` and `mergeMessageMetadata()` calls.

- [ ] **Step 2: Run outgoing tests to verify red**

Run:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :bramble-core:test --tests 'org.briarproject.bramble.sync.SimplexOutgoingSessionTest' --console=plain
```

Expected: new tests fail because first-sent metadata is not written.

- [ ] **Step 3: Implement outgoing metadata write**

In `SimplexOutgoingSession`, inject `MetadataEncoder` through the constructor and store it in a field. In `generateAndSendBatch()`, after `db.generateBatch(...)` returns a non-null batch and before writing records, record first-sent metadata for each message whose raw metadata does not already contain the key:

```java
private void recordFirstSentTransport(Collection<Message> messages)
		throws DbException {
	db.transaction(false, txn -> {
		for (Message m : messages) {
			Metadata meta = db.getMessageMetadata(txn, m.getId());
			if (!meta.containsKey(KEY_FIRST_SENT_VIA_TRANSPORT)) {
				BdfDictionary transportMeta = new BdfDictionary();
				transportMeta.put(KEY_FIRST_SENT_VIA_TRANSPORT,
						transportId.getString());
				db.mergeMessageMetadata(txn, m.getId(),
						metadataEncoder.encode(transportMeta));
			}
		}
	});
}
```

Add imports:

```java
import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.bramble.api.data.MetadataEncoder;
import org.briarproject.bramble.api.db.Metadata;

import static org.briarproject.bramble.api.sync.MessageTransportMetadata.KEY_FIRST_SENT_VIA_TRANSPORT;
```

Call it before `recordWriter.writeMessage(m)`. Update `SyncSessionFactoryImpl.createSimplexOutgoingSession(...)` to pass its injected `MetadataEncoder` to `SimplexOutgoingSession`, `EagerSimplexOutgoingSession`, and `MailboxOutgoingSession` constructors if their constructors now need the superclass argument.

Apply equivalent logic to `DuplexOutgoingSession.WriteBatch.run()` for requested batches generated by duplex sessions. Update `SyncSessionFactoryImpl.createDuplexOutgoingSession(...)` to pass `MetadataEncoder`.

- [ ] **Step 4: Write failing incoming received-via test**

Add `IncomingSessionTest` or extend an existing incoming session test with:

```java
@Test
public void testReceiveMessageStoresReceivedViaTransport() throws Exception {
	// Arrange IncomingSession with LanTcpConstants.ID.
	// Arrange recordReader.hasMessage() then readMessage() returning message.
	// Expect db.receiveMessage(txn, contactId, message).
	// Expect db.getMessageMetadata(txn, message.getId()) returning metadata
	// without KEY_RECEIVED_VIA_TRANSPORT.
	// Expect db.mergeMessageMetadata(txn, message.getId(), encoded metadata
	// whose BDF dictionary value is LanTcpConstants.ID.getString()).
}
```

- [ ] **Step 5: Run incoming test to verify red**

Run:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :bramble-core:test --tests 'org.briarproject.bramble.sync.IncomingSessionTest' --console=plain
```

Expected: fails because `IncomingSession` does not have or store the transport ID yet.

- [ ] **Step 6: Implement incoming metadata write**

Change `IncomingSession` constructor to accept `TransportId transportId`, store it in a field, and update `ReceiveMessage.run()`:

```java
db.transaction(false, txn -> {
	db.receiveMessage(txn, contactId, message);
	Metadata meta = db.getMessageMetadata(txn, message.getId());
	if (!meta.containsKey(KEY_RECEIVED_VIA_TRANSPORT)) {
		BdfDictionary transportMeta = new BdfDictionary();
		transportMeta.put(KEY_RECEIVED_VIA_TRANSPORT,
				transportId.getString());
		db.mergeMessageMetadata(txn, message.getId(),
				metadataEncoder.encode(transportMeta));
	}
});
```

Add imports:

```java
import org.briarproject.bramble.api.data.BdfDictionary;
import org.briarproject.bramble.api.data.MetadataEncoder;
import org.briarproject.bramble.api.db.Metadata;
import org.briarproject.bramble.api.plugin.TransportId;

import static org.briarproject.bramble.api.sync.MessageTransportMetadata.KEY_RECEIVED_VIA_TRANSPORT;
```

Update `SyncSessionFactory.createIncomingSession(...)` interface and `SyncSessionFactoryImpl.createIncomingSession(...)` to take `TransportId t`. Inject `MetadataEncoder` into `SyncSessionFactoryImpl` and pass it to incoming/outgoing session constructors. Update both incoming connection callers to pass their existing `transportId`.

- [ ] **Step 7: Run Bramble sync tests**

Run:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :bramble-core:test --tests 'org.briarproject.bramble.sync.*SessionTest' --console=plain
```

Expected: `BUILD SUCCESSFUL`.

## Task 3: Expose Transport Metadata Through PrivateMessageHeader

**Files:**
- Modify: `briar-api/src/main/java/org/briarproject/briar/api/messaging/PrivateMessageHeader.java`
- Modify: `briar-core/src/main/java/org/briarproject/briar/messaging/MessagingManagerImpl.java`
- Test: `briar-core/src/test/java/org/briarproject/briar/messaging/MessagingManagerIntegrationTest.java`

- [ ] **Step 1: Write failing messaging integration test**

Add a test that creates one local private message with first-sent metadata and one remote private message with received-via metadata encoded via `MetadataEncoder`, then loads headers:

```java
@Test
public void testPrivateMessageHeadersIncludeTransferTransport()
		throws Exception {
	// Create/send a local private message and merge metadata
	// KEY_FIRST_SENT_VIA_TRANSPORT = LanTcpConstants.ID.getString().
	// Create/receive a remote private message and merge metadata
	// KEY_RECEIVED_VIA_TRANSPORT = BluetoothConstants.ID.getString().
	// Load conversation headers.
	// Assert the local PrivateMessageHeader transport is LanTcpConstants.ID.
	// Assert the remote PrivateMessageHeader transport is BluetoothConstants.ID.
}
```

- [ ] **Step 2: Run test to verify red**

Run:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :briar-core:test --tests 'org.briarproject.briar.messaging.MessagingManagerIntegrationTest' --console=plain
```

Expected: fails because `PrivateMessageHeader` has no transport getter.

- [ ] **Step 3: Add nullable transport to PrivateMessageHeader**

Modify `PrivateMessageHeader`:

```java
import org.briarproject.bramble.api.plugin.TransportId;

import javax.annotation.Nullable;

@Nullable
private final TransportId transportId;

public PrivateMessageHeader(MessageId id, GroupId groupId, long timestamp,
		boolean local, boolean read, boolean sent, boolean seen,
		boolean hasText, List<AttachmentHeader> headers,
		long autoDeleteTimer, @Nullable TransportId transportId) {
	super(id, groupId, timestamp, local, read, sent, seen, autoDeleteTimer);
	this.hasText = hasText;
	this.attachmentHeaders = headers;
	this.transportId = transportId;
}

@Nullable
public TransportId getTransportId() {
	return transportId;
}
```

Update all constructor call sites to pass `null` until `MessagingManagerImpl` is updated.

- [ ] **Step 4: Decode metadata in MessagingManagerImpl**

In `getMessageHeaders()`, after `local` is read, decode the relevant BDF metadata with a private helper:

```java
@Nullable
private TransportId getTransferTransport(BdfDictionary meta, boolean local)
		throws FormatException {
	String key = local ? KEY_FIRST_SENT_VIA_TRANSPORT :
			KEY_RECEIVED_VIA_TRANSPORT;
	String transport = meta.getOptionalString(key);
	return transport == null ? null : new TransportId(transport);
}
```

Pass `transportId` into every `PrivateMessageHeader` constructor in this method.

- [ ] **Step 5: Run Briar messaging tests**

Run:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :briar-core:test --tests 'org.briarproject.briar.messaging.MessagingManagerIntegrationTest' --console=plain
```

Expected: `BUILD SUCCESSFUL`.

## Task 4: Render Per-Message Transport Icons in Android

**Files:**
- Modify: `briar-android/src/main/java/org/briarproject/briar/android/conversation/ConversationItem.java`
- Modify: `briar-android/src/main/java/org/briarproject/briar/android/conversation/ConversationItemViewHolder.java`
- Modify: `briar-android/src/main/res/layout/list_item_conversation_msg_in_content.xml`
- Modify: `briar-android/src/main/res/layout/list_item_conversation_msg_out.xml`
- Modify: `briar-android/src/main/res/values/strings.xml`
- Test: `briar-android/src/test/java/org/briarproject/briar/android/conversation/ChatTransportStateTest.java` or a new small mapper test.

- [ ] **Step 1: Write failing Android mapper test**

Create or extend a test for a small mapper method that maps transport and direction to drawable/content description resource IDs:

```java
@Test
public void testIncomingTransportDescriptions() {
	assertEquals(R.string.message_received_via_internet,
			MessageTransportUi.getContentDescription(TorConstants.ID, true));
	assertEquals(R.string.message_received_via_wifi,
			MessageTransportUi.getContentDescription(LanTcpConstants.ID, true));
	assertEquals(R.string.message_received_via_bluetooth,
			MessageTransportUi.getContentDescription(BluetoothConstants.ID, true));
}

@Test
public void testOutgoingTransportDescriptions() {
	assertEquals(R.string.message_sent_via_internet,
			MessageTransportUi.getContentDescription(TorConstants.ID, false));
	assertEquals(R.string.message_sent_via_wifi,
			MessageTransportUi.getContentDescription(LanTcpConstants.ID, false));
	assertEquals(R.string.message_sent_via_bluetooth,
			MessageTransportUi.getContentDescription(BluetoothConstants.ID, false));
}
```

- [ ] **Step 2: Run mapper test to verify red**

Run:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :briar-android:testOfficialDebugUnitTest --tests 'org.briarproject.briar.android.conversation.*Transport*Test' --console=plain
```

Expected: fails because mapper/resources do not exist.

- [ ] **Step 3: Add Android mapper**

Create `briar-android/src/main/java/org/briarproject/briar/android/conversation/MessageTransportUi.java`:

```java
package org.briarproject.briar.android.conversation;

import org.briarproject.bramble.api.plugin.BluetoothConstants;
import org.briarproject.bramble.api.plugin.LanTcpConstants;
import org.briarproject.bramble.api.plugin.TorConstants;
import org.briarproject.bramble.api.plugin.TransportId;
import org.briarproject.briar.R;
import org.briarproject.nullsafety.NotNullByDefault;

import javax.annotation.Nullable;

import androidx.annotation.DrawableRes;
import androidx.annotation.StringRes;

@NotNullByDefault
class MessageTransportUi {

	@DrawableRes
	static int getIcon(TransportId t) {
		if (t.equals(TorConstants.ID)) return R.drawable.ic_transport_internet;
		if (t.equals(LanTcpConstants.ID)) return R.drawable.ic_wifi_tethering;
		if (t.equals(BluetoothConstants.ID)) return R.drawable.ic_transport_bluetooth;
		return 0;
	}

	@StringRes
	static int getContentDescription(TransportId t, boolean incoming) {
		if (t.equals(TorConstants.ID)) return incoming ?
				R.string.message_received_via_internet :
				R.string.message_sent_via_internet;
		if (t.equals(LanTcpConstants.ID)) return incoming ?
				R.string.message_received_via_wifi :
				R.string.message_sent_via_wifi;
		if (t.equals(BluetoothConstants.ID)) return incoming ?
				R.string.message_received_via_bluetooth :
				R.string.message_sent_via_bluetooth;
		return 0;
	}

	static boolean isKnown(@Nullable TransportId t) {
		return t != null && getIcon(t) != 0;
	}
}
```

- [ ] **Step 4: Add strings**

Add to `strings.xml` near existing transport status strings:

```xml
<string name="message_received_via_internet">Received via Internet</string>
<string name="message_received_via_wifi">Received via Wi-Fi</string>
<string name="message_received_via_bluetooth">Received via Bluetooth</string>
<string name="message_sent_via_internet">Sent via Internet</string>
<string name="message_sent_via_wifi">Sent via Wi-Fi</string>
<string name="message_sent_via_bluetooth">Sent via Bluetooth</string>
```

- [ ] **Step 5: Add icon views to layouts**

In both `list_item_conversation_msg_in_content.xml` and `list_item_conversation_msg_out.xml`, add an `ImageView` with ID `@+id/messageTransport` inside `statusLayout`, next to `time` and before `bomb`/`status`:

```xml
<ImageView
	android:id="@+id/messageTransport"
	android:layout_width="12dp"
	android:layout_height="12dp"
	android:layout_marginStart="4dp"
	android:layout_marginLeft="4dp"
	android:visibility="gone"
	app:tint="?android:attr/textColorSecondary"
	tools:ignore="ContentDescription"
	tools:src="@drawable/ic_transport_internet"
	tools:visibility="visible" />
```

For outgoing layout use `app:tint="@color/private_message_date_inverse"`.

- [ ] **Step 6: Wire item/header state to view holder**

In `ConversationItem`, add:

```java
@Nullable
private final TransportId transportId;
```

Set it in the constructor from `PrivateMessageHeader` only:

```java
this.transportId = h instanceof PrivateMessageHeader ?
		((PrivateMessageHeader) h).getTransportId() : null;
```

Add getter:

```java
@Nullable
TransportId getTransportId() {
	return transportId;
}
```

In `ConversationItemViewHolder`, add:

```java
protected final ImageView messageTransport;
```

Initialize with `v.findViewById(R.id.messageTransport)` and bind:

```java
TransportId t = item.getTransportId();
if (MessageTransportUi.isKnown(t)) {
	messageTransport.setVisibility(VISIBLE);
	messageTransport.setImageResource(MessageTransportUi.getIcon(t));
	messageTransport.setContentDescription(itemView.getContext().getString(
			MessageTransportUi.getContentDescription(t, item.isIncoming())));
} else {
	messageTransport.setVisibility(GONE);
	messageTransport.setContentDescription(null);
}
```

- [ ] **Step 7: Run Android tests**

Run:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :briar-android:testOfficialDebugUnitTest --tests 'org.briarproject.briar.android.conversation.*Transport*Test' --console=plain
```

Expected: `BUILD SUCCESSFUL`.

## Task 5: End-to-End Verification

**Files:**
- All modified files from Tasks 1-4.

- [ ] **Step 1: Run focused core tests**

Run:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :bramble-core:test --tests 'org.briarproject.bramble.sync.*SessionTest' :briar-core:test --tests 'org.briarproject.briar.messaging.MessagingManagerIntegrationTest' --console=plain
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 2: Run focused Android tests**

Run:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :briar-android:testOfficialDebugUnitTest --tests 'org.briarproject.briar.android.conversation.*Transport*Test' --console=plain
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Run whitespace check**

Run:

```bash
git diff --check
```

Expected: no output.

- [ ] **Step 4: Build debug APK**

Run:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :briar-android:assembleOfficialDebug --console=plain
```

Expected: `BUILD SUCCESSFUL`; APK at `briar-android/build/outputs/apk/official/debug/briar-android-official-debug.apk`.

- [ ] **Step 5: Review final diff**

Run:

```bash
git status --short --branch
git diff -- bramble-api bramble-core briar-api briar-core briar-android docs/superpowers/specs/2026-07-08-message-transport-icons-design.md docs/superpowers/plans/2026-07-08-message-transport-icons.md
```

Expected: only intended message-transport-icon files plus pre-existing unrelated local changes are present. Do not revert unrelated local changes.

## Self-Review Notes

- Spec coverage: persistence, sync capture, conversation header exposure, Android icon rendering, unknown metadata hiding, and tests are covered.
- Scope intentionally excludes file-transfer rows and ACK/delivery transport.
- Metadata is written only when absent to preserve first observed incoming/outgoing transport.
