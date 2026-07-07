# File Transfer Cancel And Reject Design

## Goal

Allow either side of a generic file transfer to stop an in-progress transfer
without deleting the visible chat row. Stopping must also clean up useless file
payload data from disk so partial chunks are not retained.

## Current State

Generic file transfer is represented by one header message and many chunk
messages in the file-transfer client. The UI polls progress from the header and
renders a progress bar. Existing message deletion can remove local metadata and
chunk files, but there is no protocol-level cancel or reject state. Because of
that, one peer cannot explicitly tell the other peer to stop sending or stop
expecting chunks.

## User-Facing Behavior

In-progress file rows show a Stop action.

When the sender presses Stop:

- Sender row remains visible and shows `Cancelled`.
- Receiver row remains visible and shows `Cancelled by sender`.
- Sender stops the transfer and deletes local stored chunks/assembled payload
  for that file.
- Receiver deletes any already received chunks for that file.
- Receiver ignores any future chunks for that file.

When the receiver presses Stop:

- Receiver row remains visible and shows `Rejected`.
- Sender row remains visible and shows `Rejected by receiver`.
- Receiver immediately deletes already received chunks for that file.
- Sender stops the transfer and deletes local stored chunks/assembled payload
  for that file.
- Both sides ignore any future chunks for that file.

Completed transfers keep the existing `Tap to open` behavior. Cancelled or
rejected transfers are not openable. Tapping a terminal transfer row shows a
short `File transfer stopped` toast.

## Protocol Model

Add a file-transfer control message type tied to a `fileId`. The control
message carries one terminal state:

- `cancelled_by_sender`
- `rejected_by_receiver`

The receiver of a control message records the terminal state in local metadata,
cleans up payload files for the `fileId`, and ignores future chunk messages for
that `fileId`.

The sender of a control message also records the corresponding local terminal
state and cleans up local payload files. This keeps both local UI state and disk
state consistent immediately, without waiting for sync round trips.

## Storage And Cleanup

Transfer state is stored in file-transfer metadata associated with the header
message. Chunk metadata and payload files remain implementation details and may
be deleted when the transfer reaches a terminal cancelled/rejected state.

Cleanup requirements:

- Deleting payload files must remove the assembled file, chunk files, and empty
  chunk directories for the `fileId`.
- Cleanup must be safe when chunks are being processed concurrently.
- Once a terminal state is committed, later chunk processing for that `fileId`
  must not write payload data to disk.
- Terminal rows must survive cleanup because the header metadata remains.

## API Changes

Extend `FileTransferProgress.State` with terminal states:

- `CANCELLED`
- `REJECTED`

Add file-transfer manager methods for user actions:

- `cancelFileTransfer(FileTransferHeader header)` for sender-side cancellation.
- `rejectFileTransfer(FileTransferHeader header)` for receiver-side rejection.

The manager methods send the control message, update local metadata, clean up
payload files, and invalidate outgoing progress caches as needed.

## UI Changes

File-transfer rows gain a Stop action while progress state is `TRANSFERRING`.
The action calls sender cancellation for local/outgoing files and receiver
rejection for incoming files.

Rows render terminal states as text instead of progress:

- Local sender cancel: `Cancelled`
- Remote sender cancel: `Cancelled by sender`
- Local receiver reject: `Rejected`
- Remote receiver reject: `Rejected by receiver`

Terminal rows hide the progress bar and any open affordance. Completed rows
continue to show `Tap to open` and preview completed generic images as before.

## Error Handling

If sending a control message fails, the local side should still move the row to
the requested terminal state and clean local payload data. The peer may continue
to send chunks until it receives the control message or sync naturally stops,
but local terminal-state checks prevent storing future chunk payloads.

If cleanup fails for a file, the error is logged. The UI still shows the
terminal state because the user's intent is recorded in metadata.

## Tests

Core tests should cover:

- Sender cancellation records terminal state and deletes local payload files.
- Receiver rejection records terminal state and deletes already received chunks.
- Control messages update peer terminal state.
- Future chunks for terminal files are ignored and not stored.
- Terminal rows remain represented by header metadata after payload cleanup.
- Completed transfers remain openable and unaffected.

Android tests should cover at least the row/controller API surface where
practical:

- In-progress file rows expose a Stop action.
- Terminal progress states render terminal text and hide open/progress UI.
