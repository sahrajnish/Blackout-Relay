"""
Routing policies (SPEC.md §13.1, §23.4).

Three routers are compared:
  - EPIDEMIC           : forward a copy on every contact, unbounded.
  - SPRAY_WAIT         : binary spray-and-wait; the holder of the last copy
                         waits (does not re-forward).
  - SPRAY_WAIT_UTILITY : binary spray, then the last copy is handed off only to
                         a carrier with utility > self + MARGIN (§13.1).

The "bundle" is modelled at artefact granularity with the copy budgets from
§13.1 (manifest 32, proxy 4), so the two-tier relay policy is visible.
"""

from __future__ import annotations

EPIDEMIC = "epidemic"
SPRAY_WAIT = "spray_wait"
SPRAY_WAIT_UTILITY = "spray_wait_utility"

ROUTERS = (EPIDEMIC, SPRAY_WAIT, SPRAY_WAIT_UTILITY)
ARTEFACTS = ("manifest", "proxy")


def _has(node, bid: int, art: str) -> bool:
    return node.holdings.get(bid, {}).get(art, 0) > 0


def _forward(world, src, dst, bid: int, art: str) -> None:
    """One directed exchange: src offers `art` of `bid` to dst."""
    if not _has(src, bid, art) or _has(dst, bid, art):
        return
    cfg = world.cfg
    policy = world.policy
    arts = src.holdings[bid]

    if policy == EPIDEMIC:
        dst.holdings.setdefault(bid, {})[art] = 1
        src.bytes_sent += cfg.artefact_size[art]
        if art == "proxy":
            src.bytes_proxy += cfg.artefact_size[art]
        return

    copies = arts[art]
    if copies > 1:
        give = copies // 2
        dst.holdings.setdefault(bid, {})[art] = give
        arts[art] -= give
        src.bytes_sent += give * cfg.artefact_size[art]
        if art == "proxy":
            src.bytes_proxy += give * cfg.artefact_size[art]
    elif copies == 1:
        if policy == SPRAY_WAIT:
            return  # wait: hold the last copy
        # SPRAY_WAIT_UTILITY: hand off the last copy to a better carrier.
        bundle = world.bundles[bid]
        du = world.utility(dst, bundle["origin_x"], bundle["origin_y"])
        su = world.utility(src, bundle["origin_x"], bundle["origin_y"])
        if du > su + cfg.margin:
            dst.holdings.setdefault(bid, {})[art] = 1
            arts[art] = 0
            src.bytes_sent += cfg.artefact_size[art]
            if art == "proxy":
                src.bytes_proxy += cfg.artefact_size[art]


def exchange(world, a, b) -> None:
    _half(world, a, b)
    _half(world, b, a)


def _half(world, src, dst) -> None:
    for art in ARTEFACTS:
        for bid in list(src.holdings):
            if art in src.holdings[bid]:
                _forward(world, src, dst, bid, art)
