"""Error hierarchy for the RedstoneAI MCP server."""


class RedstoneError(Exception):
    """Base error for all RedstoneAI operations."""


class ConnectionError(RedstoneError):
    """WebSocket connection to the Forge mod failed or was lost."""


class WorkspaceError(RedstoneError):
    """Workspace operation failed (not found, already exists, etc.)."""


class BuildError(RedstoneError):
    """Block placement or MCR parsing failed."""


class SimulationError(RedstoneError):
    """Tick control operation failed."""


class RpcError(RedstoneError):
    """JSON-RPC protocol error from the mod."""

    def __init__(self, code: int, message: str):
        self.code = code
        super().__init__(f"RPC error {code}: {message}")
