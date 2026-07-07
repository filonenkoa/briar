# File Transfer Cancel Reject Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add explicit Stop/Cancel/Reject behavior for generic file transfers while keeping visible terminal chat rows and deleting useless payload chunks.

**Architecture:** Extend the file-transfer protocol with a control message that carries a terminal state for a `fileId`. Store terminal state on the header metadata, have progress polling surface terminal states, reject future chunks for terminal transfers, and wire Android file rows to send cancellation/rejection actions.

**Tech Stack:** Java, Android XML layouts, Briar `BdfList`/metadata validation, existing JUnit/JMock tests, Gradle Android build.

---

## File Map

- Modify `briar-api/src/main/java/org/briarproject/briar/api/filetransfer/FileTransferConstants.java`: add control message type, control state constants, and metadata key for transfer state.
- Modify `briar-api/src/main/java/org/briarproject/briar/api/filetransfer/FileTransferProgress.java`: add terminal progress states.
- Modify `briar-api/src/main/java/org/briarproject/briar/api/filetransfer/FileTransferManager.java`: add sender cancel and receiver reject methods.
- Modify `briar-core/src/main/java/org/briarproject/briar/filetransfer/FileTransferValidator.java`: validate new control messages.
- Modify `briar-core/src/main/java/org/briarproject/briar/filetransfer/FileTransferManagerImpl.java`: create/send/process control messages, store terminal state, cleanup payload files, ignore future chunks, return terminal progress states.
- Modify `briar-core/src/test/java/org/briarproject/briar/filetransfer/FileTransferValidatorTest.java`: cover valid and invalid control messages.
- Modify `briar-core/src/test/java/org/briarproject/briar/filetransfer/FileTransferDeletionTest.java`: cover cleanup and ignored chunks for terminal transfers.
- Modify `briar-core/src/test/java/org/briarproject/briar/filetransfer/FileTransferStorageTest.java`: cover progress terminal states and control-message processing.
- Modify `briar-android/src/main/java/org/briarproject/briar/android/conversation/ConversationViewModel.java`: expose cancel/reject methods and stop polling on terminal states.
- Modify `briar-android/src/main/java/org/briarproject/briar/android/conversation/ConversationActivity.java`: handle stop action and terminal tap toast.
- Modify `briar-android/src/main/java/org/briarproject/briar/android/conversation/ConversationListener.java`: add stop callback.
- Modify `briar-android/src/main/java/org/briarproject/briar/android/conversation/FileTransferViewHolder.java`: show Stop action while transferring and terminal text for cancelled/rejected transfers.
- Modify `briar-android/src/main/res/layout/list_item_conversation_file_in.xml`: add incoming stop action view.
- Modify `briar-android/src/main/res/layout/list_item_conversation_file_out.xml`: add outgoing stop action view.
- Modify `briar-android/src/main/res/values/strings.xml`: add user-visible terminal and stop strings.
- Add or extend Android unit tests under `briar-android/src/test/java/org/briarproject/briar/android/conversation/` if the current test setup can instantiate the view holder without heavy activity dependencies.

## Task 1: Add Protocol Constants And Progress States

**Files:**
- Modify: `briar-api/src/main/java/org/briarproject/briar/api/filetransfer/FileTransferConstants.java`
- Modify: `briar-api/src/main/java/org/briarproject/briar/api/filetransfer/FileTransferProgress.java`
- Modify: `briar-api/src/main/java/org/briarproject/briar/api/filetransfer/FileTransferManager.java`

- [ ] **Step 1: Add constants**

In `FileTransferConstants`, add these constants near the existing message type and metadata keys:

```java
String MSG_TYPE_CONTROL = "control";

String MSG_KEY_TRANSFER_STATE = "transferState";
String TRANSFER_STATE_CANCELLED_BY_SENDER = "cancelled_by_sender";
String TRANSFER_STATE_REJECTED_BY_RECEIVER = "rejected_by_receiver";
```

- [ ] **Step 2: Add terminal progress states**

In `FileTransferProgress.State`, replace the enum with:

```java
public enum State {
	/** Transfer is in progress. */
	TRANSFERRING,
	/** All chunks have been delivered/received. */
	COMPLETE,
	/** Sender cancelled the transfer before completion. */
	CANCELLED,
	/** Receiver rejected the transfer before completion. */
	REJECTED,
	/** An error occurred. */
	ERROR
}
```

