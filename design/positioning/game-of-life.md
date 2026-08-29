# Multiplayer Game of Life — Reference Implementation Scope

> **v1.0-consolidation supersession note (2026-08-29).** The
> reference implementation description mentions `data:` / `state:`
> / `channel:` / `signal:` as section headers — this predates the
> 2026-08-25 v1.0 user-surface consolidation that retired top-level
> section headers on class bodies. Ratified v1.0 spellings use
> `use <name>: <T>` (capability atoms), direct fields, `|:`
> propositions, `<:` / `:<` glyphs. Authoritative current syntax:
> [../spec/spec-cxc-v1.0.md](../spec/spec-cxc-v1.0.md).

The multiplayer Game of Life is Corvid's reference implementation and empirical validation target. It demonstrates the language's runtime story, concurrency model, cross-target code sharing, and Apple framework interop simultaneously. If Corvid can build this cleanly, the design has landed. If it can't, we've learned what needs to change.

## What it is

A three-tier multiplayer Game of Life:

- **Mac client** — native AppKit application. Displays the grid, allows placement of live cells, shows other players' contributions in different colors.
- **iOS client** — native UIKit application. Same functionality as the Mac client, adapted for touch.
- **Server** — headless Foundation-based service. Hosts multiple concurrent games, orchestrates ticks, broadcasts state updates.

Players connect from any client, join a room, place cells collaboratively, then watch the automaton evolve tick-by-tick. Server enforces the simulation rules; clients render the state and accept user input.

## Why this specific example

Game of Life is universally understood as a computational example. Multiplayer Game of Life is memorable. Three-target deployment across Apple platforms is a real proof of the ecosystem story.

More importantly, this example stress-tests Corvid at concrete points that abstract discussion can't:

### Sections mechanism at runtime scale

Every message type in the protocol uses sections for parsing and validation. Cell, Grid, Room, Player — all use `data:`, `state:`, `channel:`, `signal:` sections. If sections work here they work anywhere.

### Universal extents under load

A 100×100 grid = 10,000 cells. A server hosting 100 concurrent games = 1M live cell instances. Do extents scale? Does handle validation cost too much? Does `dense_storage:` deliver the SIMD-friendly iteration the spec promises? This example forces answers.

### Contracts as coordination primitives

A room transitions through states: `.lobby → .active → .paused → .finished`. Multiple players join concurrently; the room's `add_player(self, p) requires state == .lobby` contract must serialize joins atomically. Under §1.9 this should happen for free. This example verifies it.

### Channel semantics under real load

Server broadcasts grid updates 10 times per second to every connected client. Client subscribes to updates. Buffering, backpressure, cancellation all matter. If a mobile client disconnects, the server must not accumulate unbounded pending messages. This example forces the channel design to be honest about lifecycle.

### The struct-vs-class question for Cell

10,000 cells per grid. Each cell has a position (immutable) and a state (alive/dead + owner + age). Struct-Cell means value copies, no per-instance overhead. Class-Cell means universal-extent tracking, ARC per cell. The performance difference at 10K instances is real. This example forces us to specify the right answer, then verify it.

### ObjC framework interop at depth

- **URLSession** — networking on all three targets.
- **WebSocket (via URLSession)** — real-time bidirectional communication.
- **NSJSONSerialization** — protocol message serialization.
- **AppKit** — Mac rendering.
- **UIKit** — iOS rendering.
- **NSOperationQueue / DispatchQueue** — async coordination.

Every one of these is a real interop surface. Each generates specific friction; the friction becomes design signal.

### Main-thread affinity

UI updates from network events. If a channel subscriber runs on a background thread and directly touches AppKit, we crash. The `@main_actor` marker (§8.9) has to work. This example verifies it.

## What it deliberately doesn't test

- **Windows/Linux desktop deployment** — not an Apple target.
- **Web frontends** — not an Apple target.
- **CPU-intensive workloads beyond simulation** — the CA tick is O(cells) but bounded.
- **Persistence** — server state is in-memory; game state doesn't survive restart.
- **Authentication/security** — orthogonal concern.

