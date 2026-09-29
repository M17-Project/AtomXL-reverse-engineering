#!/usr/bin/env python3
"""Split DMR006.U4A.V076.bin into the flash (XIP) part for import.

Usage: split_dmr.py DMR006.U4A.V076.bin [outdir]
Writes flash.bin (file 0x0..0x34367, loads at 0x0301A000).
The RAM-resident parts are mapped by MapDmrMemory.java from the original file.
"""
import sys, os, struct
src = sys.argv[1]
out = sys.argv[2] if len(sys.argv) > 2 else "."
d = open(src, "rb").read()
# Copy descriptors in the image header (offset 0xC4), each (dst, dst_end, src):
#   (0x18000800, 0x18003660, 0x03047F90)  -> SRAM
#   (0x00010000, 0x00031B6C, 0x0304E368)  -> IRAM (initialised data follows the code)
hdr = struct.unpack_from("<6I", d, 0xC4)
for dst, end, srcaddr in (hdr[0:3], hdr[3:6]):
    print("copy 0x%08X..0x%08X <- flash 0x%08X (file 0x%X)" % (dst, end, srcaddr, srcaddr - 0x0301A000))
assert hdr[2] == 0x03047F90 and hdr[5] == 0x0304E368, "unexpected header - different firmware version?"
split = hdr[5] - 0x0301A000
open(os.path.join(out, "flash.bin"), "wb").write(d[:split])
print("flash.bin: 0x%X bytes (load at 0x0301A000); IRAM part: 0x%X bytes (load at 0x00010000)" % (split, len(d) - split))