- [ ] **Step 3: Add manager methods**

In `FileTransferManager`, add methods after `getProgress()`:

```java
/**
 * Cancels an outgoing transfer, records a terminal state, notifies the peer,
 * and removes local payload files for the transfer.
 */
void cancelFileTransfer(FileTransferHeader h) throws DbException;

/**
 * Rejects an incoming transfer, records a terminal state, notifies the peer,
 * and removes local payload files for the transfer.
 */
void rejectFileTransfer(FileTransferHeader h) throws DbException;
```

- [ ] **Step 4: Run API/core compile**

Run:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :briar-api:compileJava :briar-core:compileJava --console=plain
```

Expected: compile fails because core classes have not implemented the new interface methods and constants yet.

- [ ] **Step 5: Commit after Task 2 instead of here**

Do not commit yet. Task 1 intentionally creates compile failures that Task 2 resolves.

## Task 2: Validate Control Messages

**Files:**
- Modify: `briar-core/src/main/java/org/briarproject/briar/filetransfer/FileTransferValidator.java`
- Modify: `briar-core/src/test/java/org/briarproject/briar/filetransfer/FileTransferValidatorTest.java`

- [ ] **Step 1: Write failing validator tests**

In `FileTransferValidatorTest`, add imports for the new constants and add these tests. Use the existing helper methods in the file for creating messages and validating bodies; if helper names differ, adapt only the invocation names while preserving these bodies and assertions.

```java
@Test
public void testAcceptsCancelControlMessage() throws Exception {
	byte[] fileId = getRandomId();
	BdfList body = BdfList.of(MSG_TYPE_CONTROL, fileId,
			TRANSFER_STATE_CANCELLED_BY_SENDER);

	BdfDictionary meta = validate(body);

	assertEquals(MSG_TYPE_CONTROL, meta.getString(MSG_KEY_MSG_TYPE));
	assertArrayEquals(fileId, meta.getRaw(MSG_KEY_FILE_ID));
	assertEquals(TRANSFER_STATE_CANCELLED_BY_SENDER,
			meta.getString(MSG_KEY_TRANSFER_STATE));
	assertFalse(meta.getBoolean(MSG_KEY_LOCAL));
}

@Test
public void testAcceptsRejectControlMessage() throws Exception {
	byte[] fileId = getRandomId();
	BdfList body = BdfList.of(MSG_TYPE_CONTROL, fileId,
			TRANSFER_STATE_REJECTED_BY_RECEIVER);

	BdfDictionary meta = validate(body);

	assertEquals(MSG_TYPE_CONTROL, meta.getString(MSG_KEY_MSG_TYPE));
	assertArrayEquals(fileId, meta.getRaw(MSG_KEY_FILE_ID));
	assertEquals(TRANSFER_STATE_REJECTED_BY_RECEIVER,
			meta.getString(MSG_KEY_TRANSFER_STATE));
	assertFalse(meta.getBoolean(MSG_KEY_LOCAL));
}

@Test(expected = InvalidMessageException.class)
public void testRejectsUnknownControlState() throws Exception {
	validate(BdfList.of(MSG_TYPE_CONTROL, getRandomId(), "paused"));
}
```

- [ ] **Step 2: Run validator tests to verify failure**

Run:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :briar-core:test --tests 'org.briarproject.briar.filetransfer.FileTransferValidatorTest' --console=plain
```

Expected: FAIL because `MSG_TYPE_CONTROL` is not accepted by the validator.

- [ ] **Step 3: Implement validator control path**

In `FileTransferValidator`, add static imports for:

```java
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_KEY_TRANSFER_STATE;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.MSG_TYPE_CONTROL;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.TRANSFER_STATE_CANCELLED_BY_SENDER;
import static org.briarproject.briar.api.filetransfer.FileTransferConstants.TRANSFER_STATE_REJECTED_BY_RECEIVER;
```

Update `validateMessage()` to include the control type:

```java
if (MSG_TYPE_HEADER.equals(messageType)) {
	meta = validateHeader(m, body);
} else if (MSG_TYPE_CHUNK.equals(messageType)) {
	meta = validateChunk(m, body);
} else if (MSG_TYPE_CONTROL.equals(messageType)) {
	meta = validateControl(m, body);
} else {
	throw new InvalidMessageException("Unknown message type");
}
```

