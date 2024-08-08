# MCU Command format ("dataPackage")

- header (3 bytes): `0x7e_96_69`
- command type (1 byte): 0 for system (MCU) commands, 1 for module commands
- operation (1 byte): see list of [known command types](#known-command-types)
- unknown (1 byte): always 1 in ExtModuleProtocol and native service. Maybe direction???
- data length (2 bytes, big endian)
- data (n bytes)
- checksum (2 bytes, big endian): as per comjot api document:

  ```c
  uint16 PcCheckSum(uint8 *buf, int16 len)
  {
    uint32 sum = 0;
    while(len > 1) {
      sum += 0xFFFF & (*buf << 8 | *(buf + 1));
      buf += 2;
      len -= 2;
    }
    if (len) {
      sum += (0xFF & *buf) << 8;
    }
    while (sum >> 16) {
      sum = (sum & 0xFFFF) + (sum >> 16);
    }
    return (uint16)(sum ^ 0xFFFF);
  }
  ```

- tail (1 byte): `0x81`

## Known Command Types

### System (command type 0)

These seem to be handled by the STM32 MCU.

| operation byte | data    | direction? | description          |
| -------------- | ------- | ---------- | -------------------- |
| 0x1            | 1       | 1          | get firmware version |
| 0x8            | boolean | 1          | set DMR update state |
| 0x9            | byte    | 1          | serial put char      |
| 0x10           | boolean | 1          | set DMR on/off state |

### Module (command type 1)

These seem to be repackaged and sent to the DMR module. The Atom XL uses the "Dmr09" versions of these commands.

#### DMR09

This uses similar commands/data as several other modules, but packed
differently:

- `SR_DMR_2WUF` from sunrisedigit.com
  - [datasheet](./docs/SR_DMR_5WUF%20V100.pdf) [[Source](http://www.sunrisedigit.com/download/12-cn.html)]
- [ComJot API](./docs/ComJot_API-1.pdf), which is more conveniently in English
- https://www.nicerf.com/walkie-talkie-module/2w-digital-walkie-talkie-module-dmr818.html

Listed are commands found in the reverse engineered source code, but
others from the docs might work.

| operation byte | data | direction? | description                            |
| -------------- | ---- | ---------- | -------------------------------------- |
| 0x22           | TODO | 1          | set digital group (frequency/mode/etc) |
| 0x23           | TODO | 1          | set analog group (frequency/mode/etc)  |
| 0x2b           | 1    | 1          | get recv call info                     |
| 0x2c           | TODO | 1          | send message                           |
| 0x2d           | 1    | 1          | get message content                    |
| 0x2e           | byte | 1          | set receiver volume                    |
| 0x34           | 1    | 1          | get module software version            |

#### Non-DMR09

I have no idea what hardware these are for. Included here just because they were in `ExtModuleProtocol`.

| operation byte | data | direction? | description                            |
| -------------- | ---- | ---------- | -------------------------------------- |
| 0x2            | byte | 1          | set receiver volume                    |
| 0x7            | TODO | 1          | send message                           |
| 0x10           | 1    | 1          | get call info                          |
| 0x11           | 1    | 1          | get message content                    |
| 0x25           | 1    | 1          | get module software version            |
| 0x35           | TODO | 1          | set analog group (frequency/mode/etc)  |
| 0x36           | TODO | 1          | set digital group (frequency/mode/etc) |
