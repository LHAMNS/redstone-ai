"""Stress test: 32x32x32 workspace, 1000-tick recording, verify timing."""

__test__ = False

import asyncio
import time

from redstone_mcp.protocol import RedstoneProtocol


async def call(protocol: RedstoneProtocol, method: str, params=None):
    return await protocol.call(method, params)


async def step_batch(protocol: RedstoneProtocol, name: str, total: int):
    """Step in batches of 200 (server maxStepsPerCall default)."""
    stepped = 0
    while stepped < total:
        batch = min(200, total - stepped)
        result = await call(protocol, "sim.step", {"workspace": name, "count": batch})
        stepped += result["stepped"]
    return stepped


async def main():
    protocol = RedstoneProtocol(timeout=60.0)
    try:
        print("Connected to RedstoneAI WebSocket")

        try:
            await call(protocol, "workspace.delete", {"name": "stress"})
        except Exception:
            pass

        t0 = time.time()
        result = await call(protocol, "workspace.create", {"name": "stress", "sizeX": 32, "sizeY": 32, "sizeZ": 32})
        print(f"[OK] Created 32x32x32 workspace in {time.time()-t0:.2f}s")

        t0 = time.time()
        mcr = "@origin 0,1,0 # # # # @row D Rn2 D D @row # # # #"
        result = await call(protocol, "build.mcr", {"workspace": "stress", "mcr": mcr})
        print(f"[OK] Built circuit: {result['placed']} blocks in {time.time()-t0:.2f}s")

        await call(protocol, "sim.freeze", {"workspace": "stress"})
        print("[OK] Frozen")

        t0 = time.time()
        total_stepped = await step_batch(protocol, "stress", 1000)
        elapsed = time.time() - t0
        print(f"[OK] Stepped {total_stepped} ticks in {elapsed:.2f}s ({total_stepped/elapsed:.0f} ticks/sec)")

        result = await call(protocol, "sim.summary", {"workspace": "stress"})
        summary = result["summary"]
        print(f"[OK] Level 1: {summary.splitlines()[0]}")

        t0 = time.time()
        await call(protocol, "sim.timing", {"workspace": "stress", "from": 990, "to": 999})
        print(f"[OK] Level 2 timing in {time.time()-t0:.3f}s")

        t0 = time.time()
        await call(protocol, "sim.detail", {"workspace": "stress", "from": 995, "to": 999})
        print(f"[OK] Level 3 detail in {time.time()-t0:.3f}s")

        t0 = time.time()
        result = await call(protocol, "sim.rewind", {"workspace": "stress", "count": 200})
        print(f"[OK] Rewound 200 ticks in {time.time()-t0:.2f}s -> vtick={result['virtualTick']}")

        t0 = time.time()
        result = await call(protocol, "sim.ff", {"workspace": "stress", "count": 200})
        vtick = result.get("virtualTick", "?")
        print(f"[OK] Fast-forwarded 200 in {time.time()-t0:.2f}s -> vtick={vtick}")

        result = await call(protocol, "workspace.info", {"name": "stress"})
        print(f"[OK] Workspace info: recording={result.get('recordingLength')} ticks, "
              f"position={result.get('recordingPosition')}")

        await call(protocol, "workspace.delete", {"name": "stress"})
        print("\n=== Stress test PASSED ===")
    finally:
        await protocol.disconnect()


if __name__ == "__main__":
    asyncio.run(main())
