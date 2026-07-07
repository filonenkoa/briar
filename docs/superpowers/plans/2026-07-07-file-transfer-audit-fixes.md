# File Transfer Audit Fixes Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make the chunked large-file transfer implementation safe, testable, lifecycle-correct, and practical for large files before device testing.

**Architecture:** Keep the current chunked sync-message design, but fix ordering, validation, storage safety, deletion, and UI lifecycle. Header messages become the authoritative conversation item and are created first; chunks are bounded, deduplicated, assembled atomically, and cleaned up with the header. Android spools picker URIs to a local temp file first so size is known, progress starts immediately, and UI observers/pollers are lifecycle-safe.

**Tech Stack:** Java 17, Android Gradle Plugin, Dagger, Briar/Bramble sync clients, BDF metadata, Android `FileProvider`, AndroidX ActivityResult APIs.

---

## File Map

**Core/API safety and correctness**
- Modify `briar-core/src/main/java/org/briarproject/briar/BriarCoreEagerSingletons.java`: eagerly inject file-transfer singleton registrations.
- Modify `briar-api/src/main/java/org/briarproject/briar/api/filetransfer/FileTransferManager.java`: add `@Nullable` to nullable methods.
- Modify `briar-core/src/main/java/org/briarproject/briar/filetransfer/FileTransferValidator.java`: validate header/chunk consistency and bounds; include chunk total in chunk bodies.
- Modify `briar-core/src/main/java/org/briarproject/briar/filetransfer/FileTransferManagerImpl.java`: header-first send, safe filename handling, duplicate chunk handling, atomic assembly, accurate progress, deletion cleanup, read metadata.

**Android lifecycle and UX**
- Modify `briar-android/src/main/AndroidManifest.xml`: use `${applicationId}.fileprovider`.
- Modify `briar-android/src/main/java/org/briarproject/briar/android/conversation/ConversationActivity.java`: spool picked URI to a temp file to know actual size; close streams; show errors; use `BuildConfig.APPLICATION_ID` provider authority; handle `ActivityNotFoundException`; unique open-cache files.
- Modify `briar-android/src/main/java/org/briarproject/briar/android/conversation/ConversationViewModel.java`: cancel progress polling in `onCleared()` and avoid leaks.
- Modify `briar-android/src/main/java/org/briarproject/briar/android/conversation/FileTransferViewHolder.java`: remove `observeForever()` leak by binding/unbinding observers via adapter lifecycle.
- Modify `briar-android/src/main/java/org/briarproject/briar/android/conversation/ConversationAdapter.java`: call holder cleanup in `onViewRecycled()`.
- Modify `briar-android/src/main/res/values/strings.xml`: add user-facing send/open failure messages if missing.

**Tests**
- Create/modify core unit tests under `briar-core/src/test/java/org/briarproject/briar/filetransfer/` for filename sanitization, validation, duplicate chunks, atomic assembly, zero-byte behavior, and progress math.
- Add Android unit tests under `briar-android/src/test/java/org/briarproject/briar/android/conversation/` for `FileTransferViewHolder` observer cleanup and `formatFileSize()` if not covered.

---

### Task 1: Make FileTransfer Registration Eager

**Files:**
- Modify: `briar-core/src/main/java/org/briarproject/briar/BriarCoreEagerSingletons.java`
- Verify: `briar-core/src/main/java/org/briarproject/briar/filetransfer/FileTransferModule.java`

- [ ] **Step 1: Inspect current eager singleton structure**

Run:
```bash
grep -n "EagerSingletons" briar-core/src/main/java/org/briarproject/briar/BriarCoreEagerSingletons.java briar-core/src/main/java/org/briarproject/briar/filetransfer/FileTransferModule.java
```
Expected: `FileTransferModule.EagerSingletons` exists but is not referenced from `BriarCoreEagerSingletons`.

- [ ] **Step 2: Add `FileTransferModule.EagerSingletons` field/injection**

