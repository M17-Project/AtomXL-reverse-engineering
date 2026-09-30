# Command format

There are two protocol layers:

```
Android app / native service --(host frames 7E 96 69 ... 81, USB CDC-ACM)--> STM32F411 --(module frames 68 ... 10, UART)--> DMR-006 (HR-C7000 UART3)
```

On the phone, the host link is the STM32's USB CDC-ACM port, `/dev/ttyACM0`. The stock software opens it at 115200 8N1 raw.

The STM32 handles type-0 (system) host frames itself. It re-packs type-1 (module) frames into module frames and forwards them, and it wraps the module's replies back into host frames.

Verified against STM32 firmware `20201016161541.bin` and DMR module firmware `DMR006.U4A.V076.bin`, and cross-checked with the [ComJot API](./docs/ComJot_API-1.pdf).

## Checksum (both layers)

This is the ComJot `PcCheckSum`: a 16-bit ones'-complement sum over big-endian 16-bit words. An odd final byte counts as the high byte.

```c
uint16 PcCheckSum(uint8 *buf, int16 len)
{
    uint32 sum = 0;
    while (len > 1) { sum += (buf[0] << 8) | buf[1]; buf += 2; len -= 2; }
    if (len) sum += buf[0] << 8;
    while (sum >> 16) sum = (sum & 0xFFFF) + (sum >> 16);
    return (uint16)(sum ^ 0xFFFF);
}
```

## Host frame (phone ↔ STM32)

| Offset | Size | Field | Notes |
|---|---|---|---|
| 0 | 3 | header | `7E 96 69` |
| 3 | 1 | type | 0 = system (STM32), 1 = module (forwarded) |
| 4 | 1 | op | command, see below |
| 5 | 1 | flag | always 1 from the phone, 0 in STM32 replies. Not forwarded to the module. |
| 6 | 2 | LEN | data length, **big-endian**, max 512 |
| 8 | LEN | data | |
| 8+LEN | 2 | checksum | **big-endian**, `PcCheckSum` over bytes 3 ... 7+LEN (type through end of data) |
| 10+LEN | 1 | tail | `81` |

Replies use the same format, with type and op echoed and flag = 0.

### System operations (type 0)

| op | Data | Description |
|---|---|---|
| `0x01` | - | Get MCU firmware version. The reply is 14 ASCII digits, the firmware's build timestamp `YYYYMMDDhhmmss`, produced by `sprintf("%d%02d%02d%02d%02d%02d")` from `__DATE__`/`__TIME__`. For example, `20201016161541` for the image of the same name. |
| `0x08` | 1 byte: 1/0 | Module update mode. 1 = the STM32 becomes a transparent bridge between USB CDC-ACM and the module UART (no framing either way), 0 = normal framed operation. The stock module updater uses this. |
| `0x09` | n bytes | Raw write of the data to the module UART, with no framing. Not used by the stock updater (which uses bridge mode, 0x08), purpose otherwise unknown. |
| `0x10` | 1 byte: 1/0 | DMR module power on/off. Sent by `extmodule`'s `setDmrOnOffState()`. |
| `0x12` | 1 byte | Drives two module control GPIOs low (0 = also reconfigure them as outputs, otherwise re-initialise the UART). Probably reset/boot-mode control for updates (unconfirmed). |
| other | - | Error reply: type 0, same op, data `02`. |

