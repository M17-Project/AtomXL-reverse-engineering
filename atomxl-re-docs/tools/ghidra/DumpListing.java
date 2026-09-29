// Writes a plain-text disassembly listing with resolved references ("->addr"),
// one instruction per line, function starts marked with ";---- name".
// Argument: output path (default ./listing.txt)
//@category Export
import ghidra.app.script.GhidraScript;
import ghidra.program.model.listing.*;
import ghidra.program.model.symbol.Reference;
import java.io.*;

public class DumpListing extends GhidraScript {
    public void run() throws Exception {
        String[] a = getScriptArgs();
        String out = a.length > 0 ? a[0] : "listing.txt";
        FunctionManager fm = currentProgram.getFunctionManager();
        try (PrintWriter pw = new PrintWriter(new FileWriter(out))) {
            for (Instruction i : currentProgram.getListing().getInstructions(true)) {
                Function f = fm.getFunctionAt(i.getAddress());
                if (f != null) pw.println("\n;---- " + f.getName());
                StringBuilder sb = new StringBuilder();
                for (Reference r : i.getReferencesFrom()) sb.append(" ->").append(r.getToAddress());
                pw.println(i.getAddress() + "  " + i + sb);
            }
        }
        println("DumpListing -> " + out);
    }
}