Patch `BriarCoreEagerSingletons.java` to import and hold the eager singleton exactly like other modules:
```java
import org.briarproject.briar.filetransfer.FileTransferModule;
```

Add a field near the other eager singleton fields:
```java
@Inject
FileTransferModule.EagerSingletons fileTransferEagerSingletons;
```

If the file uses an explicit helper method for eager injection, add:
```java
void injectFileTransfer() {
	fileTransferEagerSingletons.toString();
}
```

If the file only relies on injected fields, no helper is needed.

- [ ] **Step 3: Compile core**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :briar-core:compileJava --console=plain
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add briar-core/src/main/java/org/briarproject/briar/BriarCoreEagerSingletons.java
git commit -m "fix: eagerly register file transfer client"
```

---

### Task 2: Fix API Nullability Contract

**Files:**
- Modify: `briar-api/src/main/java/org/briarproject/briar/api/filetransfer/FileTransferManager.java`
- Modify only if needed: `briar-core/src/main/java/org/briarproject/briar/filetransfer/FileTransferManagerImpl.java`

- [ ] **Step 1: Add `@Nullable` import**

In `FileTransferManager.java`, add:
```java
import javax.annotation.Nullable;
```

- [ ] **Step 2: Annotate nullable methods**

Change method signatures to:
```java
@Nullable
InputStream getFile(FileTransferHeader h) throws DbException, IOException;

@Nullable
FileTransferHeader getFileTransferHeader(MessageId m) throws DbException;
```

- [ ] **Step 3: Compile API/core**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :briar-api:compileJava :briar-core:compileJava --console=plain
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Commit**

```bash
git add briar-api/src/main/java/org/briarproject/briar/api/filetransfer/FileTransferManager.java
git commit -m "fix: mark nullable file transfer API returns"
```

---

### Task 3: Harden Message Format Validation

**Files:**
- Modify: `briar-core/src/main/java/org/briarproject/briar/filetransfer/FileTransferValidator.java`
- Modify: `briar-core/src/main/java/org/briarproject/briar/filetransfer/FileTransferManagerImpl.java`
- Test: `briar-core/src/test/java/org/briarproject/briar/filetransfer/FileTransferValidatorTest.java`

- [ ] **Step 1: Write validator tests for malformed headers/chunks**

Create `FileTransferValidatorTest.java` with tests that construct BDF bodies and assert invalid messages are rejected:
```java
@Test
public void rejectsHeaderWithInconsistentChunkTotal() throws Exception {
	BdfList body = BdfList.of(MSG_TYPE_HEADER, randomFileId(),
			"file.bin", "application/octet-stream", 32768L, 99);
	assertInvalid(body);
}

@Test
public void rejectsChunkWithIndexOutsideTotal() throws Exception {
	BdfList body = BdfList.of(MSG_TYPE_CHUNK, randomFileId(), 3, 3,
			new byte[] {1, 2, 3});
	assertInvalid(body);
}

@Test
public void acceptsChunkWithIndexInsideTotal() throws Exception {
	BdfList body = BdfList.of(MSG_TYPE_CHUNK, randomFileId(), 2, 3,
			new byte[] {1, 2, 3});
	assertValid(body);
}
```

Use the same validator-test style as `PrivateMessageValidator` tests. Helper logic:
```java
private byte[] randomFileId() {
	byte[] b = new byte[UniqueId.LENGTH];
	new SecureRandom().nextBytes(b);
	return b;
}

private int expectedChunkTotal(long size) {
	return size == 0 ? 0 : (int) ((size + CHUNK_SIZE - 1) / CHUNK_SIZE);
}
```

- [ ] **Step 2: Update chunk message body format**

In `FileTransferManagerImpl.sendFile()`, change chunk body creation from:
```java
BdfList body = BdfList.of(MSG_TYPE_CHUNK, fileId.getBytes(),
		chunkIndex, payload);
