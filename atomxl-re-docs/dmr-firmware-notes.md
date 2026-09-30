# DMR module firmware notes (HR-C7000)

Reverse-engineering notes for `DMR006.U4A.V076.bin` (version string `DMR006.U4A.V076`).
Addresses are for this exact image, derive for other versions. See
[tools/ghidra](./tools/ghidra) to reproduce the analysis and
[command-format.md](../command-format.md) for the wire protocol.

## SoC

Dahua **HR-C7000**: C-SKY **CK803S** core (little-endian), uC/OS-III, with an
integrated DMR/FM modem, vocoder and audio codec. Runs XIP from external SPI NOR.

## Image layout

Plaintext, two loadable regions described by copy descriptors at file offset 0xC4
(each `dst, dst_end, src`):

| File offset | Loads at | Contents |
|---|---|---|
| `0x00000`-`0x34367` | `0x0301A000` | main code (XIP from flash) |
| `0x34368`-end | `0x00010000` | IRAM: RTOS, drivers, AT1846S + register routines |
| `0x2DF90` (len `0x2E60`) | `0x18000800` | SRAM code/data (incl. a jump table) |

Load base 0x0301A000 is from string-pointer voting. Calls from the IRAM region
appear offset by ~0x0303E368 if the image is mapped flat — map the regions
(see `MapDmrMemory.java`) before analysis.

## Memory map (from the HR-C7000 user guide, §4.5.3)

| Base | Region |
|---|---|
| `0x00000000` | 2 KB boot ROM |
| `0x00010000` | IRAM |
| `0x00040000` | SRAM (data) |
| `0x03000000` | SPI flash (XIP) |
| `0x11000000` | Modem control |
| `0x16000000` | Modem buffer + codec |
| `0x14000000` | APB peripherals (timers, GPIO, UART, SPI) |
| `0x18000000` | SRAM |

## Key registers

Modem (`0x11000000`):

| Offset | Name | Notes |
|---|---|---|
| `0x100` | WORK_MODE | bit 7 = FM mode (set by `FUN_0301af60`) |
| `0x104` | RF_MODE | bit 24 = AF input, bits 6:5 = 3: two-point modulation, bits 31 and 29 ("reserved") enable an undocumented physical-layer receive test mode |
| `0x108` | SIG_CENTER | two-point per-path offset (±422 mV) |
| `0x114` | RF_MOD_BIAS_CTRL | MOD1/MOD2 amplitude, and MOD1 RX bias |
| `0x500` | FM_BANDWIDTH | **never written**, reset 0x5F keeps band-pass (bit 6) and emphasis (bit 4) ON |
| `0x504` | FM_DEV_COEF | **never written**, TX limiter `tr_sig_lim` = 0x0F |

Codec (`0x160009C0`): DAC/ADC at a fixed 8 kHz (FCR_DAC never written), volume
byte → GCR_DACL digital gain. FM RX audio is mirrored in RAM at
`0x160004E0`-`0x160008DF`.

## Audio sample rates

- The receive ADC digitises the AT1846S AF output at **38.4 kHz** (8 samples per
  4800-baud symbol), before the FM chain's filters. No documented register exposes
  these samples to the CPU.
- The FM chain runs internally at 32-38.4 kHz (its tone blocks are specified
  against a 32 kHz clock).
- The modem hands audio to the codec as 16-bit words with an **8 kHz strobe**
  (user guide §7.2). The CPU tone-playback path and the vocoder feed are 8 kHz too.
- `FCR_DAC.dac_freq` (`0x160009D4`, bits 3:0) and `FCR_ADC.adc_freq`
  (`0x160009D7`) document only `0000` = 8 kHz, other codes are "omitted". The
  codec's THD is specified for Fs <= 16 kHz, so higher codes likely exist, but
  with an 8 kHz source they would probably repeat samples rather than add
  bandwidth. Untested.
- FM receive RAM (`0x160004E0`-`0x160008DF`) holds two ping-pong buffers of 256
  16-bit PCM samples each, stored big-endian (high byte at the lower address).

AT1846S transceiver: I2C address 0x71, bit-banged by the C7000 on GPIO 7/8.
Register 0x58 = 0xBCFD (all voice filters + emphasis bypassed — same as OpenRTX M17).

## Key functions

| Address | Name (assigned) | Role |
|---|---|---|
| `0x03023fc8` | host frame dispatcher | validates `68...10` frames, switch on `cmd - 0x22` (table @ `0x0304D274`) |
| `0x03023500` | cmd 0x23 handler | applies `DB_ANALOG_INFO`, re-runs transceiver init, retunes |
| `0x0301af60` | FM mode setter | sets WORK_MODE bit 7 when not in DMR mode |
| `0x0301c8dc` | AT1846S write | I2C write (reg, val16) to address 0x71 |
| `0x0301c9e4` | transceiver init | plays the AT1846S register tables |
| `0x03020040` | codec init | powers up the codec, writes the volume/level register |
| `0x03021740` | checksum | ones'-complement 16-bit sum (see command-format.md) |
| `0x0302272c` | reply builder | emits a 9-byte module reply |
| `0x0002c540` | UART3 RX ISR | fills the 1500-byte host-frame ring buffer |

## Undocumented commands

`0x3A`-`0x3C` set internal flags, `0x88` reboots the module (`FUN_03023aa0`,
1 s delay then reset). Full command list in [command-format.md](../command-format.md).
