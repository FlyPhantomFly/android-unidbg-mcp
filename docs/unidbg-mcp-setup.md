> **Note:** this document describes the original *source-overlay* deployment
> (copying the MCP sources into an unidbg checkout and running via `mvnw exec:java`).
> The repo also supports a fully self-contained fat-jar build that needs no
> unidbg clone — see [README.md](../README.md) (Setup A) for that path.

unidbg MCP Server with OpenCode — Complete Setup Guide

A persistent, multi-target unidbg MCP server that integrates with OpenCode. Each "slot" runs an independent emulator bound to its own port, loading its own target library, and can be started/stopped without touching OpenCode's config.

Architecture

```
OpenCode session
  └─ npx mcp-remote (spawned per session by OpenCode)
       └─ http://127.0.0.1:9239/sse
            └─ unidbg McpServer (systemd user service, slot 1, Java 21)
                 └─ libb794.so @ 0x12000000 (Dynarmic backend)

OpenCode session
  └─ npx mcp-remote
       └─ http://127.0.0.1:9240/sse
            └─ unidbg McpServer (systemd user service, slot 2, Java 21)
                 └─ other-library.so
```

Each slot is:

· A systemd template instance (unidbg-mcp@N.service)
· Configured via ~/.config/unidbg/slot-N.conf
· Independent lifecycle, port, and library

Prerequisites

· Java 21 (or 11+) — required; Java 8 fails the ELF loader (Bytecode.position(int) NoSuchMethodError)
· Node.js with npx
· Maven (the project ships mvnw)
· systemd (user session)
· OpenCode installed

Verify:

```bash
java --version      # must report 11 or higher
which npx
readlink -f $(which java)
```

Note the JDK path from readlink — you'll need it in Step 5.

---

Step 1 — Clone and build unidbg

```bash
git clone https://github.com/zhkl0228/unidbg.git ~/unidbg
cd ~/unidbg
./mvnw install -DskipTests -Dgpg.skip=true
```

Installs all modules (0.9.10-SNAPSHOT) into the local Maven repository.

---

Step 2 — Create the parameterized MCP server class

Create ~/unidbg/unidbg-android/src/test/java/McpServer.java:

```java
import com.github.unidbg.AndroidEmulator;
import com.github.unidbg.arm.backend.DynarmicFactory;
import com.github.unidbg.linux.android.AndroidEmulatorBuilder;
import com.github.unidbg.linux.android.AndroidResolver;
import com.github.unidbg.linux.android.dvm.DalvikModule;
import com.github.unidbg.linux.android.dvm.VM;

import java.io.File;

public class McpServer {

    public static void main(String[] args) throws Exception {
        // Resolve config: CLI arg > env var > default
        String libPath = args.length > 0 ? args[0] : System.getenv("UNIDBG_LIB");
        int port;
        if (args.length > 1) {
            port = Integer.parseInt(args[1]);
        } else {
            port = Integer.parseInt(System.getenv().getOrDefault("UNIDBG_PORT", "9239"));
        }

        // Boot emulator with Dynarmic backend (avoids native unicorn_java lookup)
        AndroidEmulator emulator = AndroidEmulatorBuilder.for64Bit()
                .addBackendFactory(new DynarmicFactory(true))
                .build();
        emulator.getMemory().setLibraryResolver(new AndroidResolver(23));

        // Load target library (if configured)
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

        // Start MCP server
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

        // Service queued tool calls forever
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
```

Compile once:

```bash
cd ~/unidbg/unidbg-android
../mvnw test-compile -Dmaven.test.skip=false
```

---

Step 3 — Create the systemd template unit

```bash
mkdir -p ~/.config/systemd/user

cat > ~/.config/systemd/user/unidbg-mcp@.service <<'EOF'
[Unit]
Description=unidbg MCP Server (slot %i)
After=network.target
StartLimitIntervalSec=0

[Service]
Type=simple
WorkingDirectory=%h/unidbg/unidbg-android
EnvironmentFile=%h/.config/unidbg/slot-%i.conf
ExecStartPre=/bin/bash -c 'for i in {1..10}; do ss -ltn | grep -q ":$UNIDBG_PORT " || exit 0; sleep 1; done; exit 1'
ExecStart=/bin/bash -lc '../mvnw exec:java -Dexec.mainClass=McpServer -Dexec.classpathScope=test -Dmaven.test.skip=false'
Restart=always
RestartSec=5
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=default.target
EOF
```

