"""Quick WebSocket integration test through RedstoneProtocol."""

__test__ = False

import asyncio
import json

from redstone_mcp.protocol import RedstoneProtocol


async def run_tests() -> None:
    protocol = RedstoneProtocol(timeout=10.0)
    tests = [
        ("status", {}),
        ("workspace.create", {"name": "test1", "sizeX": 8, "sizeY": 4, "sizeZ": 8}),
        ("workspace.list", {}),
        ("build.mcr", {"workspace": "test1", "mcr": "@origin 0,1,0 # # # @row D Rn2 D"}),
        ("sim.freeze", {"workspace": "test1"}),
        ("sim.step", {"workspace": "test1", "count": 5}),
        ("help.mcr", {}),
        ("workspace.delete", {"name": "test1"}),
    ]
    try:
        for method, params in tests:
            result = await protocol.call(method, params or None)
            print(f"PASS {method}: {json.dumps(result)[:100]}")
    finally:
        await protocol.disconnect()


if __name__ == "__main__":
    asyncio.run(run_tests())