Add this method after `validateChunk()`:

```java
private BdfDictionary validateControl(Message m, BdfList body)
		throws FormatException, InvalidMessageException {
	// type(String), fileId(byte[]), transferState(String)
	checkSize(body, 3);
	byte[] fileId = body.getRaw(1);
	checkLength(fileId, UniqueId.LENGTH);
	String state = body.getString(2);
	if (!TRANSFER_STATE_CANCELLED_BY_SENDER.equals(state) &&
			!TRANSFER_STATE_REJECTED_BY_RECEIVER.equals(state)) {
		throw new InvalidMessageException("Unknown transfer state");
	}
	BdfDictionary meta = new BdfDictionary();
	meta.put(MSG_KEY_MSG_TYPE, MSG_TYPE_CONTROL);
	meta.put(MSG_KEY_FILE_ID, fileId);
	meta.put(MSG_KEY_TRANSFER_STATE, state);
	meta.put(MSG_KEY_LOCAL, false);
	meta.put(MSG_KEY_TIMESTAMP, m.getTimestamp());
	return meta;
}
```

- [ ] **Step 4: Run validator tests**

Run:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :briar-core:test --tests 'org.briarproject.briar.filetransfer.FileTransferValidatorTest' --console=plain
```

Expected: PASS.

- [ ] **Step 5: Commit**

Run:

```bash
git add briar-api/src/main/java/org/briarproject/briar/api/filetransfer/FileTransferConstants.java briar-api/src/main/java/org/briarproject/briar/api/filetransfer/FileTransferProgress.java briar-api/src/main/java/org/briarproject/briar/api/filetransfer/FileTransferManager.java briar-core/src/main/java/org/briarproject/briar/filetransfer/FileTransferValidator.java briar-core/src/test/java/org/briarproject/briar/filetransfer/FileTransferValidatorTest.java
git commit -m "feat: validate file transfer controls"
```

## Task 3: Implement Core Terminal State And Cleanup

**Files:**
- Modify: `briar-core/src/main/java/org/briarproject/briar/filetransfer/FileTransferManagerImpl.java`
- Modify: `briar-core/src/test/java/org/briarproject/briar/filetransfer/FileTransferStorageTest.java`
- Modify: `briar-core/src/test/java/org/briarproject/briar/filetransfer/FileTransferDeletionTest.java`

- [ ] **Step 1: Write failing tests for terminal progress**

In `FileTransferStorageTest`, add tests that call existing private progress helpers by reflection, following the file's existing `getOutgoingProgress()` style. Add one test for cancelled sender state and one for rejected receiver state. Use header metadata with `MSG_KEY_TRANSFER_STATE` set on the header message.

```java
@Test
public void testOutgoingProgressIsCancelledWhenHeaderCancelled()
		throws Exception {
	Transaction txn = new Transaction(null, true);
	Contact contact = getContact();
	Group group = getGroup(CLIENT_ID, MAJOR_VERSION);
	UniqueId fileId = new UniqueId(getRandomId());
	MessageId headerId = new MessageId(getRandomId());
	FileTransferHeader header = new FileTransferHeader(headerId, group.getId(),
			123L, true, true, false, false, NO_AUTO_DELETE_TIMER, fileId,
			"large.bin", "application/octet-stream", CHUNK_SIZE * 2L, 2);
	BdfDictionary meta = headerMetadata(fileId, true);
	meta.put(MSG_KEY_TRANSFER_STATE, TRANSFER_STATE_CANCELLED_BY_SENDER);

	expectContactGroup(txn, contact, group);
	context.checking(new Expectations() {{
		oneOf(clientHelper).getMessageMetadataAsDictionary(txn, headerId);
		will(returnValue(meta));
	}});

	FileTransferProgress p = getOutgoingProgress(txn, header);

	assertEquals(FileTransferProgress.State.CANCELLED, p.getState());
	assertEquals(0, p.getTransferred());
}