Key points:

· StartLimitIntervalSec=0 lives in [Unit] (not [Service])
· EnvironmentFile= pulls UNIDBG_LIB and UNIDBG_PORT per slot
· ExecStartPre waits up to 10s for the port to be free
· Restart=always + StartLimitIntervalSec=0 = infinite retry

---

Step 4 — Create slot configuration files

```bash
mkdir -p ~/.config/unidbg

cat > ~/.config/unidbg/slot-1.conf <<'EOF'
UNIDBG_PORT=9239
UNIDBG_LIB=/home/dragon/targets/libb794.so
EOF

cat > ~/.config/unidbg/slot-2.conf <<'EOF'
UNIDBG_PORT=9240
UNIDBG_LIB=/home/dragon/targets/libc0ffee.so
EOF
```

Adjust paths to real files. If a slot exists purely for testing, set UNIDBG_LIB= (empty) to start with an empty emulator.

---

Step 5 — Force Java 21 for the service

The systemd user session inherits a minimal PATH, which may resolve java to JDK 8. Pin the correct JDK with a drop-in:

```bash
mkdir -p ~/.config/systemd/user/unidbg-mcp@.service.d

cat > ~/.config/systemd/user/unidbg-mcp@.service.d/override.conf <<'EOF'
[Service]
Environment=JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64
Environment=PATH=/usr/lib/jvm/java-21-openjdk-amd64/bin:/usr/local/bin:/usr/bin:/bin
EOF
```

Replace the path with the JDK you identified via readlink -f $(which java) (minus /bin/java).

Verify:

```bash
ls /usr/lib/jvm/java-21-openjdk-amd64/bin/java
systemctl --user show unidbg-mcp@1.service | grep -i environment
```

---

Step 6 — Enable and start slot 1

```bash
systemctl --user daemon-reload
systemctl --user enable --now unidbg-mcp@1.service
```

Wait a few seconds, then verify:

```bash
systemctl --user status unidbg-mcp@1.service --no-pager | head -15
ss -ltnp | grep 9239
journalctl --user -u unidbg-mcp@1.service -n 20 --no-pager
```

Expected in the journal:

```
[McpServer] loaded /home/dragon/targets/libb794.so at 0x12000000 (size 0x17c000)
[McpServer] listening on http://localhost:9239/sse
[McpServer] press Ctrl+C to stop
```

ss should show one java PID bound to :9239, matching Main PID from status.

---

Step 7 — Enable linger (start at boot, survive logout)

```bash
loginctl enable-linger $USER
loginctl show-user $USER | grep Linger
# Linger=yes
```

Without this, the service stops when you fully log out.

---

Step 8 — Configure OpenCode

Add entries to the mcp block in ~/.config/opencode/opencode.json. Each slot is a separate local-type MCP using the mcp-remote bridge:

```json
"mcp": {
  "jadx": { "...": "..." },
  "ida": { "...": "..." },
  "android-re": { "...": "..." },

  "unidbg-1": {
    "type": "local",
    "command": ["npx", "-y", "mcp-remote", "http://127.0.0.1:9239/sse"],
    "enabled": true,
    "timeout": 60000
  },
  "unidbg-2": {
    "type": "local",
    "command": ["npx", "-y", "mcp-remote", "http://127.0.0.1:9240/sse"],
    "enabled": true,
    "timeout": 60000
  }
}
```

The mcp-remote bridge is required — OpenCode's native type: "remote" SSE transport connects but silently fails to register tools (issue #9425). Wrapping unidbg's SSE endpoint in a stdio proxy bypasses the bug entirely.

Pre-adding slots 2, 3, etc. is safe: unstarted slots show as disconnected in mcp list but do not affect running ones.

