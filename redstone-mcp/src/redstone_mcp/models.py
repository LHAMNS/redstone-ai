"""Pydantic models for MCP tool parameters and responses."""

from pydantic import BaseModel, Field
from typing import Optional


class WorkspaceInfo(BaseModel):
    name: str
    size: str = ""
    frozen: bool = False
    mode: str = "ai_only"
    virtual_tick: int = 0
    io_markers: int = 0
    recording_length: Optional[int] = None
    recording_position: Optional[int] = None


class PlaceResult(BaseModel):
    placed: int
    skipped: int = 0


class StepResult(BaseModel):
    stepped: int
    virtual_tick: int
    summary: str = ""


class TestCaseResult(BaseModel):
    passed: bool = Field(alias="pass")
    inputs: dict = {}
    expected: dict = {}
    actual: dict = {}

    class Config:
        populate_by_name = True


class TestSuiteResult(BaseModel):
    total: int
    passed: int
    failed: int
    results: list[TestCaseResult] = []
