#!/usr/bin/env python3
# SPDX-License-Identifier: GPL-3.0-or-later
"""uidump.py -- parse ``uiautomator dump`` XML and recognise LibreMail's screens.

Pure (no device dependency) so it can be unit-tested against the saved dumps from the
manual run. The Compose UI exposes no stable ``resource-id``s, so screens and rows are
recognised structurally -- by class, clickable flags, bounds, and descendant
text/content-desc -- exactly as observed in ``ui_mailbox.xml`` / ``ui_reader.xml``.

It also provides the keyguard / foreign-app guards the harness needs: a uiautomator sample
taken against the lockscreen (``ui-mbox.xml`` in the manual run was a keyguard capture) or
another app (``ui-03.xml`` was a deskclock alarm) must be detected and skipped.
"""

from __future__ import annotations

import re
import xml.etree.ElementTree as ET
from collections import Counter
from dataclasses import dataclass, field
from typing import Callable, List, Optional, Tuple

SYSTEMUI_PACKAGE = "com.android.systemui"
_BOUNDS_RE = re.compile(r"\[(-?\d+),(-?\d+)\]\[(-?\d+),(-?\d+)\]")


def parse_bounds(text: str) -> Optional[Tuple[int, int, int, int]]:
    """``"[x1,y1][x2,y2]"`` -> ``(x1, y1, x2, y2)`` (or ``None``)."""
    m = _BOUNDS_RE.search(text or "")
    if not m:
        return None
    return tuple(int(g) for g in m.groups())  # type: ignore[return-value]