@Test
public void testIncomingProgressIsRejectedWhenHeaderRejected()
		throws Exception {
	Transaction txn = new Transaction(null, true);
	Group group = getGroup(CLIENT_ID, MAJOR_VERSION);
	UniqueId fileId = new UniqueId(getRandomId());
	MessageId headerId = new MessageId(getRandomId());
	FileTransferHeader header = new FileTransferHeader(headerId, group.getId(),
			123L, false, false, false, false, NO_AUTO_DELETE_TIMER, fileId,
			"large.bin", "application/octet-stream", CHUNK_SIZE * 2L, 2);
	BdfDictionary meta = headerMetadata(fileId, false);
	meta.put(MSG_KEY_TRANSFER_STATE, TRANSFER_STATE_REJECTED_BY_RECEIVER);
	Map<MessageId, BdfDictionary> headers = new HashMap<>();
	headers.put(headerId, meta);

	context.checking(new Expectations() {{
		oneOf(clientHelper).getMessageMetadataAsDictionary(txn, group.getId(),
				with(any(BdfDictionary.class)));
		will(returnValue(headers));
	}});

	FileTransferProgress p = getIncomingProgress(txn, header);

	assertEquals(FileTransferProgress.State.REJECTED, p.getState());
	assertEquals(0, p.getTransferred());
}
```

- [ ] **Step 2: Run focused storage tests to verify failure**

Run:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :briar-core:test --tests 'org.briarproject.briar.filetransfer.FileTransferStorageTest' --console=plain
```

Expected: FAIL because progress ignores `MSG_KEY_TRANSFER_STATE`.

- [ ] **Step 3: Implement metadata helpers**

In `FileTransferManagerImpl`, add imports for the new constants and add helpers near `setChunksReceived()`:

```java
private void setTransferState(Transaction txn, MessageId headerId,
		String transferState) throws DbException, FormatException {
	BdfDictionary merge = BdfDictionary.of(new BdfEntry(
			MSG_KEY_TRANSFER_STATE, transferState));
	clientHelper.mergeMessageMetadata(txn, headerId, merge);
}

@Nullable
private State getTerminalProgressState(BdfDictionary meta)
		throws FormatException {
	String transferState = meta.getOptionalString(MSG_KEY_TRANSFER_STATE);
	if (TRANSFER_STATE_CANCELLED_BY_SENDER.equals(transferState)) {
		return State.CANCELLED;
	}
	if (TRANSFER_STATE_REJECTED_BY_RECEIVER.equals(transferState)) {
		return State.REJECTED;
	}
	return null;
}

private boolean isTerminal(BdfDictionary meta) throws FormatException {
	return getTerminalProgressState(meta) != null;
}
```

- [ ] **Step 4: Use terminal states in progress methods**

At the start of `getOutgoingProgress()`, after `fileId`, fetch the header metadata and return terminal progress if present:

```java
try {
	BdfDictionary headerMeta =
			clientHelper.getMessageMetadataAsDictionary(txn, h.getId());
	State terminal = getTerminalProgressState(headerMeta);
	if (terminal != null) return new FileTransferProgress(terminal, 0,
			h.getFileSize());
} catch (FormatException e) {
	throw new DbException(e);
}
```

In `getIncomingProgress()`, inside the loop over `headers.values()`, check for terminal state before reading chunks:

```java
for (BdfDictionary hd : headers.values()) {
	State terminal = getTerminalProgressState(hd);
	if (terminal != null) return new FileTransferProgress(terminal, 0,
			h.getFileSize());
	received = hd.getInt(MSG_KEY_CHUNKS_RECEIVED);
}
```

- [ ] **Step 5: Add control-message send and local terminal methods**

Implement the interface methods in `FileTransferManagerImpl`:

```java
@Override
public void cancelFileTransfer(FileTransferHeader h) throws DbException {
	if (!h.isLocal()) throw new IllegalArgumentException();
	setTerminalStateAndSendControl(h, TRANSFER_STATE_CANCELLED_BY_SENDER);
}

@Override
public void rejectFileTransfer(FileTransferHeader h) throws DbException {
	if (h.isLocal()) throw new IllegalArgumentException();
	setTerminalStateAndSendControl(h, TRANSFER_STATE_REJECTED_BY_RECEIVER);
}

private void setTerminalStateAndSendControl(FileTransferHeader h,
		String transferState) throws DbException {
	db.transaction(false, txn -> {
		try {
			setTransferState(txn, h.getId(), transferState);
			storeControlMessage(txn, h, transferState);
			invalidateOutgoingProgressCache(Collections.singleton(h.getFileId()));
			scheduleDeleteFileDir(txn, h.getFileId());
		} catch (FormatException e) {
			throw new DbException(e);
		}
	});
}

private void storeControlMessage(Transaction txn, FileTransferHeader h,
		String transferState) throws DbException, FormatException {
	BdfList body = BdfList.of(MSG_TYPE_CONTROL, h.getFileId().getBytes(),
			transferState);
	long timestamp = clockMillis();
	GroupId groupId = h.getGroupId();
	Message m = clientHelper.createMessage(groupId, timestamp, body);
	clientHelper.addLocalMessage(txn, m, new BdfDictionary(), true, false);
}
```