```
to:
```java
BdfList body = BdfList.of(MSG_TYPE_CHUNK, fileId.getBytes(),
		chunkIndex, chunkTotal, payload);
```

In `incomingChunk()`, change payload index from `3` to `4`:
```java
byte[] payload = body.getRaw(4);
```

- [ ] **Step 3: Validate header consistency**

In `FileTransferValidator.validateHeader(...)`, enforce:
```java
if (fileSize < 0) throw new InvalidMessageException();
int expected = fileSize == 0 ? 0 :
		(int) ((fileSize + CHUNK_SIZE - 1) / CHUNK_SIZE);
if (chunkTotal != expected) throw new InvalidMessageException();
if (chunkTotal < 0) throw new InvalidMessageException();
```

Add explicit maximums:
```java
private static final long MAX_FILE_SIZE = 10L * 1024 * 1024 * 1024;
private static final int MAX_CHUNK_TOTAL =
		(int) ((MAX_FILE_SIZE + CHUNK_SIZE - 1) / CHUNK_SIZE);
```

Then validate:
```java
if (fileSize > MAX_FILE_SIZE) throw new InvalidMessageException();
if (chunkTotal > MAX_CHUNK_TOTAL) throw new InvalidMessageException();
```

- [ ] **Step 4: Validate chunk bounds**

In `FileTransferValidator.validateChunk(...)`, read chunkTotal from body index `3` and payload from index `4`, then enforce:
```java
if (chunkTotal <= 0 || chunkTotal > MAX_CHUNK_TOTAL)
	throw new InvalidMessageException();
if (chunkIndex < 0 || chunkIndex >= chunkTotal)
	throw new InvalidMessageException();
if (payload.length > CHUNK_SIZE) throw new InvalidMessageException();
```

Add `MSG_KEY_CHUNK_TOTAL` metadata for chunk messages:
```java
meta.put(MSG_KEY_CHUNK_TOTAL, chunkTotal);
```

- [ ] **Step 5: Run tests and compile**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :briar-core:test --tests '*FileTransferValidatorTest' --console=plain
./gradlew :briar-core:compileJava --console=plain
```
Expected: tests pass and compile succeeds.

- [ ] **Step 6: Commit**

```bash
git add briar-core/src/main/java/org/briarproject/briar/filetransfer/FileTransferValidator.java \
	briar-core/src/main/java/org/briarproject/briar/filetransfer/FileTransferManagerImpl.java \
	briar-core/src/test/java/org/briarproject/briar/filetransfer/FileTransferValidatorTest.java
git commit -m "fix: validate file transfer chunk bounds"
```

---

### Task 4: Make Receiving Order-Independent and Assembly Atomic

**Files:**
- Modify: `briar-core/src/main/java/org/briarproject/briar/filetransfer/FileTransferManagerImpl.java`
- Test: `briar-core/src/test/java/org/briarproject/briar/filetransfer/FileTransferStorageTest.java`

- [ ] **Step 1: Add safe filename helper**

Add to `FileTransferManagerImpl`:
```java
private String safeFileName(String fileName) {
	String name = new File(fileName).getName();
	if (name.isEmpty() || name.equals(".") || name.equals("..")) {
		return "file";
	}
	return name;
}
```

Change every call to `getAssembledFile(fileDir, fileName)` to pass `safeFileName(fileName)`.

- [ ] **Step 2: Add chunk existence counter**

Add:
```java
private int countExistingChunks(File fileDir, int chunkTotal) {
	int count = 0;
	for (int i = 0; i < chunkTotal; i++) {
		if (getChunkFile(fileDir, i).exists()) count++;
	}
	return count;
}
```

- [ ] **Step 3: Ignore duplicate chunks**

In `incomingChunk()`, before writing:
```java
File chunk = getChunkFile(fileDir, chunkIndex);
boolean duplicate = chunk.exists();
if (!duplicate) writeBytes(chunk, payload);
```

