// Maps the RAM-resident segments of the HR-C7000 DMR module firmware.
// Run as a -preScript on an import of flash.bin (see split_dmr.py), base 0x0301A000.
// Argument: path to the ORIGINAL firmware file (DMR006.U4A.V076.bin).
//@category HR-C7000
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.*;
import ghidra.program.model.mem.*;
import java.nio.file.*;
import java.util.Arrays;

public class MapDmrMemory extends GhidraScript {
    static final int IRAM_SRC = 0x34368;           // file offset of IRAM image
    static final long IRAM_DST = 0x00010000L;
    static final int SRAM_SRC = 0x2DF90;           // file offset of SRAM image
    static final long SRAM_DST = 0x18000800L;
    static final int SRAM_LEN = 0x2E60;

    public void run() throws Exception {
        String[] args = getScriptArgs();
        if (args.length < 1) throw new IllegalArgumentException("usage: MapDmrMemory.java <DMR006.U4A.V076.bin>");
        byte[] all = Files.readAllBytes(Paths.get(args[0]));
        Memory m = currentProgram.getMemory();
        AddressSpace sp = currentProgram.getAddressFactory().getDefaultAddressSpace();

        byte[] iram = Arrays.copyOfRange(all, IRAM_SRC, all.length);
        MemoryBlock b = m.createInitializedBlock("iram", sp.getAddress(IRAM_DST),
                new java.io.ByteArrayInputStream(iram), iram.length, monitor, false);
        b.setRead(true); b.setWrite(true); b.setExecute(true);
        long end = (IRAM_DST + iram.length + 0xF) & ~0xFL;
        m.createUninitializedBlock("iram_bss", sp.getAddress(end), 0x50000 - end, false).setWrite(true);

        m.createUninitializedBlock("sram_lo", sp.getAddress(0x18000000L), 0x800, false).setWrite(true);
        byte[] sram = Arrays.copyOfRange(all, SRAM_SRC, SRAM_SRC + SRAM_LEN);
        b = m.createInitializedBlock("sram", sp.getAddress(SRAM_DST),
                new java.io.ByteArrayInputStream(sram), sram.length, monitor, false);
        b.setRead(true); b.setWrite(true); b.setExecute(true);

        // Peripherals (HR-C7000 user guide, section 4.5.3)
        String[][] per = {
            {"modem", "0x11000000", "0x1000"},        // WORK_MODE 0x100, RF_MODE 0x104, FM regs 0x500..
            {"modem_buffer", "0x16000000", "0x1000"}, // codec @0x160009C0, RX audio RAM 0x160004E0
            {"apb", "0x14000000", "0x160000"},        // timers, GPIO, UART3 @0x14090000, SPI ...
        };
        for (String[] p : per) {
            MemoryBlock pb = m.createUninitializedBlock(p[0], sp.getAddress(Long.decode(p[1])), Long.decode(p[2]), false);
            pb.setRead(true); pb.setWrite(true); pb.setVolatile(true);
        }
        println("MapDmrMemory: iram 0x" + Integer.toHexString(iram.length) + " bytes, sram 0x" + Integer.toHexString(SRAM_LEN));
    }
}