Add `import java.util.Collections;` if not already present.

- [ ] **Step 6: Process incoming control messages**

In `incomingMessage()`, add the new branch:

```java
} else if (MSG_TYPE_CONTROL.equals(messageType)) {
	incomingControl(txn, m, metaDict);
```

Add this method near `incomingHeader()`:

```java
private void incomingControl(Transaction txn, Message m,
		BdfDictionary metaDict) throws DbException, FormatException {
	GroupId groupId = m.getGroupId();
	UniqueId fileId = new UniqueId(metaDict.getRaw(MSG_KEY_FILE_ID));
	String transferState = metaDict.getString(MSG_KEY_TRANSFER_STATE);
	BdfDictionary query = BdfDictionary.of(
			new BdfEntry(MSG_KEY_FILE_ID, fileId.getBytes()),
			new BdfEntry(MSG_KEY_MSG_TYPE, MSG_TYPE_HEADER));
	Map<MessageId, BdfDictionary> headers =
			clientHelper.getMessageMetadataAsDictionary(txn, groupId, query);
	for (MessageId headerId : headers.keySet()) {
		setTransferState(txn, headerId, transferState);
	}
	invalidateOutgoingProgressCache(Collections.singleton(fileId));
	scheduleDeleteFileDir(txn, fileId);
}
```

- [ ] **Step 7: Ignore future chunks for terminal files**

In `incomingChunk()`, when iterating headers, if a matching header has terminal metadata, schedule deletion and return before writing payload:

```java
if (isTerminal(h)) {
	scheduleDeleteFileDir(txn, fileId);
	return;
}
```

Place this immediately after validating that `chunkTotal == total && chunkIndex < total` and before reading `received`.

- [ ] **Step 8: Run core file-transfer tests**

Run:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :briar-core:test --tests '*FileTransfer*' --console=plain
```

Expected: PASS.

- [ ] **Step 9: Commit**

Run:

```bash
git add briar-core/src/main/java/org/briarproject/briar/filetransfer/FileTransferManagerImpl.java briar-core/src/test/java/org/briarproject/briar/filetransfer/FileTransferStorageTest.java briar-core/src/test/java/org/briarproject/briar/filetransfer/FileTransferDeletionTest.java
git commit -m "feat: add file transfer terminal states"
```

## Task 4: Wire ViewModel And Activity Actions

**Files:**
- Modify: `briar-android/src/main/java/org/briarproject/briar/android/conversation/ConversationViewModel.java`
- Modify: `briar-android/src/main/java/org/briarproject/briar/android/conversation/ConversationActivity.java`
- Modify: `briar-android/src/main/java/org/briarproject/briar/android/conversation/ConversationListener.java`
- Modify: `briar-android/src/main/res/values/strings.xml`

- [ ] **Step 1: Add strings**

In `strings.xml`, add near existing file-transfer strings:

```xml
<string name="file_transfer_stop">Stop</string>
<string name="file_transfer_stopped">File transfer stopped</string>
<string name="file_transfer_cancelled">Cancelled</string>
<string name="file_transfer_cancelled_by_sender">Cancelled by sender</string>
<string name="file_transfer_rejected">Rejected</string>
<string name="file_transfer_rejected_by_receiver">Rejected by receiver</string>
```

- [ ] **Step 2: Update progress polling terminal states**

In `ConversationViewModel.startFileProgressPolling()`, replace the terminal check with:

```java
if (progress.getState() == FileTransferProgress.State.COMPLETE ||
		progress.getState() == FileTransferProgress.State.CANCELLED ||
		progress.getState() == FileTransferProgress.State.REJECTED ||
		progress.getState() == FileTransferProgress.State.ERROR) {
	fileProgress.remove(id);
	fileProgressPollers.remove(id);
} else {
	long delay = h.getFileSize() > 1024L * 1024 * 1024 ? 2000L : 1000L;
	fileHandler.postDelayed(this, delay);
}
```

- [ ] **Step 3: Add ViewModel stop method**

Add this method to `ConversationViewModel` near `getFileProgress()`:

```java
void stopFileTransfer(FileTransferHeader h) {
	runOnDbThread(() -> {
		try {
			if (h.isLocal()) fileTransferManager.cancelFileTransfer(h);
			else fileTransferManager.rejectFileTransfer(h);
		} catch (DbException e) {
			handleException(e);
		}
	});
}
```

- [ ] **Step 4: Add listener callback**

In `ConversationListener`, add:

```java
void onFileStopClicked(ConversationFileItem item);
```

- [ ] **Step 5: Implement Activity callback and terminal tap behavior**

In `ConversationActivity`, add:

```java
@Override
public void onFileStopClicked(ConversationFileItem item) {
	viewModel.stopFileTransfer(item.getHeader());
}
```

Update `onFileClicked()` to handle terminal states:

```java
FileTransferProgress p = item.getProgress().getValue();
if (p == null) return;
FileTransferProgress.State state = p.getState();
if (state == FileTransferProgress.State.CANCELLED ||
		state == FileTransferProgress.State.REJECTED) {
	Toast.makeText(this, R.string.file_transfer_stopped, LENGTH_SHORT).show();
	return;
}
if (state != FileTransferProgress.State.COMPLETE) return;
openFile(item.getHeader());
```

- [ ] **Step 6: Run Android compile**

Run:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :briar-android:compileOfficialDebugJavaWithJavac --console=plain
```