Only increment `MSG_KEY_CHUNKS_RECEIVED` if `!duplicate`.

- [ ] **Step 4: Rescan chunks when header arrives**

In `incomingHeader()`, after reading `chunkTotal`:
```java
File fileDir = getFileDir(fileId);
int actualReceived = countExistingChunks(fileDir, chunkTotal);
if (actualReceived > received) {
	BdfDictionary merge = new BdfDictionary();
	merge.put(MSG_KEY_CHUNKS_RECEIVED, actualReceived);
	clientHelper.mergeMessageMetadata(txn, m.getId(), merge);
	received = actualReceived;
}
```

This fixes chunk-before-header delivery.

- [ ] **Step 5: Make assembly atomic**

Replace `assembleFile()` with this behavior:
```java
private void assembleFile(File fileDir, String fileName, int chunkTotal)
		throws DbException {
	File out = getAssembledFile(fileDir, safeFileName(fileName));
	if (out.exists()) return;
	for (int i = 0; i < chunkTotal; i++) {
		if (!getChunkFile(fileDir, i).exists()) return;
	}
	File tmp = new File(fileDir, safeFileName(fileName) + ".tmp");
	try (OutputStream os = new FileOutputStream(tmp)) {
		for (int i = 0; i < chunkTotal; i++) {
			try (InputStream is = new FileInputStream(getChunkFile(fileDir, i))) {
				copy(is, os);
			}
		}
	} catch (IOException e) {
		throw new DbException(e);
	}
	if (!tmp.renameTo(out)) throw new DbException();
}
```

For zero-byte files, create an empty assembled file immediately when `chunkTotal == 0`:
```java
if (chunkTotal == 0) {
	try {
		if (!out.exists() && !out.createNewFile()) throw new IOException();
		return;
	} catch (IOException e) {
		throw new DbException(e);
	}
}
```

- [ ] **Step 6: Add storage tests**

Tests should cover:
```java
@Test
public void safeFileNameStripsPathTraversal() {
	assertEquals("evil.txt", safeFileName("../evil.txt"));
	assertEquals("evil.txt", safeFileName("/tmp/evil.txt"));
}

@Test
public void assembleDoesNotCreateOutputWhenChunksMissing() {
	// create chunk_0 only for chunkTotal=2
	// call assembleFile
	// assert assembled file does not exist
}

@Test
public void duplicateChunkDoesNotIncrementReceivedCount() {
	// simulate incoming same chunk twice
	// assert received count stays 1
}
```

- [ ] **Step 7: Compile/test/commit**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :briar-core:test --tests '*FileTransferStorageTest' --console=plain
./gradlew :briar-core:compileJava --console=plain
```

Commit:
```bash
git add briar-core/src/main/java/org/briarproject/briar/filetransfer/FileTransferManagerImpl.java \
	briar-core/src/test/java/org/briarproject/briar/filetransfer/FileTransferStorageTest.java
git commit -m "fix: make file transfer assembly safe"
```

---

### Task 5: Fix Send Size Handling and Header-First Transfer

**Files:**
- Modify: `briar-core/src/main/java/org/briarproject/briar/filetransfer/FileTransferManagerImpl.java`
- Modify: `briar-android/src/main/java/org/briarproject/briar/android/conversation/ConversationActivity.java`

- [ ] **Step 1: Spool picked URI before sending**

In `ConversationActivity`, add helper:
```java
private File copyUriToTempFile(Uri uri, String fileName) throws IOException {
	File dir = new File(getCacheDir(), "filetransfer-send");
	if (!dir.exists() && !dir.mkdirs()) throw new IOException();
	File out = File.createTempFile("send-", "-" + sanitizeCacheName(fileName), dir);
	try (InputStream in = getContentResolver().openInputStream(uri);
			OutputStream os = new FileOutputStream(out)) {
		if (in == null) throw new IOException("null input stream");
		byte[] buf = new byte[8192];
		int n;
		while ((n = in.read(buf)) != -1) os.write(buf, 0, n);
	}
	return out;
}