@dataclass
class UiNode:
    """One uiautomator node with parsed attributes and parent/child links."""

    cls: str = ""
    text: str = ""
    resource_id: str = ""
    content_desc: str = ""
    package: str = ""
    clickable: bool = False
    long_clickable: bool = False
    scrollable: bool = False
    enabled: bool = False
    bounds: Optional[Tuple[int, int, int, int]] = None
    parent: Optional["UiNode"] = None
    children: List["UiNode"] = field(default_factory=list)

    @property
    def center(self) -> Optional[Tuple[int, int]]:
        if not self.bounds:
            return None
        x1, y1, x2, y2 = self.bounds
        return ((x1 + x2) // 2, (y1 + y2) // 2)

    @property
    def height(self) -> int:
        return (self.bounds[3] - self.bounds[1]) if self.bounds else 0

    def walk(self):
        """Depth-first iterate over this node and all descendants."""
        yield self
        for child in self.children:
            yield from child.walk()

    def find_all(self, predicate: Callable[["UiNode"], bool]) -> List["UiNode"]:
        return [n for n in self.walk() if predicate(n)]

    def first_clickable_ancestor(self) -> Optional["UiNode"]:
        node: Optional[UiNode] = self
        while node is not None:
            if node.clickable:
                return node
            node = node.parent
        return None

    def descendant_texts(self) -> List[str]:
        """Non-empty ``text`` values under this node, in document order."""
        return [n.text for n in self.walk() if n.text]

    def descendant_descs(self) -> List[str]:
        return [n.content_desc for n in self.walk() if n.content_desc]


def _to_bool(value: Optional[str]) -> bool:
    return value == "true"


def _build(elem: ET.Element, parent: Optional[UiNode]) -> UiNode:
    node = UiNode(
        cls=elem.get("class", ""),
        text=elem.get("text", ""),
        resource_id=elem.get("resource-id", ""),
        content_desc=elem.get("content-desc", ""),
        package=elem.get("package", ""),
        clickable=_to_bool(elem.get("clickable")),
        long_clickable=_to_bool(elem.get("long-clickable")),
        scrollable=_to_bool(elem.get("scrollable")),
        enabled=_to_bool(elem.get("enabled")),
        bounds=parse_bounds(elem.get("bounds", "")),
        parent=parent,
    )
    for child_elem in list(elem):
        if child_elem.tag == "node":
            node.children.append(_build(child_elem, node))
    return node


def parse_dump(xml_text: str) -> UiNode:
    """Parse uiautomator XML into a synthetic root :class:`UiNode` (the ``<hierarchy>``)."""
    root_elem = ET.fromstring(xml_text.strip())
    root = UiNode(cls="hierarchy")
    for child_elem in list(root_elem):
        if child_elem.tag == "node":
            root.children.append(_build(child_elem, root))
    return root


# --------------------------------------------------------------------------- #
# Screen recognition
# --------------------------------------------------------------------------- #
def foreground_package(root: UiNode) -> str:
    """The package that owns most of the tree -- a robust "what's on screen" signal."""
    counts = Counter(n.package for n in root.walk() if n.package)
    if not counts:
        return ""
    return counts.most_common(1)[0][0]


def is_lockscreen(root: UiNode) -> bool:
    """True if the dump is the keyguard/lockscreen rather than an app."""
    if foreground_package(root) != SYSTEMUI_PACKAGE:
        return False
    for node in root.walk():
        if "keyguard" in node.resource_id or node.content_desc in (
            "Lock screen",
            "Fingerprint sensor",
        ):
            return True
    return False


def is_app_foreground(root: UiNode, package: str) -> bool:
    """True if ``package`` is the foreground app in this dump."""
    return foreground_package(root) == package


def find_by_text(root: UiNode, text: str, exact: bool = True) -> Optional[UiNode]:
    for node in root.walk():
        if (node.text == text) if exact else (text in node.text):
            return node
    return None


def find_by_content_desc(root: UiNode, desc: str, exact: bool = True) -> Optional[UiNode]:
    for node in root.walk():
        if (node.content_desc == desc) if exact else (desc in node.content_desc):
            return node
    return None


def is_reader(root: UiNode, package: str) -> bool:
    """The message reader: a Back affordance plus the 'Message' title, in our package."""
    if not is_app_foreground(root, package):
        return False
    has_back = find_by_content_desc(root, "Back") is not None
    has_title = find_by_text(root, "Message") is not None
    return has_back and has_title


def has_progress_bar(root: UiNode) -> bool:
    """True if a ProgressBar is present (reader still loading its body -- a fallback signal)."""
    return any(n.cls.endswith("ProgressBar") for n in root.walk())


@dataclass
class MessageRow:
    """A tappable message row in the mailbox list."""

    index: int
    bounds: Tuple[int, int, int, int]
    center: Tuple[int, int]
    texts: List[str]
    cached: bool

    @property
    def label(self) -> str:
        """A short, log-safe identifier -- sender + subject when available."""
        if not self.texts:
            return f"row#{self.index}"
        if len(self.texts) >= 2:
            return f"{self.texts[0]} / {self.texts[-1]}"
        return self.texts[0]


def find_message_rows(root: UiNode, package: str) -> List[MessageRow]:
    """Return the tappable message rows in the mailbox's scrollable list.

    Rows are the ``clickable`` + ``long-clickable`` children of the scrollable list (as in
    ``ui_mailbox.xml``). A row carrying the ``"Available offline"`` content-desc has its
    body cached already, so it is marked ``cached`` (callers pick uncached rows for the
    uncached-open scenarios).

    Row-selection hardening: a candidate is skipped unless it carries at least one multi-char
    text label. A real message row always shows a sender/subject; a bare tappable container
    (a stray clickable spacer, a "load more"/footer affordance, an empty section row) has
    none, and tapping it would open nothing and corrupt a sample -- so it is not returned.
    """
    scrollables = root.find_all(lambda n: n.scrollable and n.package == package)
    rows: List[MessageRow] = []
    seen_bounds = set()
    for scroller in scrollables:
        for child in scroller.children:
            if not (child.clickable and child.long_clickable and child.bounds):
                continue
            if child.bounds in seen_bounds:
                continue
            seen_bounds.add(child.bounds)
            texts = child.descendant_texts()
            # Drop single-letter avatar monograms; keep sender/subject/time.
            texts = [t for t in texts if len(t) > 1]
            if not texts:
                # No sender/subject label -> not a message row; skip it (hardening).
                continue
            cached = any(d == "Available offline" for d in child.descendant_descs())
            rows.append(
                MessageRow(
                    index=len(rows),
                    bounds=child.bounds,
                    center=child.center,  # type: ignore[arg-type]
                    texts=texts,
                    cached=cached,
                )
            )
    return rows
