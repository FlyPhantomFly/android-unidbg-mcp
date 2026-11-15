import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.arm.backend.DynarmicFactory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.linux.android.dvm.DalvikModule;
import com.github.unidbg.linux.android.dvm.VM;

import java.io.File;

public class McpServer {

    public static void main(String[] args) throws Exception {
        // --- Resolve configuration ---
        // Priority: CLI arg[0] > UNIDBG_LIB env var
        String libPath = args.length > 0 ? args[0] : System.getenv("UNIDBG_LIB");

        // Priority: CLI arg[1] > UNIDBG_PORT env var > default 9239
        int port;
        if (args.length > 1) {
            port = Integer.parseInt(args[1]);
        } else {
            port = Integer.parseInt(System.getenv().getOrDefault("UNIDBG_PORT", "9239"));
        }

        // --- Boot emulator ---
        AndroidEmulator emulator = AndroidEmulatorBuilder.for64Bit()
                .addBackendFactory(new DynarmicFactory(true))
                .build();
        emulator.getMemory().setLibraryResolver(new AndroidResolver(23));

        // --- Load target library (if provided) ---
        if (libPath != null && !libPath.isEmpty()) {
            File libFile = new File(libPath);
            if (!libFile.exists()) {
                System.err.println("[McpServer] library not found: " + libPath);
                System.exit(1);
            }
            VM vm = emulator.createDalvikVM();
            DalvikModule dm = vm.loadLibrary(libFile, false);
            dm.callJNI_OnLoad(emulator);
            System.out.println("[McpServer] loaded " + libPath
                    + " at 0x" + Long.toHexString(dm.getModule().base)
                    + " (size 0x" + Long.toHexString(dm.getModule().size) + ")");
        } else {
            System.err.println("[McpServer] no library path provided — starting with empty emulator");
        }

        // --- Start MCP server ---
        com.github.unidbg.mcp.McpServer server =
                new com.github.unidbg.mcp.McpServer(emulator, port);

        server.start();
        server.setDebugIdle(true);

        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            System.out.println("[McpServer] shutting down...");
            try { server.stop(); } catch (Throwable ignored) {}
        }));

        System.out.println("[McpServer] listening on http://localhost:" + port + "/sse");
        System.out.println("[McpServer] press Ctrl+C to stop");

        // --- Service queued tool calls forever ---
        while (true) {
            try {
                server.executePendingOperation();
            } catch (Throwable t) {
                System.err.println("[McpServer] operation error: " + t);
            }
            Thread.sleep(50);
        }
    }
}