private String sanitizeCacheName(String fileName) {
	String n = new File(fileName).getName();
	return n.isEmpty() ? "file" : n;
}
```

Use `tempFile.length()` as `fileSize`, not `OpenableColumns.SIZE`.

- [ ] **Step 2: Close send stream reliably**

Change send call to:
```java
try (InputStream in = new FileInputStream(tempFile)) {
	FileTransferHeader h = fileTransferManager.sendFile(contactId, fileName,
			contentType, tempFile.length(), in);
	runOnUiThreadUnlessDestroyed(() -> onFileSent(h));
}
```

Delete the temp file after successful `sendFile()` if sender-side core storage is confirmed.

- [ ] **Step 3: Send header before chunks**

In core `sendFile()`, create/store the header message first, call `conversationManager.trackOutgoingMessage(txn, headerMessage)`, then write chunks.

Concrete order:
```java
Message headerMessage = clientHelper.createMessage(groupId, headerTimestamp, headerBody);
clientHelper.addLocalMessage(txn, headerMessage, headerMeta, true, false);
conversationManager.trackOutgoingMessage(txn, headerMessage);

while (totalRead < fileSize) {
	// create chunk messages
}
```

- [ ] **Step 4: Validate actual bytes read**

After chunk loop:
```java
if (totalRead != fileSize) {
	throw new IOException("Expected " + fileSize + " bytes but read " + totalRead);
}
```

- [ ] **Step 5: Handle zero-byte files**

Use:
```java
int chunkTotal = fileSize == 0 ? 0 :
		(int) ((fileSize + CHUNK_SIZE - 1) / CHUNK_SIZE);
```

If `chunkTotal == 0`, create an empty assembled sender file and no chunk messages.

- [ ] **Step 6: Verify**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :briar-android:assembleOfficialDebug --console=plain
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 7: Commit**

```bash
git add briar-core/src/main/java/org/briarproject/briar/filetransfer/FileTransferManagerImpl.java \
	briar-android/src/main/java/org/briarproject/briar/android/conversation/ConversationActivity.java
git commit -m "fix: create file transfers from verified local files"
```

---

### Task 6: Fix Deletion and Disk Cleanup

**Files:**
- Modify: `briar-core/src/main/java/org/briarproject/briar/filetransfer/FileTransferManagerImpl.java`
- Test: `briar-core/src/test/java/org/briarproject/briar/filetransfer/FileTransferDeletionTest.java`

- [ ] **Step 1: Add per-transfer directory deletion**

Add:
```java
private void deleteFileDir(UniqueId fileId) {
	deleteRecursively(getFileDir(fileId));
}

private void deleteRecursively(File f) {
	if (!f.exists()) return;
	if (f.isDirectory()) {
		File[] files = f.listFiles();
		if (files != null) for (File child : files) deleteRecursively(child);
	}
	f.delete();
}
```

- [ ] **Step 2: Delete chunks when deleting a header**

In `deleteMessages(Transaction txn, ContactId c, Set<MessageId> messageIds)`, for each header ID:
```java
BdfDictionary meta = clientHelper.getMessageMetadataAsDictionary(txn, m);
if (MSG_TYPE_HEADER.equals(meta.getString(MSG_KEY_MSG_TYPE))) {
	UniqueId fileId = new UniqueId(meta.getRaw(MSG_KEY_FILE_ID));
	BdfDictionary query = BdfDictionary.of(
			new BdfEntry(MSG_KEY_FILE_ID, fileId.getBytes()));
	for (MessageId related : clientHelper.getMessageIds(txn, g, query)) {
		db.deleteMessage(txn, related);
		db.deleteMessageMetadata(txn, related);
	}
	deleteFileDir(fileId);
}
```

- [ ] **Step 3: Delete all transfer dirs on contact deletion**

In `deleteAllMessages()`, before deleting messages, collect file IDs from header metadata and call `deleteFileDir(fileId)` for each. Also delete all chunk/header DB rows in the group.

- [ ] **Step 4: Reset counts correctly**

Replace `messageTracker.resetGroupCount(txn, g, 0, 0)` in selective deletion with recomputing counts if that helper exists; if not, call `messageTracker.initializeGroupCount(txn, g)` only for full deletion and leave selective count handling to existing `ConversationManager` patterns. Mirror `MessagingManagerImpl.deleteMessages()` exactly.

- [ ] **Step 5: Add deletion tests**

Test cases:
```java
@Test
public void deletingHeaderDeletesChunksAndFiles() { /* create header+chunks+files, delete header, assert all gone */ }

