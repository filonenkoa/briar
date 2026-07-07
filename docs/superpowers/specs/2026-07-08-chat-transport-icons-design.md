# Chat Transport Icons Design

## Goal

Show which transport connections are currently active for the open one-to-one
chat without adding bulky toolbar text.

## User Experience

The conversation toolbar shows three small icons next to the contact name:

- Internet
- WiFi
- Bluetooth

Each icon is always visible. Active transports are colored; inactive transports
are grey. If more than one transport is registered for the contact, each active
transport is colored. If no transport is active, all three icons are grey.

The existing avatar online/offline status remains unchanged.

## Transport Mapping

- Internet uses `TorConstants.ID` (`org.briarproject.bramble.tor`).
- WiFi uses `LanTcpConstants.ID` (`org.briarproject.bramble.lan`).
- Bluetooth uses `BluetoothConstants.ID`
  (`org.briarproject.bramble.bluetooth`).

## State Source

Initial state is read from `ConnectionRegistry` for the current chat contact.
Live updates come from `ConnectionOpenedEvent` and `ConnectionClosedEvent`.
Events for other contacts are ignored.

Briar can temporarily have more than one connection registered for a contact.
The UI therefore does not collapse state to a single best transport; it shows all
three transport states independently.

## Interaction And Accessibility

The icon strip has a content description that includes all three states, such as
`Internet active, WiFi inactive, Bluetooth inactive`.

Tapping the icon strip shows the same state in a short toast. This keeps the
toolbar compact while making the icon meanings discoverable.

## Visual Requirements

Use vector drawables. Reuse existing icons where suitable and add missing
Internet/Bluetooth/WiFi icons only if needed.

Use the existing toolbar text color or another theme-appropriate accent for
active icons. Use a disabled/secondary grey tint for inactive icons.

The icon strip must not overlap the contact name. The contact name keeps single
line ellipsizing on narrow screens.

## Testing

Add focused tests for the state mapping logic if it is extracted from the
activity. At minimum, verify:

- Initial states map registry connectivity to icon states.
- Open/close events for the current contact update only the matching transport.
- Events for other contacts do not change the current chat state.
- Multiple simultaneous active transports can be represented.

Run the Android compile/build after implementation.