Adding any of these would test additional design axes but distract from the core validation. Keep the scope tight.

## The shared protocol

All three targets share a protocol layer. Corvid modules organize as follows:

```
com.corvid.gol.protocol
  ├── Message (enumeration)
  │   ├── JoinRoom(room_id: RoomID)
  │   ├── PlaceCell(x: Int, y: Int)
  │   ├── StartGame
  │   ├── TickUpdate(cells: [CellUpdate])
  │   └── RoomState(state: RoomState, players: [PlayerID])
  ├── RoomState (enumeration)
  │   ├── lobby
  │   ├── active
  │   ├── paused
  │   └── finished
  ├── CellUpdate (struct)
  ├── RoomID, PlayerID (structs)
  └── Cell, Grid (types)
```

The protocol module is deployment-target-independent. Same module compiles for Mac, iOS, and server.

## The server

```
com.corvid.gol.server
  ├── Server (class)
  │   ├── state: rooms: Dict<RoomID, Room>
  │   └── methods: accept_connection, dispatch_message, tick_all_rooms
  ├── Room (class)
  │   ├── state: game_state, players, grid
  │   ├── invariants: game_state transitions
  │   └── channel: state_updates (broadcasts to subscribers)
  ├── Grid (class or struct — TBD by validation)
  └── Ticker (class — runs the tick loop with structured async)
```

Uses Foundation for networking. Deployed to Linux (via Foundation-Swift) or macOS.

## The Mac client

```
com.corvid.gol.mac
  ├── AppDelegate (class, @main_actor)
  ├── GridView (class, NSView subclass via ObjC interop)
  ├── ConnectionManager (class)
  │   └── channel subscription to server state_updates
  └── InputController (class)
```

Uses AppKit for rendering. URLSession for WebSocket connection. Main-actor discipline for UI updates from network callbacks.

## The iOS client

Structurally similar to the Mac client but with UIKit. Same protocol module. Same ConnectionManager pattern.

The iOS client is mostly a copy of the Mac client with different UI framework — which is exactly the point. The shared protocol module means most of the code isn't UI code; the UI-specific layer is small.

## Estimated implementation cost

- **Protocol module** — 300 lines Corvid. 1 day.
- **Server** — 800-1200 lines Corvid. 3-5 days.
- **Mac client** — 600-1000 lines Corvid (excluding AppKit glue). 3-5 days.
- **iOS client** — 400-800 lines Corvid (mostly copying Mac client). 2-3 days.
- **Total** — roughly 2100-3300 lines Corvid, 2-3 weeks of focused engineering.

Assumes Corvid compiler exists. Building the compiler is orders of magnitude more work than building this example.

## What we learn from building this

**If it works:**
- The runtime interop story is real (proven by AppKit/UIKit calls).
- The concurrency model handles real load (proven by tick-broadcast fan-out).
- Extents scale to real instance counts (proven by 10K-100K cells).
- The two-type pattern applies to real domain (proven by construction patterns).
- Cross-target code sharing is practical (proven by shared protocol module).

**If specific pieces don't work:**
- Extent scaling problems → revisit §6.4 (maybe extents are opt-in after all, contrary to current commitment).
- Channel semantics friction → revisit §8.6 (maybe Signal is enough, or Channel needs redesign).
- Interop surface issues → revisit §9 (identify which framework calls need special handling).

The example is a design verifier, not just a demo. Every friction it surfaces is data.

## Priority within the handoff

The Game of Life implementation should NOT be the first thing built. The compiler doesn't exist yet. Stage 0 bootstrap comes first (Corvid→ObjC translator), then Stage 1 (LLVM IR direct with LSP). Game of Life is a Stage 1+ validation target that becomes possible once the compiler runs.

For the design handoff, this document exists to give the next agent a concrete deployment target to design against. Every design decision should be checked against "does this help Game of Life work?" — if the answer is "no" or "unclear," the decision needs more thought.