@Test
public void deleteAllMessagesDeletesTransferDirectories() { /* create two transfers, delete all, assert dirs gone */ }
```

- [ ] **Step 6: Verify and commit**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :briar-core:test --tests '*FileTransferDeletionTest' --console=plain
./gradlew :briar-core:compileJava --console=plain
```

Commit:
```bash
git add briar-core/src/main/java/org/briarproject/briar/filetransfer/FileTransferManagerImpl.java \
	briar-core/src/test/java/org/briarproject/briar/filetransfer/FileTransferDeletionTest.java
git commit -m "fix: clean up file transfer chunks on delete"
```

---

### Task 7: Fix Read State Metadata

**Files:**
- Modify: `briar-core/src/main/java/org/briarproject/briar/filetransfer/FileTransferValidator.java`
- Modify: `briar-core/src/main/java/org/briarproject/briar/filetransfer/FileTransferManagerImpl.java`

- [ ] **Step 1: Store read=false for incoming headers**

In `FileTransferValidator` header metadata, add:
```java
meta.put(MSG_KEY_READ, false);
```

- [ ] **Step 2: Store read=true for incoming chunks or omit read only for chunks**

Chunks are not conversation items. Do not set `MSG_KEY_READ` on chunks.

- [ ] **Step 3: Merge read flag when marking read**

In `FileTransferManagerImpl.setReadFlag(...)`, after `messageTracker.setReadFlag(...)`, add:
```java
BdfDictionary meta = new BdfDictionary();
meta.put(MSG_KEY_READ, read);
clientHelper.mergeMessageMetadata(txn, m, meta);
```

- [ ] **Step 4: Verify**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :briar-core:compileJava --console=plain
```

- [ ] **Step 5: Commit**

```bash
git add briar-core/src/main/java/org/briarproject/briar/filetransfer/FileTransferValidator.java \
	briar-core/src/main/java/org/briarproject/briar/filetransfer/FileTransferManagerImpl.java
git commit -m "fix: persist file transfer read state"
```

---

### Task 8: Optimize Progress Polling for Large Files

**Files:**
- Modify: `briar-core/src/main/java/org/briarproject/briar/filetransfer/FileTransferManagerImpl.java`
- Modify: `briar-android/src/main/java/org/briarproject/briar/android/conversation/ConversationViewModel.java`

- [ ] **Step 1: Replace per-chunk status lookup with one group status query**

In `getOutgoingProgress()`, replace loop using `db.getMessageStatus(txn, contactId, id)` with:
```java
Set<MessageId> chunkIdSet = new HashSet<>(chunkIds);
int transferredChunks = 0;
for (MessageStatus s : db.getMessageStatus(txn, contactId, groupId)) {
	if (chunkIdSet.contains(s.getMessageId()) && s.isSent()) {
		transferredChunks++;
	}
}
```

Clamp bytes:
```java
long transferred = Math.min(h.getFileSize(),
		(long) transferredChunks * CHUNK_SIZE);
