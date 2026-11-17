# android-unidbg-mcp

A [Model Context Protocol](https://modelcontextprotocol.io) (MCP) server for
[unidbg](https://github.com/zhkl0228/unidbg) that exposes the unidbg Android
emulator to AI agents as native-debugging tools. From an agent in OpenCode (or
any MCP client) you can load a native library, set breakpoints, single-step,
trace, patch instructions, call functions, and inspect memory and registers
interactively.

Everything needed lives in this repo — one Maven build produces a runnable fat
jar, with no unidbg checkout or overlay copying required:

```bash
git clone https://github.com/FlyPhantomFly/android-unidbg-mcp.git
cd android-unidbg-mcp
./mvnw -B -DskipTests package          # first run pulls unidbg artifacts from JitPack
java -jar target/unidbg-mcp.jar        # boots the emulator, serves MCP on http://localhost:9239/sse
```

Compared against the source-built deployment: the same target library
(`libb794.so`) loads at the same base address (`0x12000000`) either way.

```
OpenCode session
  └─ npx mcp-remote (stdio ⇄ SSE bridge, spawned per session)
       └─ http://127.0.0.1:9239/sse
            └─ unidbg McpServer (systemd user service `unidbg-mcp@1`, Java 21)
                 └─ libb794.so @ 0x12000000 (Dynarmic backend)
```

---

## Relationship to upstream unidbg

- The MCP server layer — `com.github.unidbg.mcp` (`McpServer`, `McpTools`,
  `McpSession`, `MergedInputStream`) — already lives in upstream unidbg. The
  upstream maintainer added it in March 2026; it is present in the `v0.9.9`
  tag and at master `e0d88a9`, and the compiled classes ship inside the
  published JitPack artifacts.
- What this repo adds on top:
  - a standalone Maven build: a fat jar you can run with `java -jar`, with
    dependencies pinned to upstream master commit `e0d88a9` via JitPack
  - a parameterized runner (`src/main/java/McpServer.java`): configuration
    from CLI args or the `UNIDBG_LIB`/`UNIDBG_PORT` environment variables,
    automatic target-library loading with `JNI_OnLoad`, and the
    queued-tool-call polling loop that makes a persistent service possible
  - the multi-slot systemd deployment and the OpenCode integration guide
- The vendored MCP sources (`src/main/java/com/github/unidbg/mcp/`) are
  identical to upstream at commit `e0d88a9` and replace the artifact copies
  at build time (see the shade filter in `pom.xml`), so local MCP changes
  don't require patching upstream.
- That also means there is nothing to PR upstream for the MCP layer itself —
  it is already there. If you do want to contribute, the runner and the
  deployment guide are the natural candidates, ideally packaged as a small
  `unidbg-mcp` module or example; check the open PRs and issues for overlap
  first.

---

## Repo layout

```
src/main/java/com/github/unidbg/mcp/   MCP server: McpServer.java (HTTP/SSE + dispatch),
                                       McpTools.java (all 41 tools), McpSession.java,
                                       MergedInputStream.java   [vendored from upstream]
src/main/java/McpServer.java           Runner: config resolution, emulator boot, target
                                       .so load, MCP server start, tool-call polling loop
pom.xml                                Standalone build: JitPack deps pinned to upstream
                                       commit e0d88a9, shaded fat jar (Main-Class: McpServer)
mvnw, .mvn/                            Maven wrapper (3.9.9) — no Maven install needed
systemd/unidbg-mcp@.service            systemd user template unit (standalone fat-jar mode)
systemd/overlay-mode/unidbg-mcp@.service  Alternative unit (mvnw exec:java inside an unidbg
                                       source checkout — the originally deployed variant)
systemd/override.conf                  Drop-in pinning Java 21 for the service
slots/slot-1.conf, slots/slot-2.conf   Per-slot config: port + target library path
docs/unidbg-mcp-setup.md               Full annotated walkthrough (source-overlay path)
LICENSE                                Apache-2.0, inherited from vendored upstream code
```

The MCP protocol layer is implemented natively on top of the JDK's
`com.sun.net.httpserver` — plain SSE and JSON-RPC, no MCP SDK. The only
dependencies are the ones upstream already declares (fastjson, commons-io,
commons-codec, commons-collections4, slf4j, unicorn, capstone, keystone,
demumble). They are pinned explicitly in `pom.xml` because JitPack generates
invalid POMs for these modules, which silently drops their transitive
dependencies during resolution.

---

## Tool catalog (41 tools)

All tools are synchronous wrappers around a shared emulator. Call
`check_connection` first — it reports architecture, backend, state, and module
count. All addresses are hex strings (`"0x12000000"`); sizes are integers.

This is the toolset the server exposes for an Android/ARM64 emulator. Running
an iOS emulator additionally surfaces ObjC introspection tools
(`dump_objc_class`, `dump_gpb_protobuf`), which call the Objective-C runtime
and will modify registers and stack — see the operational notes below.

### Status & runtime

| Tool | Parameters | Description |
|---|---|---|
| `check_connection` | — | Emulator status: architecture, backend, mode, state, module count. **Call first.** |
| `poll_events` | `timeout_ms` (default 10000, 0 = no wait) | Poll runtime events: `breakpoint_hit`, `execution_completed`, `trace_code`/`trace_read`/`trace_write`. **Call after every execution tool.** |
| `get_threads` | — | List all threads/tasks with IDs and status |

### Modules & symbols

| Tool | Parameters | Description |
|---|---|---|
| `list_modules` | `filter` (optional, case-insensitive) | Loaded modules with name, base, size |
| `get_module_info` | `module_name` | Details: base, size, exports count, dependencies |
| `list_exports` | `module_name`, `filter` (optional) | Exported symbols with addresses and demangled names |
| `find_symbol` | `module_name`, `symbol_name`, `address` (all optional) | Symbol by name in module, or nearest symbol to an address |

`find_symbol` only sees `.dynsym`/exports. For stripped binaries compute
`address = module_base + IDA_offset` and use `add_breakpoint_by_offset` instead.

### Memory map & allocation

| Tool | Parameters | Description |
|---|---|---|
| `list_memory_map` | — | All mapped regions with base, size, permissions |
| `allocate_memory` | `size`, `data` (hex bytes to fill), `runtime` (true = mmap, false = malloc; auto-selected) | Allocate RW memory. Prefer `malloc` while stopped, `mmap` while running (page-aligned) |
| `free_memory` | `address` | Free memory allocated via `allocate_memory` |
| `list_allocations` | — | Active allocations from `allocate_memory` |

### Memory read / write / search

| Tool | Parameters | Description |
|---|---|---|
| `read_memory` | `address`, `size` (default 0x70) | Hex dump of memory |
| `write_memory` | `address`, `hex_bytes` | Write hex-encoded bytes |
| `read_string` | `address`, `max_length` (default 256) | Null-terminated C string (UTF-8) |
| `read_std_string` | `address` | C++ `std::string` (libc++ layout, auto-detects SSO/heap) |
| `read_typed` | `address`, `type`, `count` (default 1) | Typed values: `int8/16/32/64`, `uint8/16/32/64`, `float`, `double`, `pointer` |
| `read_pointer` | `address`, `depth` (default 1), `offset` (per-deref, default 0) | Read pointer(s), optionally follow chains (isa, vtable, …) |
| `search_memory` | `pattern`, `type` (`hex` default / `string`), `module_name`, `start`, `end`, `scope` (`stack`/`heap`), `permission` (`read`/`write`/`execute`, default `write` for heap), `max_results` (default 50) | Search for hex bytes with `??` wildcards, or text |

### Registers & callstack

| Tool | Parameters | Description |
|---|---|---|
| `get_registers` | — | All general-purpose registers |
| `get_register` | `name` (`X0`, `R0`, `SP`, `PC`, `LR`, …) | One register by name |
| `set_register` | `name`, `value` (hex) | Write a register |
| `get_callstack` | — | Backtrace: PC, module, offset, nearest symbol |

### Execution & stepping

Requires `isRunning=false`. Arguments passed as an array of strings:

| Format | Meaning |
|---|---|
| `"0x1234"` or `"1234"` | **Both parsed as hex.** `"128"` = 0x128 = 296 decimal |
| `"s:hello world"` | C string, auto-allocated |
| `"b:48656c6c6f"` | Byte array (hex-encoded), auto-allocated |
| `"null"` | NULL pointer |

| Tool | Parameters | Description |
|---|---|---|
| `call_function` | `address`, `args[]`, `preview_size` (default 64 bytes, 0 = off) | Call native function; returns result plus hex preview at returned pointer. Traces set beforehand stay active |
| `call_symbol` | `module_name`, `symbol_name`, `args[]`, `preview_size` | Same, resolving the address via module + exported symbol |
| `continue_execution` | — | Resume from current PC |
| `step_into` | `count` (default 1) | Execute N instructions then stop |
| `step_over` | — | Step over the current instruction (skip calls) |
| `step_out` | — | Run until current function returns (breakpoint at LR) |
| `next_block` | — | Break at next basic-block start. **Unicorn only** |
| `step_until_mnemonic` | `mnemonic` (`bl`, `ret`, … lowercase) | Break when the mnemonic executes. **Unicorn only** |

### Breakpoints

| Tool | Parameters | Description |
|---|---|---|
| `add_breakpoint` | `address`, `temporary` (auto-remove after first hit, default false) | Breakpoint at absolute address |
| `add_breakpoint_by_offset` | `module_name`, `offset`, `temporary` | Breakpoint at module base + offset — use IDA/Ghidra offsets here |
| `add_breakpoint_by_symbol` | `module_name`, `symbol_name`, `temporary` | Breakpoint at an exported symbol — **preferred** over `find_symbol` + `add_breakpoint` |
| `remove_breakpoint` | `address` | Remove breakpoint |
| `list_breakpoints` | — | All breakpoints with address, module info, disassembly |

### Tracing

| Tool | Parameters | Description |
|---|---|---|
| `trace_code` | `begin`, `end`, `break_on` (optional hex PC to pause on) | Trace instruction execution in range. Events include `regs_read` (before) and `prev_write` (after previous instruction) for data-flow tracking |
| `trace_read` | `begin`, `end`, `break_on` | Trace memory reads; events via `poll_events`; auto-removed on breakpoint/step/finish |
| `trace_write` | `begin`, `end`, `break_on` | Trace memory writes; same delivery and cleanup rules |

### Assembly & patching

| Tool | Parameters | Description |
|---|---|---|
| `disassemble` | `address`, `count` (default 10) | Disassemble; branch targets annotated with symbol names |
| `assemble` | `assembly`, `address` (default 0, for PC-relative encoding) | Assemble instruction text to machine-code hex — does **not** write memory |
| `patch` | `address`, `assembly` | Assemble and write the result to memory |

---

## Example tool output

```
check_connection
  Android64 ARM64 | DynarmicBackend64 (MINIMAL)
  unidbg | bp_debug idle=true running=false bp=0 events=0
  7 modules

list_modules
  7 modules:
  libb794.so 0x12000000 0x17c000
  libc.so    0x12180000 0xf0000
  libdl.so   0x12270000 0x20000
  libstdc++.so 0x12290000 0x20000
  libm.so    0x122b0000 0x50000
  liblog.so  0x12300000 0x20000
  libc++.so  0x12320000 0x100000

list_exports { module_name: "libb794.so", filter: "JNI" }
  Showing 1 of 2 symbols (filter: 'JNI')
  +0xe94e0 JNI_OnLoad

list_memory_map (excerpt)
  7 modules, 5 anonymous:
  libb794.so 0x12000000-0x1217c000 0x17c000
  ...
  0x12480000-0x124c0000 0x40000 rw-

find_symbol { module_name: "libc.so", symbol_name: "dlopen" }
  Symbol 'dlopen' not found in libc.so
```

A typical workflow: `check_connection` → `list_modules` / `list_exports` →
`add_breakpoint_by_symbol` → `call_symbol` → `poll_events` (breakpoint hit) →
`get_registers` / `read_memory` → `step_into` / `step_until_mnemonic` →
`patch` to modify behavior → `continue_execution`.

---

## Emulator state & execution model

Two state flags govern which tools work:

| State | Meaning | Available tools |
|---|---|---|
| `debug_idle=true` | Paused, debugger ready | Everything |
| `debug_idle=false`, `isRunning=true` | Emulator executing | Execution tools + `poll_events` only |
| Breakpoint debug mode | Paused at a breakpoint | Everything |
| Custom-tool mode (`DebugRunnable`) | Repeatable scripted execution | Set breakpoints/traces **before** the custom tool call, then `poll_events` |

Execution tools (`call_function`, `call_symbol`, `continue_execution`, steps)
return immediately — **always `poll_events` afterwards** to collect
`breakpoint_hit` / `execution_completed` / trace events.

### Backend capability matrix

| Backend | Capabilities |
|---|---|
| Unicorn / Unicorn2 | **FULL** — all tools |
| Hypervisor (Apple) | **PARTIAL** — limited HW breakpoints, 1 code trace, no `next_block`/`step_until_mnemonic` |
| Dynarmic / KVM | **MINIMAL** — breakpoints only, no trace/step |

The deployed slot 1 uses **Dynarmic**, chosen because it avoids unidbg's native
`unicorn_java` JNI lookup on Linux x86_64 hosts. Stepping/tracing tools are
limited there; use breakpoints + `call_*` + `poll_events` as the primary loop.

### Operational notes

- **Issue tool calls sequentially.** The single-slot server services one queued
  operation at a time; firing several concurrently can cross or drop responses.
- Execution tools require `isRunning=false` (use after a breakpoint hit, not
  mid-run).
- Emulator state does **not** survive slot restarts — restarting is the
  mechanism for switching targets.
- Never run a second instance on the same port (`Address already in use`).
  Stop the slot first or pick another port.
- On iOS targets, `dump_objc_class`/`dump_gpb_protobuf` call the ObjC runtime
  and will modify registers/stack.

---

## Setup A — standalone fat jar (recommended)

### Prerequisites

- Java **11+** (deployed on Java 21). Java 8 breaks unidbg's ELF loader
  (`Bytecode.position(int)` NoSuchMethodError). The `mvnw` wrapper also picks
  up `JAVA_HOME` — if your default `java` is old, prefix builds with
  `JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64`.
- Node.js with `npx` (for the `mcp-remote` bridge), systemd (user session),
  OpenCode.

### 1. Build

```bash
cd ~/github/android-unidbg-mcp
JAVA_HOME=/usr/lib/jvm/java-21-openjdk-amd64 ./mvnw -B -DskipTests package
# → target/unidbg-mcp.jar
```

The first run downloads unidbg from JitPack. The dependency set is pinned to
upstream commit `e0d88a9`, so every build pulls the same upstream binaries.
Smoke-test it:

```bash
UNIDBG_PORT=9299 UNIDBG_LIB=/path/to/target.so timeout 40 java -jar target/unidbg-mcp.jar
# expect: [McpServer] loaded /path/to/target.so at 0x... (size 0x...)
#         [McpServer] listening on http://localhost:9299/sse
```

### 2. systemd unit

```bash
mkdir -p ~/.config/systemd/user/unidbg-mcp@.service.d
cp systemd/unidbg-mcp@.service ~/.config/systemd/user/
cp systemd/override.conf ~/.config/systemd/user/unidbg-mcp@.service.d/
```

The unit runs `java -jar
%h/github/android-unidbg-mcp/target/unidbg-mcp.jar`, waits up to 10 s for the
port to be free, and restarts forever (`Restart=always` +
`StartLimitIntervalSec=0`, which lives in `[Unit]`). The override pins
`JAVA_HOME`/`PATH` to Java 21 — edit the paths if your JDK differs
(`readlink -f $(which java)` minus `/bin/java`).

### 3. Slot configs

```bash
mkdir -p ~/.config/unidbg
cp slots/slot-*.conf ~/.config/unidbg/
```

```ini
# ~/.config/unidbg/slot-1.conf
UNIDBG_PORT=9239
UNIDBG_LIB=/home/dragon/targets/libb794.so

# ~/.config/unidbg/slot-2.conf
UNIDBG_PORT=9240
UNIDBG_LIB=/home/dragon/targets/libc0ffee.so
```

Adjust the library paths; an empty `UNIDBG_LIB=` starts the slot with an empty
emulator.

### 4. Start

```bash
systemctl --user daemon-reload
systemctl --user enable --now unidbg-mcp@1.service
loginctl enable-linger $USER      # start at boot, survive logout
```

Verify: `systemctl --user status unidbg-mcp@1.service`, `ss -ltnp | grep 9239`,
and `journalctl --user -u unidbg-mcp@1.service -n 20` — expect
`[McpServer] loaded … at 0x…` (unidbg picks the base per run) and
`listening on http://localhost:9239/sse`.

### 5. OpenCode client config

Add to the `mcp` block of `~/.config/opencode/opencode.json` — **one entry per
slot**, wrapped in the `mcp-remote` stdio bridge:

```json
"unidbg": {
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
```

`mcp-remote` is mandatory: OpenCode's native `type: "remote"` SSE transport
connects but silently fails to register tools (opencode issue #9425). Fully
restart OpenCode after editing the config — MCP entries are read at session
start. Unstarted slots simply show as disconnected.

### 6. Verify end-to-end

```bash
opencode mcp list
```

In a session, ask the agent to `check_connection` and `list_modules` — it
should report the target library plus the Android runtime libraries
(`libc.so`, `libdl.so`, `libstdc++.so`, `libm.so`, `liblog.so`, `libc++.so`).

### Adding slots

Create `~/.config/unidbg/slot-N.conf` with a fresh port + library, run
`systemctl --user enable --now unidbg-mcp@N.service`, and add the matching
`mcp-remote` entry to `opencode.json`. Slots are fully independent; restarting
one never affects the others.

---

## Setup B — source overlay (alternative)

Use this when the MCP has to run against a locally-built unidbg source tree
(say, `0.9.10-SNAPSHOT` with local patches) instead of the pinned JitPack
artifacts. This is how the currently deployed slot 1 runs.

1. Clone unidbg and build: `git clone https://github.com/zhkl0228/unidbg.git
   ~/unidbg && cd ~/unidbg && ./mvnw install -DskipTests -Dgpg.skip=true`
2. Copy the sources from this repo into the same relative paths:
   ```bash
   cp -r src/main/java/com/github/unidbg/mcp ~/unidbg/unidbg-api/src/main/java/com/github/unidbg/
   cp src/main/java/McpServer.java           ~/unidbg/unidbg-android/src/test/java/
   ```
3. Compile: `cd ~/unidbg/unidbg-android && ../mvnw test-compile -Dmaven.test.skip=false`
4. Install the overlay-mode unit instead:
   `cp systemd/overlay-mode/unidbg-mcp@.service ~/.config/systemd/user/`
   (it runs `mvnw exec:java` with `WorkingDirectory=%h/unidbg/unidbg-android`)
5. Continue with steps 3–6 above (slot configs, start, OpenCode config, verify).

`docs/unidbg-mcp-setup.md` is the original annotated walkthrough of this path.

---

## Operations reference

| Task | Command |
|---|---|
| Live logs | `journalctl --user -u unidbg-mcp@1.service -f` |
| Start / stop / restart a slot | `systemctl --user start\|stop\|restart unidbg-mcp@N.service` |
| Disable autostart | `systemctl --user disable --now unidbg-mcp@N.service` |
| List all slots | `systemctl --user list-units 'unidbg-mcp@*' --all` |
| Check port | `ss -ltnp \| grep 9239` |
| Clear failed state | `systemctl --user reset-failed unidbg-mcp@N.service` |
| Verify Java for the service | `systemctl --user show unidbg-mcp@N.service \| grep -i environment` |
| Rebuild (standalone) | `cd ~/github/android-unidbg-mcp && JAVA_HOME=<jdk21> ./mvnw -B -DskipTests package && systemctl --user restart unidbg-mcp@N.service` |
| Rebuild (overlay) | copy updated files into the unidbg checkout, `cd ~/unidbg/unidbg-android && ../mvnw test-compile -Dmaven.test.skip=false && systemctl --user restart unidbg-mcp@N.service` |
| Change slot target | edit `~/.config/unidbg/slot-N.conf`, then restart the slot |

> Current deployment: slot 1 (port 9239, `libb794.so`) runs in overlay mode
> from `~/unidbg/unidbg-android`. The standalone jar built from this repo
> behaves identically (verified: same load base and size for `libb794.so`);
> moving a slot over to it is just a matter of installing the unit from
> `systemd/unidbg-mcp@.service` instead.

---

## Credits

- Emulator core and the `com.github.unidbg.mcp` MCP server layer:
  [zhkl0228/unidbg](https://github.com/zhkl0228/unidbg), Apache-2.0 (see
  `LICENSE`). The vendored sources here are unmodified upstream code at
  commit `e0d88a9`.
- Parameterized runner, standalone build/deployment, multi-slot systemd
  setup, and documentation: this project.