OpenCode must be fully restarted (new process, new session) after editing opencode.json — MCP config is read at session start.

---

Step 9 — Verify end-to-end

```bash
opencode mcp list
```

Expected:

```
●  ✓ jadx connected
●  ✓ ida connected
●  ✓ android-re connected
●  ✓ unidbg-1 connected
│      npx -y mcp-remote http://127.0.0.1:9239/sse
●  ○ unidbg-2 disconnected
```

In a new OpenCode session, ask:

"Use the unidbg tools to list loaded modules."

The AI should call unidbg_list_modules and return a table showing libb794.so at 0x12000000 plus the standard Android runtime libraries (libc.so, libdl.so, libstdc++.so, libm.so, liblog.so, libc++.so). That confirms the module is loaded and visible to the MCP tools.

---

Adding more slots

```bash
# Edit the target for a new slot
cat > ~/.config/unidbg/slot-3.conf <<'EOF'
UNIDBG_PORT=9241
UNIDBG_LIB=/home/dragon/targets/libtarget3.so
EOF

# Start it
systemctl --user enable --now unidbg-mcp@3.service

# Add to opencode.json
# "unidbg-3": { "type": "local", "command": ["npx","-y","mcp-remote","http://127.0.0.1:9241/sse"], "enabled": true, "timeout": 60000 }
```

Each slot is fully independent — restarting one does not affect others.

---

Operations Reference

Task Command
View live logs journalctl --user -u unidbg-mcp@1.service -f
Restart a slot systemctl --user restart unidbg-mcp@1.service
Stop a slot systemctl --user stop unidbg-mcp@1.service
Start a slot systemctl --user start unidbg-mcp@1.service
Disable autostart systemctl --user disable --now unidbg-mcp@1.service
Check port ss -ltnp \| grep 9239
List all slots systemctl --user list-units 'unidbg-mcp@*' --all
Clear failed state systemctl --user reset-failed unidbg-mcp@1.service
Verify Java systemctl --user show unidbg-mcp@1.service \| grep -i environment
Rebuild after code change cd ~/unidbg/unidbg-android && ../mvnw test-compile -Dmaven.test.skip=false && systemctl --user restart unidbg-mcp@1.service
Change slot library Edit ~/.config/unidbg/slot-N.conf then systemctl --user restart unidbg-mcp@N.service

---

Notes

· Java 11+ is mandatory. unidbg's ELF loader calls ByteBuffer.position(int) with a covariant return type — this method does not exist in Java 8. The Maven exec plugin misreports this as The specified mainClass doesn't contain a main method with appropriate signature. If you see that error, verify JAVA_HOME in the systemd drop-in points to a real JDK 11+ directory.
· mcp-remote is mandatory. OpenCode's remote SSE transport shows "connected" but does not register tools (issue #9425). The npx mcp-remote bridge converts SSE to stdio, which OpenCode handles reliably.
· Never run exec:java manually while a slot is active on the same port — you will get Address already in use. Stop the slot first, or use a different port.
· Each slot uses its own library base address — unidbg picks per-run. Cross-reference address discussion with the specific slot's journal output rather than assuming a fixed base.
· Emulator state does not persist across slot restarts. Changing slot-N.conf and restarting drops all loaded state (breakpoints, allocations, execution history). This is expected — restart is the mechanism for switching targets.
· The unidbg-android module does not ship a standalone java -jar — the MCP server is only reachable through McpServer (the class you wrote in Step 2) or the console path via debugger.run(...). The parameterized polling loop in Step 2 is what makes the service persistent and multi-target.

---

Files and Locations Summary

Path Purpose
~/unidbg/ Cloned repository
~/unidbg/unidbg-android/src/test/java/McpServer.java Parameterized runner class
~/.config/systemd/user/unidbg-mcp@.service Systemd template unit
~/.config/systemd/user/unidbg-mcp@.service.d/override.conf Java 21 override
~/.config/unidbg/slot-1.conf Slot 1: port + library
~/.config/unidbg/slot-2.conf Slot 2: port + library
~/.config/opencode/opencode.json OpenCode MCP client config