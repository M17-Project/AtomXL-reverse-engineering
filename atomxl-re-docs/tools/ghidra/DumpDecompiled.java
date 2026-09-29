// Decompiles every function into one greppable C file.
// Argument: output path (default ./decompiled.c)
//@category Export
import ghidra.app.script.GhidraScript;
import ghidra.app.decompiler.*;
import ghidra.program.model.listing.*;
import java.io.*;

public class DumpDecompiled extends GhidraScript {
    public void run() throws Exception {
        String[] a = getScriptArgs();
        String out = a.length > 0 ? a[0] : "decompiled.c";
        DecompInterface di = new DecompInterface();
        di.openProgram(currentProgram);
        int n = 0, fail = 0;
        try (PrintWriter pw = new PrintWriter(new FileWriter(out))) {
            for (Function f : currentProgram.getFunctionManager().getFunctions(true)) {
                DecompileResults r = di.decompileFunction(f, 60, monitor);
                pw.println("//==== " + f.getName() + " @ " + f.getEntryPoint() + " size " + f.getBody().getNumAddresses());
                if (r != null && r.decompileCompleted()) pw.println(r.getDecompiledFunction().getC());
                else { pw.println("// FAILED " + (r == null ? "" : r.getErrorMessage())); fail++; }
                n++;
            }
        }
        println("DumpDecompiled: " + n + " functions (" + fail + " failed) -> " + out);
    }
}
