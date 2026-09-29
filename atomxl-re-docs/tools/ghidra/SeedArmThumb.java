// For raw STM32 (Cortex-M) images loaded at 0x08000000: disassembles vector-table
// targets and creates functions at Thumb push {..., lr} prologues (0xB5xx, 0xE92D).
//@category STM32
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.*;
import ghidra.app.cmd.disassemble.DisassembleCommand;
import ghidra.app.cmd.function.CreateFunctionCmd;

public class SeedArmThumb extends GhidraScript {
    public void run() throws Exception {
        Memory m = currentProgram.getMemory(); Listing L = currentProgram.getListing();
        MemoryBlock blk = m.getBlocks()[0];
        Address base = blk.getStart(); long len = blk.getSize(); int n = 0;
        for (int v = 1; v < 100; v++) {
            long t = m.getInt(base.add(4L * v)) & 0xffffffffL;
            if ((t & 1) == 1 && t >= base.getOffset() && t < base.getOffset() + len) {
                Address a = toAddr(t & ~1L);
                new DisassembleCommand(a, null, true).applyTo(currentProgram, monitor);
                new CreateFunctionCmd(a).applyTo(currentProgram, monitor);
            }
        }
        for (long off = 0x188; off + 4 <= len; off += 2) {
            Address a = base.add(off);
            if (L.getInstructionContaining(a) != null) continue;
            Data dt = L.getDataContaining(a);
            if (dt != null && dt.isDefined()) continue;
            int h = m.getShort(a) & 0xffff;
            if ((h & 0xff00) == 0xb500 || h == 0xe92d) {
                new DisassembleCommand(a, null, true).applyTo(currentProgram, monitor);
                if (L.getInstructionAt(a) != null) { new CreateFunctionCmd(a).applyTo(currentProgram, monitor); n++; }
            }
        }
        println("SeedArmThumb: created " + n + " functions");
    }
}
