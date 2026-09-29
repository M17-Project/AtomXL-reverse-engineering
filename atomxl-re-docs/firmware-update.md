# Firmware update paths

Two independent processors get flashed, by two different mechanisms. Both are
driven by the native side over the STM32's USB CDC-ACM port (`/dev/ttyACM0`),
either from `/system/bin/extmodule` (Android 10) or from the
`vendor.mediatek.hardware.aguiextmodule@1.0` HIDL HAL (Android 11).

## MCU (STM32F411) - DFU

The STM32 ROM DFU bootloader (USB `0483:df11`). The stock `auto_update` script:

```sh
extcmdtest -u1 -r1 -p1          # assert boot pins, reset into DFU
sleep 1.5
dfu-util -d 0483:df11 -a 0 -s 0x08000000 -D <firmware.bin>
sleep 0.5
extcmdtest -u0 -p0 -r0          # release, run application
```

Firmware lives in `/system/data/mcu` (A10) or `/vendor/data/mcu` (A11), named with
a 14-digit timestamp. The image loads at 0x08000000 and has no checksum, so a
single-byte patch can be flashed as-is. Recovery is always possible: the DFU
bootloader is in ROM.

## DMR module (HR-C7000) - YMODEM

Reconstructed from `extmodule`'s `update_dmr_firmware`. The module has a
bootloader (in the first 0x1A000 of its SPI flash, not in the dumped image) that
runs an ST AN2557-style IAP menu, entered by holding PTT during power-up.

Sequence:

1. Open `/dev/ttyACM0` at **115200 8N1, raw**.
2. `setDmrOnOffState(0)` - power the module off (sysfs `mcu_power`), wait ~4 s.
3. Assert PTT: write `0` to `/sys/bus/platform/drivers/mcu/handle_ptt`.
4. Host system command **0x08 = 1** → the STM32 becomes a transparent UART bridge.
5. Power the module on, wait ~2 s. With PTT held, it stays in the bootloader.
6. Send `'1'` (the download menu entry). Wait for at least six `'C'` characters.
7. **YMODEM-1K** transfer:
   - 1024-byte data blocks (SOH/128-byte for the tail), 0x1A padding
   - CRC-16/CCITT (poly 0x1021), high byte first
   - block 0 carries the file name and the decimal size, EOT ends it.
8. Release PTT (write `1`), host command **0x08 = 0**, done.

If fewer than six `'C'`s arrive, `extmodule` gives up and instead sends module
command 0x0A (`make_cmd(...,10,...)`) - i.e. it assumes the module is already
running and just re-selects the channel.

### Flashing a modified image without a PC

The app also scans `/sdcard/intercom/dmr/` (`/storage/self/primary/intercom/dmr/`).
A `.bin` there is flashed when its name sorts after `<module-version>.bin`
(case-insensitive). The running version is the ASCII string inside the image:

- `DMR006.U4A.V076` at file offset **0x33410** (`V076` at **0x3341B**).

So to flash a patched image, bump the version (e.g. `V077`) both in the file name
and at offset 0x3341B, or the app re-flashes on every launch. To revert, drop the
original back with a higher version string.

### Recovery

PTT-held power-up enters the bootloader before the application runs, so a bad
application image can be reflashed as long as the bootloader is intact (YMODEM
never writes to it). The CK803S mask ROM is a further fallback, but its download
protocol is not yet documented.

### Unknowns

- The flash address the bootloader writes to (assumed 0x0301A000).
- Whether the bootloader validates the image (no checksum found in the payload).
- Other IAP menu entries. ST's AN2557 menu uses `'2'` = upload, sending `'2'` in
  update mode may dump the whole flash (bootloader included) - untested.