Ready-made system frames (built exactly as `extmodule`'s `make_cmd` does), for testing by hand:

| Frame | Bytes |
|---|---|
| Module power off | `7E 96 69 00 10 01 00 01 00 FD EF 81` |
| Module power on | `7E 96 69 00 10 01 00 01 01 FD EE 81` |
| Bridge mode on | `7E 96 69 00 08 01 00 01 01 FD F6 81` |
| Bridge mode off | `7E 96 69 00 08 01 00 01 00 FD F7 81` |

Both firmware update procedures (MCU via DFU, module via YMODEM) are described in [firmware-update.md](./atomxl-re-docs/firmware-update.md).

### Module operations (type 1)

The op is the module command, and the data is the module payload, forwarded unchanged. The STM32 builds the module frame with R/W = 1 and S/R = 1.

Two length checks happen on the STM32:

- `0x22` must have LEN = 163 (`0xA3`).
- `0x23` must have LEN = 19 (`0x13`).

If either check fails, the phone gets a type-1 error reply with data `05`.

## Module frame (STM32 ↔ DMR-006)

| Offset | Size | Field | Notes |
|---|---|---|---|
| 0 | 1 | head | `68` |
| 1 | 1 | CMD | command |
| 2 | 1 | R/W | 0 = read, 1 = write, 2 = sent by the module (unsolicited) |
| 3 | 1 | S/R | request: 1 = set. Reply: status (see below). |
| 4 | 2 | checksum | **big-endian**. `PcCheckSum` over the **whole frame**, computed with this field set to 0. A received frame is valid if the sum over the whole frame is 0. |
| 6 | 2 | LEN | payload length, **big-endian**, max 798 |
| 8 | LEN | payload | multi-byte fields are **little-endian** (this is what ComJot's "little-endian" note refers to) |
| 8+LEN | 1 | tail | `10` |

Reply to a set command is 9 bytes: `68 CMD 00 status ck ck 00 00 10`. Status values:

- 0 = OK
- 1 = failed: bad parameter, or bad head/tail
- 2 = checksum error

The STM32 forwards module replies to the phone as type-1 host frames. A reply with an empty payload is forwarded with one data byte, the status. A module frame with a bad checksum is reported to the phone as data `01`.

Unsolicited module frames (R/W = 2) are mostly not forwarded. The STM32 acknowledges `0x36` (status notification) itself. It forwards `0x2D` (received SMS) only after a `0x36` notification with type 5.

### Module commands

The DMR module dispatcher handles `0x22`-`0x3C` and `0x88`. Names are from ComJot, corrected where the firmware disagrees.

| CMD | Description | Payload / notes |
|---|---|---|
| `0x22` | Set digital channel | 163-byte digital struct (ComJot `1.01`) |
| `0x23` | Set analog channel | 19-byte `DB_ANALOG_INFO`, see below |
| `0x24` | Get digital channel info | |
| `0x25` | Get analog channel info | |
| `0x26` | Reset transmission info | |
| `0x27` | Check analog init complete | |
| `0x28` | Set enhanced features | |
| `0x29` | Set encryption | |
| `0x2A` | Set MIC gain | |
| `0x2B` | Get received call info | |
| `0x2C` | Send SMS | |
| `0x2D` | Get SMS content | |
| `0x2E` | Set volume | 1 byte, 0-9 (0 also disables the codec line-out) |
| `0x2F` | Set monitor | 1 byte: 1 = on, 2 = off. ComJot's index calls this "Set gain", but its own section and the firmware say monitor. |
| `0x30` | Set squelch | 1 byte, 0-9 |
| `0x31` | Set power saving | 1 byte: 1 = on, 2 = off |
| `0x32` | Get signal strength | |
| `0x33` | Set repeater decoupling | 1 byte: 1 = on, 2 = off |
| `0x34` | Get module firmware version | |
| `0x35` | Test input | |
| `0x36` | Analog status notification (module → host) | acknowledged by the STM32 |
| `0x37` | Set polite (channel-access) strategy | |
| `0x38` | Set analog/digital dual-mode sync | |
| `0x39` | Get analog/digital sync info | |
| `0x3A`-`0x3C`, `0x88` | Undocumented | handlers exist in firmware |

### `0x23` payload: `DB_ANALOG_INFO` (19 bytes)

The firmware validates each field, the ranges below are the firmware's, with ComJot's in brackets where they differ. Values sent by the stock app are in the last column.

| Offset | Type | Field | Range | Stock |
|---|---|---|---|---|
| 0 | u32 LE | `rx_freq` | Hz, 400-490 MHz [ComJot: 400-480 MHz] | |
| 4 | u32 LE | `tx_freq` | Hz, 400-490 MHz | |
| 8 | u8 | `band` | 0 = 12.5 kHz, 1 = 25 kHz (selects the AT1846S bandwidth table) | |
| 9 | u8 | `power` | 0 = low, 1 = high | |
| 10 | u8 | `sq` | squelch 0-9: 0 keeps audio open | |
| 11 | u8 | `rx_type` | 0 = off, 1 = CTCSS, 2 = DCS normal, 3 = DCS inverted | |
| 12 | u8 | `rx_subcode` | CTCSS 0-50, DCS 0-83 [ComJot: 0-82] | |
| 13 | u8 | `tx_type` | as `rx_type` | |
| 14 | u8 | `tx_subcode` | as `rx_subcode` | |
| 15 | u8 | `pwrsave` | 1 = on (duty-cycles the AT1846S), 2 = off | 1 |
| 16 | u8 | `volume` | 0-9: codec DAC gain 0, -10, -4, +3, +8, +11, +14, +17, +20, +22 dB | 8 |
| 17 | u8 | `monitor` | 1 = on (forces FM audio to the codec), 2 = off | 2 |
| 18 | u8 | `relay` | repeater decoupling: 1 = on, 2 = off | 2 |

For M17 receive, use `pwrsave` = 2 and `monitor` = 1, and a lower `volume` to avoid digital clipping. See the README for the full receive-path analysis.

## Other modules (not used by the Atom XL)

`ExtModuleProtocol` in the Intercom app also contains a command set for a different module family. These are listed only for reference:

| op | Description |
|---|---|
| `0x02` | Set volume |
| `0x07` | Send message |
| `0x10` | Get call info |
| `0x11` | Get message content |
| `0x25` | Get module version |
| `0x35` | Set analog channel |
| `0x36` | Set digital channel |