Expected: compile may fail until Task 5 adds view-holder callback usage; fix only direct signature/import errors in these files before continuing.

- [ ] **Step 7: Commit after Task 5 instead of here**

Do not commit yet if compile depends on Task 5 UI changes.

## Task 5: Add Stop Action And Terminal Row Rendering

**Files:**
- Modify: `briar-android/src/main/res/layout/list_item_conversation_file_in.xml`
- Modify: `briar-android/src/main/res/layout/list_item_conversation_file_out.xml`
- Modify: `briar-android/src/main/java/org/briarproject/briar/android/conversation/FileTransferViewHolder.java`

- [ ] **Step 1: Add stop action to layouts**

In both file row layouts, add a `TextView` between `progressText` and `statusLayout`:

```xml
<TextView
	android:id="@+id/stopTransfer"
	style="@style/TextMessage.Timestamp"
	android:layout_width="wrap_content"
	android:layout_height="wrap_content"
	android:layout_marginTop="6dp"
	android:text="@string/file_transfer_stop"
	android:textStyle="bold"
	app:layout_constraintTop_toBottomOf="@+id/progressText"
	app:layout_constraintStart_toStartOf="parent" />
```

For `list_item_conversation_file_out.xml`, set the text color to match outgoing timestamp text:

```xml
android:textColor="@color/private_message_date_inverse"
```

Change `statusLayout` top constraint in both layouts from `progressText` to `stopTransfer`:

```xml
app:layout_constraintTop_toBottomOf="@+id/stopTransfer"
```

- [ ] **Step 2: Update view holder fields**

In `FileTransferViewHolder`, add:

```java
private final TextView stopTransfer;
```

In the constructor:

```java
stopTransfer = v.findViewById(R.id.stopTransfer);
```

- [ ] **Step 3: Bind Stop click and terminal states**

In `bind()`, after `itemView.setOnClickListener(...)`, add:

```java
stopTransfer.setOnClickListener(view -> listener.onFileStopClicked(item));
```

In `bindProgress()`, update state branches:

