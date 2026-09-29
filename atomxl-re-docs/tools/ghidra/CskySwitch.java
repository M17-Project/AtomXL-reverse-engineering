// Resolves C-SKY switch jump tables and installs decompiler jump-table overrides,
// so the decompiler emits real switch statements.
//
// Recognised idioms (scanning backwards from `jmp rT`):
//   A)  cmphsi rI,N            (optional bound)
//       lrw    rB,<pool>       (pool word = table base address)
//       ldr.w  rT,(rB,rI<<2)
//       jmp    rT
//   B)  cmphsi rI,N            (optional bound)
//       lsli   rX,rI,2
//       lrw    rB,<pool>
//       addu   rB,rX,rB
//       ld.w   rT,(rB,0x0)
//       jmp    rT
//
// Tables with no recovered bound are read until the next word is not a valid code
// pointer; those are logged "NO BOUND" so you can verify them by hand.
//@category HR-C7000
import ghidra.app.script.GhidraScript;
import ghidra.program.model.address.*;
import ghidra.program.model.listing.*;
import ghidra.program.model.mem.*;
import ghidra.program.model.symbol.*;
import ghidra.program.model.pcode.JumpTable;
import ghidra.app.cmd.disassemble.DisassembleCommand;
import ghidra.app.cmd.function.CreateFunctionCmd;
import java.util.*;
import java.util.regex.*;

public class CskySwitch extends GhidraScript {
    static final int WINDOW = 24;        // instructions to scan backwards
    static final int MAX_UNBOUNDED = 64;
    Memory m; AddressSpace sp;

    long rd(long a) throws Exception { return m.getInt(sp.getAddress(a)) & 0xffffffffL; }

    boolean valid(long t) {
        MemoryBlock b = m.getBlock(sp.getAddress(t & ~1L));
        return b != null && b.isExecute() && b.isInitialized();
    }

    public void run() throws Exception {
        m = currentProgram.getMemory();
        sp = currentProgram.getAddressFactory().getDefaultAddressSpace();
        Listing L = currentProgram.getListing();
        Pattern pLdr  = Pattern.compile("ldr\\.w (r\\d+),\\((r\\d+),(r\\d+)<<2\\)");
        Pattern pLdw  = Pattern.compile("ld\\.w (r\\d+),\\((r\\d+),0x0\\)");
        Pattern pAddu = Pattern.compile("addu (r\\d+),(r\\d+),(r\\d+)");
        Pattern pLsl  = Pattern.compile("lsli (r\\d+),(r\\d+),0x2");
        Pattern pLrw  = Pattern.compile("lrw (r\\d+),0x([0-9a-f]+)");
        Pattern pCmp  = Pattern.compile("cmphsi (r\\d+),0x([0-9a-f]+)");

        List<Instruction> jmps = new ArrayList<>();
        for (Instruction i : L.getInstructions(true)) if (i.getMnemonicString().equals("jmp")) jmps.add(i);

        int ok = 0;
        for (Instruction j : jmps) {
            String target = j.getDefaultOperandRepresentation(0);
            Set<String> baseRegs = new HashSet<>();  // registers currently holding (part of) the table base
            String idxReg = null;
            Long table = null; Integer bound = null;
            boolean started = false;

            Instruction p = j;
            for (int k = 0; k < WINDOW && (p = p.getPrevious()) != null; k++) {
                String s = p.toString(); Matcher mm;

                if (!started && (mm = pLdr.matcher(s)).find() && mm.group(1).equals(target)) {
                    baseRegs.add(mm.group(2)); idxReg = mm.group(3); started = true; continue;
                }
                if (!started && (mm = pLdw.matcher(s)).find() && mm.group(1).equals(target)) {
                    baseRegs.add(mm.group(2)); started = true; continue;
                }
                if (!started) continue;

                if ((mm = pAddu.matcher(s)).find() && baseRegs.contains(mm.group(1))) {
                    baseRegs.remove(mm.group(1)); baseRegs.add(mm.group(2)); baseRegs.add(mm.group(3)); continue;
                }
                if ((mm = pLsl.matcher(s)).find() && baseRegs.contains(mm.group(1))) {
                    baseRegs.remove(mm.group(1)); idxReg = mm.group(2); continue;   // this reg was index<<2
                }
                if (table == null && (mm = pLrw.matcher(s)).find() && baseRegs.contains(mm.group(1))) {
                    table = rd(Long.parseLong(mm.group(2), 16)); continue;
                }
                if (idxReg != null && bound == null && (mm = pCmp.matcher(s)).find() && mm.group(1).equals(idxReg)) {
                    bound = Integer.parseInt(mm.group(2), 16);
                }
            }

            if (table == null) { println("unresolved jmp @" + j.getAddress()); continue; }

            int n = bound != null ? bound : MAX_UNBOUNDED;
            ArrayList<Address> dests = new ArrayList<>();
            for (int e = 0; e < n; e++) {
                long t = rd(table + 4L * e);
                if (!valid(t)) break;
                dests.add(sp.getAddress(t & ~1L));
            }
            if (dests.isEmpty()) continue;

            for (Address d : dests) {
                j.addOperandReference(0, d, RefType.COMPUTED_JUMP, SourceType.USER_DEFINED);
                if (L.getInstructionAt(d) == null) new DisassembleCommand(d, null, true).applyTo(currentProgram, monitor);
            }
            Function f = getFunctionContaining(j.getAddress());
            if (f != null) {
                new JumpTable(j.getAddress(), dests, true, 0).writeOverride(f);
                CreateFunctionCmd.fixupFunctionBody(currentProgram, f, monitor);
            }
            ok++;
            println(String.format("jmp @%s table 0x%x cases=%d%s fn=%s", j.getAddress(), table, dests.size(),
                    bound == null ? " (NO BOUND - verify)" : "", f == null ? "-" : f.getName()));
        }
        println("CskySwitch: resolved " + ok + "/" + jmps.size());
    }
}
