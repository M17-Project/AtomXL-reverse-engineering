// Disassembles and creates functions at C-SKY v2 push prologues (push16 0x14Cx/0x14Dx, push32 0xEBE0)
// in every initialized executable block. Run as pre- and post-script.
//@category HR-C7000
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.*;
import ghidra.app.cmd.disassemble.DisassembleCommand;
import ghidra.app.cmd.function.CreateFunctionCmd;

public class SeedCsky extends GhidraScript {
    public void run() throws Exception {
        Memory m = currentProgram.getMemory();
        Listing L = currentProgram.getListing();
        int n = 0;
        for (MemoryBlock blk : m.getBlocks()) {
            if (!blk.isInitialized() || !blk.isExecute()) continue;
            Address base = blk.getStart();
            if (L.getInstructionAt(base) == null) new DisassembleCommand(base, null, true).applyTo(currentProgram, monitor);
            for (long off = 0; off + 4 <= blk.getSize(); off += 2) {
                Address a = base.add(off);
                if (L.getInstructionContaining(a) != null) continue;
                int h = m.getShort(a) & 0xffff;
                boolean hit = ((h & 0xffe0) == 0x14c0 && (h & 0x1f) != 0) || h == 0xebe0;
                if (!hit) continue;
                new DisassembleCommand(a, null, true).applyTo(currentProgram, monitor);
                if (L.getInstructionAt(a) != null) { new CreateFunctionCmd(a).applyTo(currentProgram, monitor); n++; }
            }
        }
        println("SeedCsky: created " + n + " functions");
    }
}