```java
if (state == State.COMPLETE) {
	progressBar.setVisibility(GONE);
	stopTransfer.setVisibility(GONE);
	if (status != null) status.setVisibility(VISIBLE);
	progressText.setText(R.string.file_transfer_tap_to_open);
	if (isImage(item)) {
		imagePreview.setVisibility(VISIBLE);
		imagePreview.setTag(item.getKey());
		listener.onFilePreviewRequested(item, imagePreview);
	} else {
		imagePreview.setVisibility(GONE);
		imagePreview.setTag(null);
	}
} else if (state == State.CANCELLED) {
	progressBar.setVisibility(GONE);
	stopTransfer.setVisibility(GONE);
	if (status != null) status.setVisibility(INVISIBLE);
	imagePreview.setVisibility(GONE);
	imagePreview.setTag(null);
	progressText.setText(item.getHeader().isLocal() ?
			R.string.file_transfer_cancelled :
			R.string.file_transfer_cancelled_by_sender);
} else if (state == State.REJECTED) {
	progressBar.setVisibility(GONE);
	stopTransfer.setVisibility(GONE);
	if (status != null) status.setVisibility(INVISIBLE);
	imagePreview.setVisibility(GONE);
	imagePreview.setTag(null);
	progressText.setText(item.getHeader().isLocal() ?
			R.string.file_transfer_rejected_by_receiver :
			R.string.file_transfer_rejected);
} else if (state == State.ERROR) {
	progressBar.setVisibility(GONE);
	stopTransfer.setVisibility(GONE);
	if (status != null) status.setVisibility(INVISIBLE);
	progressText.setText(R.string.file_transfer_error);
	imagePreview.setVisibility(GONE);
	imagePreview.setTag(null);
} else {
	progressBar.setVisibility(VISIBLE);
	stopTransfer.setVisibility(VISIBLE);
	if (status != null) status.setVisibility(INVISIBLE);
	progressBar.setProgress(pct);
	imagePreview.setVisibility(GONE);
	imagePreview.setTag(null);
	progressText.setText(itemView.getContext().getString(
			R.string.file_transfer_progress, pct,
			formatFileSize(itemView.getContext(), p.getTransferred()),
			formatFileSize(itemView.getContext(), p.getTotal())));
}
```

- [ ] **Step 4: Run Android build**

Run:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :briar-android:assembleOfficialDebug --console=plain
```

Expected: PASS.

- [ ] **Step 5: Commit Tasks 4 and 5**

Run:

```bash
git add briar-android/src/main/java/org/briarproject/briar/android/conversation/ConversationViewModel.java briar-android/src/main/java/org/briarproject/briar/android/conversation/ConversationActivity.java briar-android/src/main/java/org/briarproject/briar/android/conversation/ConversationListener.java briar-android/src/main/java/org/briarproject/briar/android/conversation/FileTransferViewHolder.java briar-android/src/main/res/layout/list_item_conversation_file_in.xml briar-android/src/main/res/layout/list_item_conversation_file_out.xml briar-android/src/main/res/values/strings.xml
git commit -m "feat: stop file transfers from chat"
```

## Task 6: Full Verification And Push

**Files:**
- No code files unless verification reveals a defect.

- [ ] **Step 1: Run core file-transfer tests**

Run:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :briar-core:test --tests '*FileTransfer*' --console=plain
```

Expected: PASS.

- [ ] **Step 2: Run focused Android unit test**

Run:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :briar-android:testOfficialDebugUnitTest --tests 'org.briarproject.briar.android.view.CompositeSendButtonTest' --console=plain
```

Expected: PASS.

- [ ] **Step 3: Build debug APK**

Run:

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17 && ./gradlew :briar-android:assembleOfficialDebug --console=plain
```

Expected: PASS and APK at `briar-android/build/outputs/apk/official/debug/briar-android-official-debug.apk`.

- [ ] **Step 4: Check whitespace and status**

Run:

```bash
git diff --check
git status --short --branch
```

Expected: `git diff --check` prints no output. Status shows a clean worktree on `filonenkoa/no_relogin` ahead of origin by the implementation commits.

- [ ] **Step 5: Push**

Run:

```bash
git push
```

Expected: branch `filonenkoa/no_relogin` updates on `github.com:filonenkoa/briar.git`.

## Self-Review

- Spec coverage: terminal states, visible rows, sender cancel, receiver reject, cleanup, ignored future chunks, non-openable terminal rows, tests, and verification all map to tasks above.
- Placeholder scan: the plan contains concrete paths, constants, method signatures, commands, and expected outcomes.
- Type consistency: constants use `MSG_KEY_TRANSFER_STATE`, `TRANSFER_STATE_CANCELLED_BY_SENDER`, and `TRANSFER_STATE_REJECTED_BY_RECEIVER` consistently across API, validator, manager, and UI tasks.
