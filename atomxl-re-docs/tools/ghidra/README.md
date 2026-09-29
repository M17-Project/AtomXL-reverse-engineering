# Ghidra scripts

Headless scripts used to reverse-engineer the two firmwares. Tested with Ghidra
12.1.4 and the [ghidra_csky](https://github.com/leommxj/ghidra_csky) processor
module (needed only for the DMR module, the STM32 uses stock ARM).

## Setup

1. Install the C-SKY extension into Ghidra and compile its SLEIGH spec:
   copy `ghidra_csky/CSKY` into `Ghidra/Processors/CSKY`, then
   `support/sleigh Ghidra/Processors/CSKY/data/languages/csky_v2.slaspec`.
2. A JDK matching the Ghidra release is required for step 1.

## DMR module (HR-C7000, C-SKY CK803S)

```sh
python3 split_dmr.py DMR006.U4A.V076.bin      # -> flash.bin (the XIP part)

analyzeHeadless <projdir> dmr \
  -import flash.bin \
  -processor CSKY_V2:LE:32:default \
  -loader BinaryLoader -loader-baseAddr 0x0301A000 \
  -scriptPath . \
  -preScript  MapDmrMemory.java /abs/path/DMR006.U4A.V076.bin \
  -preScript  SeedCsky.java \
  -postScript SeedCsky.java \
  -postScript CskySwitch.java \
  -postScript DumpDecompiled.java /abs/path/dmr.c \
  -postScript DumpListing.java    /abs/path/dmr_listing.txt
```

`SeedCsky` runs twice on purpose: once before auto-analysis to seed entry points,
once after to catch prologues exposed by the analysis. Expect ~1141 functions and
21/22 jump tables resolved. The one remaining (`jmp @03029ebc`) is a second table
sharing a dispatch and must be done by hand. Tables logged "NO BOUND - verify"
were read until the first non-code word, check their length.

Base address 0x0301A000 is from string-pointer voting and is baked into the
commands above.

## STM32 (Cortex-M4, raw image at 0x08000000)

```sh
analyzeHeadless <projdir> stm \
  -import 20201016161541.bin \
  -processor ARM:LE:32:Cortex \
  -loader BinaryLoader -loader-baseAddr 0x08000000 \
  -scriptPath . \
  -preScript  SeedArmThumb.java \
  -postScript DumpDecompiled.java /abs/path/stm.c \
  -postScript DumpListing.java    /abs/path/stm_listing.txt
```

## Scripts

| Script | Purpose |
|---|---|
| `split_dmr.py` | Extract the XIP `flash.bin` and print the header copy descriptors |
| `MapDmrMemory.java` | Map the IRAM/SRAM copied segments and the peripheral regions |
| `SeedCsky.java` | Disassemble and create functions at C-SKY push prologues |
| `CskySwitch.java` | Resolve C-SKY switch jump tables (decompiler overrides) |
| `SeedArmThumb.java` | Seed functions from the vector table and Thumb prologues (STM32) |
| `DumpDecompiled.java` | Decompile every function into one greppable `.c` file |
| `DumpListing.java` | Plain-text disassembly with resolved references |

The ELF service binary `extmodule` (AArch64) needs no scripts, import it normally,
its symbols are intact.