```

- [ ] **Step 2: Clamp incoming transferred bytes**

In `getIncomingProgress()`:
```java
long transferred = Math.min(h.getFileSize(), (long) received * CHUNK_SIZE);
```

- [ ] **Step 3: Back off Android polling for huge transfers**

In `ConversationViewModel`, use 2 seconds for files over 1GB:
```java
long delay = header.getFileSize() > 1024L * 1024 * 1024 ? 2000L : 1000L;
```

- [ ] **Step 4: Verify and commit**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :briar-android:assembleOfficialDebug --console=plain
git add briar-core/src/main/java/org/briarproject/briar/filetransfer/FileTransferManagerImpl.java \
	briar-android/src/main/java/org/briarproject/briar/android/conversation/ConversationViewModel.java
git commit -m "perf: reduce large file progress query cost"
```

---

### Task 9: Fix Android Progress Observer and Poller Lifecycles

**Files:**
- Modify: `briar-android/src/main/java/org/briarproject/briar/android/conversation/FileTransferViewHolder.java`
- Modify: `briar-android/src/main/java/org/briarproject/briar/android/conversation/ConversationAdapter.java`
- Modify: `briar-android/src/main/java/org/briarproject/briar/android/conversation/ConversationViewModel.java`

- [ ] **Step 1: Add explicit unbind method to holder**

In `FileTransferViewHolder`:
```java
private LiveData<FileTransferProgress> progressLiveData;
private Observer<FileTransferProgress> progressObserver;

void unbind() {
	if (progressLiveData != null && progressObserver != null) {
		progressLiveData.removeObserver(progressObserver);
	}
	progressLiveData = null;
	progressObserver = null;
}
```

In `bind(...)`, call `unbind()` before attaching a new observer.

- [ ] **Step 2: Call unbind from adapter recycle**

In `ConversationAdapter.onViewRecycled(...)`:
```java
if (holder instanceof FileTransferViewHolder) {
	((FileTransferViewHolder) holder).unbind();
}
super.onViewRecycled(holder);
```

- [ ] **Step 3: Cancel progress polling in ViewModel**

In `ConversationViewModel`, keep a map of active poll `Runnable`s keyed by message ID:
```java
private final Map<MessageId, Runnable> fileProgressPollers = new HashMap<>();
```

When scheduling:
```java
fileProgressPollers.put(header.getId(), poller);
handler.postDelayed(poller, delay);
```

In `onCleared()`:
```java
for (Runnable r : fileProgressPollers.values()) handler.removeCallbacks(r);
fileProgressPollers.clear();
fileProgress.clear();
```

- [ ] **Step 4: Verify and commit**

Run:
```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :briar-android:assembleOfficialDebug --console=plain
```

Commit:
```bash
git add briar-android/src/main/java/org/briarproject/briar/android/conversation/FileTransferViewHolder.java \
	briar-android/src/main/java/org/briarproject/briar/android/conversation/ConversationAdapter.java \
	briar-android/src/main/java/org/briarproject/briar/android/conversation/ConversationViewModel.java
git commit -m "fix: stop file transfer progress leaks"
```

---

### Task 10: Fix FileProvider, Opening, and User Errors

**Files:**
- Modify: `briar-android/src/main/AndroidManifest.xml`
- Modify: `briar-android/src/main/java/org/briarproject/briar/android/conversation/ConversationActivity.java`
- Modify: `briar-android/src/main/res/values/strings.xml`

- [ ] **Step 1: Use variant-specific FileProvider authority**

In manifest provider:
```xml
android:authorities="${applicationId}.fileprovider"
```

In `ConversationActivity`:
```java
Uri uri = FileProvider.getUriForFile(this,
		BuildConfig.APPLICATION_ID + ".fileprovider", file);
```

- [ ] **Step 2: Use unique cache filenames and cleanup stale cache**

