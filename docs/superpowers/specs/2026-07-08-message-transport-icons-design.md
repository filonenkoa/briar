# Message Transport Icons Design

## Goal

Show which transport moved each private chat text message:

- Incoming messages show the transport the message was received through.
- Outgoing messages show the first transport used to send the message body.
- Messages with unknown transport metadata show no per-message transport icon.

This complements the existing chat-toolbar transport indicators, which show current connection state rather than historical message-transfer state.

## Semantics

Each sync session runs over one `TransportId`, but a message can be offered, requested, retransmitted, and acknowledged across different sessions. Therefore the icon must not mean "this message only ever used this transport".

The icon means:

- `Received via`: for non-local messages, the transport of the incoming sync session that delivered the `Message` record.
- `First sent via`: for local messages, the first transport used by an outgoing sync session to write the message body.

If a message is sent again later over another transport, the stored first-sent transport is unchanged.

## Persistence

Store the transfer transport as message metadata keyed by `MessageId`.

Metadata keys:

- Incoming received-via transport ID.
- Outgoing first-sent-via transport ID.

Existing messages will not have this metadata and should render without an icon. No migration is needed because the absence of metadata is valid.

## Sync Layer

Incoming path:

- `IncomingSession` must know the session `TransportId`.
- When it receives a `Message`, pass the transport ID down to the database receive path or merge the metadata immediately after accepting the message.
- Record the received-via transport only for messages newly accepted from the remote contact.

Outgoing path:

- `SimplexOutgoingSession` and `DuplexOutgoingSession` already know their `TransportId`.
- When `generateBatch()` or `generateRequestedBatch()` selects messages to write, record the first-sent transport for each selected visible local message that does not already have one.
- Do not overwrite an existing first-sent transport during retransmission.

## Conversation API

Expose an optional per-message transport ID through private conversation message headers.

The Android conversation UI should not read raw Bramble metadata directly. The Briar conversation/messaging layer should translate metadata into a nullable `TransportId` on `PrivateMessageHeader` or a small value object if that fits existing patterns better.

## Android UI

Reuse the current transport icon mapping:

- Tor/Internet: `TorConstants.ID`
- Wi-Fi/LAN: `LanTcpConstants.ID`
- Bluetooth: `BluetoothConstants.ID`

Render a small icon near the existing timestamp/status area for private text message rows.

Accessibility labels:

- Incoming: `Received via Internet`, `Received via Wi-Fi`, or `Received via Bluetooth`.
- Outgoing: `Sent via Internet`, `Sent via Wi-Fi`, or `Sent via Bluetooth`.

If the metadata is absent or the transport ID is not one of the known transports, hide the icon.

## Testing

Core tests:

- Incoming sync records the received-via transport for a newly received message.
- Outgoing sync records first-sent transport when a message body is selected for sending.
- Retransmission over another transport does not overwrite the first-sent transport.
- Existing messages without metadata still load successfully.

Android/UI tests:

- `PrivateMessageHeader` transport metadata maps to the correct icon state.
- Incoming and outgoing content descriptions use the right wording.
- Unknown or missing metadata hides the icon.

## Out of Scope

- Showing multiple transports per message.
- Showing ACK/delivery transport.
- Changing toolbar current-transport behavior.
- File-transfer message transport icons, unless added in a later pass.
