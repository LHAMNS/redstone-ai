"""Multi-level information density formatters for AI context management.

Level 1 (~50 tokens): Pass/fail summary with final IO values.
Level 2 (~200 tokens): ASCII timing diagram.
Level 3 (~500 tokens): Tick-by-tick detail.

AI should always start with Level 1 and only escalate if issues are found.
"""


def format_level1(result: dict) -> str:
    """Format a simulation result as Level 1 summary."""
    summary = result.get("summary", "")
    if summary:
        return summary
    # Fallback: construct from raw data
    parts = []
    if "stepped" in result:
        parts.append(f"Stepped: {result['stepped']} ticks")
    if "virtualTick" in result:
        parts.append(f"VTick: {result['virtualTick']}")
    return " | ".join(parts) if parts else "No data"


def format_level2(result: dict) -> str:
    """Format timing diagram from Level 2 result."""
    return result.get("timing", "No timing data")


def format_level3(result: dict) -> str:
    """Format tick-by-tick detail from Level 3 result."""
    return result.get("detail", "No detail data")