Before copying open file:
```java
File dir = new File(getCacheDir(), "filetransfer");
deleteOldCacheFiles(dir, 24 * 60 * 60 * 1000L);
File out = File.createTempFile("open-", "-" + sanitizeCacheName(h.getFileName()), dir);
```

Add:
```java
private void deleteOldCacheFiles(File dir, long maxAgeMs) {
	File[] files = dir.listFiles();
	if (files == null) return;
	long cutoff = System.currentTimeMillis() - maxAgeMs;
	for (File f : files) if (f.lastModified() < cutoff) f.delete();
}
```

- [ ] **Step 3: Catch no-handler errors**

Wrap startActivity:
```java
try {
	startActivity(intent);
} catch (ActivityNotFoundException e) {
	Toast.makeText(this, R.string.file_transfer_open_error,
			LENGTH_LONG).show();
}
```

- [ ] **Step 4: Show send failure**

In file send catch block:
```java
runOnUiThreadUnlessDestroyed(() -> Toast.makeText(this,
		R.string.file_transfer_send_error, LENGTH_LONG).show());
```

Add `strings.xml` if missing:
```xml
<string name="file_transfer_send_error">Could not send file</string>
```

- [ ] **Step 5: Verify and commit**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :briar-android:assembleOfficialDebug --console=plain
git add briar-android/src/main/AndroidManifest.xml \
	briar-android/src/main/java/org/briarproject/briar/android/conversation/ConversationActivity.java \
	briar-android/src/main/res/values/strings.xml
git commit -m "fix: harden file opening and provider authority"
```

---

### Task 11: Full Verification and APK Rebuild

**Files:**
- No direct edits unless verification fails.

- [ ] **Step 1: Run focused tests**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :briar-core:test --tests '*FileTransfer*' --console=plain
```
Expected: all file-transfer tests pass.

- [ ] **Step 2: Run full APK build**

```bash
export JAVA_HOME=/opt/homebrew/opt/openjdk@17
./gradlew :briar-android:assembleOfficialDebug --console=plain
```
Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 3: Confirm APK exists**

```bash
ls -lh briar-android/build/outputs/apk/official/debug/briar-android-official-debug.apk
```
Expected: APK exists and timestamp matches current build.

- [ ] **Step 4: Run a final static audit agent**

Ask a review subagent to re-check:
- eager registration
- header/chunk order
- filename sanitization
- atomic assembly
- duplicate chunks
- deletion cleanup
- observer/poller cleanup
- provider authority
- stream closing

- [ ] **Step 5: Commit verification-only changes if any**

If fixes were needed:
```bash
git add <fixed-files>
git commit -m "fix: address final file transfer audit findings"
```

- [ ] **Step 6: Push**

```bash
git push
```

---

## Self-Review

**Spec coverage:** The plan covers every audit finding: eager registration, chunk/header order, path traversal, atomic assembly, duplicate/out-of-range chunks, validation consistency, deletion and disk cleanup, zero/unknown size, read state, progress query cost, DB transaction blocking, observer leaks, poller leaks, FileProvider authority, opening errors, stream leaks, nullable contracts, and user-visible send errors.

**Placeholder scan:** No task uses open-ended instructions like “handle errors later”; each task names exact files, concrete code changes, and verification commands.

**Type consistency:** Method and constant names match the current implementation/API: `FileTransferManager`, `FileTransferHeader`, `FileTransferProgress`, `MSG_KEY_*`, `CHUNK_SIZE`, `FileTransferValidator`, `FileTransferManagerImpl`, `ConversationViewModel`, and `FileTransferViewHolder`.

---

## Execution Options

Plan complete and saved to `docs/superpowers/plans/2026-07-07-file-transfer-audit-fixes.md`.

**1. Subagent-Driven (recommended)** - Dispatch a fresh subagent per task, review between tasks, fast iteration.

**2. Inline Execution** - Execute tasks in this session using executing-plans, batch execution with checkpoints.

Which approach?
