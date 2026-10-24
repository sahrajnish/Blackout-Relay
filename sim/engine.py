"""
Simulation engine: world geometry, node mobility, contact detection, energy
accounting. Pure Python, spatial-grid based for speed.

Model geometry (from SPEC.md §5 "honest geometry"):
  - A 5 km x 5 km square is the blackout zone. Leaving it == "uploaded".
  - The crowd is a disk in the centre. The ring between the crowd edge and the
    boundary is empty: the swarm alone cannot cross it; a person (courier) or a
    fixed relay must bridge it.
"""

from __future__ import annotations

import math
import random
from collections import deque
from dataclasses import dataclass, field

# --- Parameters (single source of truth for the experiments) ----------------

@dataclass
class Config:
    world_size: float = 5000.0          # metres, square edge
    crowd_radius: float = 750.0         # radius of the central crowd disk
    n_people: int = 500                 # people in the crowd
    courier_fraction: float = 0.012     # fraction of the crowd that leaves
    adoption: float = 0.20              # fraction running the app
    range: float = 100.0                # BLE contact range (m)
    protester_speed: float = 0.8        # m/s
    courier_speed: float = 1.4          # m/s
    dt: float = 5.0                     # seconds per tick
    t_max: float = 4 * 3600.0           # simulation window (s)
    copy_budget: dict = field(default_factory=lambda: {"manifest": 32, "proxy": 4})
    artefact_size: dict = field(default_factory=lambda: {"manifest": 2048,
                                                         "proxy": 2 * 1024 * 1024})
    margin: float = 0.15                # utility hand-off margin (§13.1)
    n_bundles: int = 40                 # capture events per trial
    n_fixed_relays: int = 0             # fixed relays at the boundary
    relay_radius: float = 0.0           # radial distance of fixed relays from centre
    utility_window_s: float = 120.0     # displacement window for the utility fn (§13.3)
    displacement_threshold: float = 100.0  # sustained directional motion, below this outward=0
    seed: int = 0

    @property
    def centre(self) -> float:
        return self.world_size / 2.0

    @property
    def n_couriers(self) -> int:
        return int(round(self.n_people * self.courier_fraction))


class Node:
    __slots__ = ("id", "x", "y", "history", "kind", "adopted", "angle",
                 "walk_angle", "holdings", "bytes_sent", "bytes_proxy", "alive")

    def __init__(self, node_id: int, x: float, y: float, kind: str, adopted: bool,
                 window: int = 24):
        self.id = node_id
        self.x = x
        self.y = y
        self.history = deque([(x, y)], maxlen=window)  # 2-min position trail
        self.kind = kind            # 'protester' | 'courier'
        self.adopted = adopted
        self.angle = 0.0            # courier heading (set by World with its RNG)
        self.walk_angle = 0.0       # protester walk (set by World with its RNG)
        self.holdings = {}          # bundle_id -> {artefact: copies}
        self.bytes_sent = 0         # total bytes forwarded (both artefacts)
        self.bytes_proxy = 0        # proxy bytes only (Wi-Fi)
        self.alive = True

    def dist2(self, other: "Node") -> float:
        dx = self.x - other.x
        dy = self.y - other.y
        return dx * dx + dy * dy


class World:
    def __init__(self, cfg: Config, rng: random.Random):
        self.cfg = cfg
        self.rng = rng
        self.tick = 0
        self.time = 0.0
        self.nodes: list[Node] = []
        self.bundles: list[dict] = []      # each: {id, origin_x, origin_y}
        self.escaped: dict = {"manifest": {}, "proxy": {}}  # bundle_id -> time
        self._grid = {}
        self._next_id = 0
        self._populate()

    # -- population ---------------------------------------------------------

    def _populate(self):
        c = self.cfg
        n_couriers = c.n_couriers
        n_protesters = c.n_people - n_couriers
        adopted_ids = set()

        # Assign adoption: couriers always adopt (they are the motivated leavers).
        for _ in range(n_protesters):
            if self.rng.random() < c.adoption:
                adopted_ids.add(self._next_id)
            self._add_protester()
        for _ in range(n_couriers):
            adopted_ids.add(self._next_id)   # couriers always carry the app
            self._add_courier()

        for n in self.nodes:
            n.adopted = n.id in adopted_ids

        # Capture bundles from random adopted protesters at t=0.
        adopted_protesters = [n for n in self.nodes
                              if n.adopted and n.kind == "protester"]
        if not adopted_protesters:
            return
        for b in range(c.n_bundles):
            creator = self.rng.choice(adopted_protesters)
            bid = b
            self.bundles.append({"id": bid, "origin_x": creator.x, "origin_y": creator.y})
            for art in ("manifest", "proxy"):
                creator.holdings.setdefault(bid, {})[art] = c.copy_budget[art]

    def _add_protester(self):
        x, y = self._crowd_point()
        n = Node(self._next_id, x, y, "protester", False, self._window_ticks())
        n.walk_angle = self.rng.uniform(0.0, 2.0 * math.pi)
        self._next_id += 1
        self.nodes.append(n)

    def _add_courier(self):
        x, y = self._crowd_point()
        n = Node(self._next_id, x, y, "courier", False, self._window_ticks())
        n.angle = self.rng.uniform(0.0, 2.0 * math.pi)
        self._next_id += 1
        self.nodes.append(n)

    def _window_ticks(self):
        return max(1, int(round(self.cfg.utility_window_s / self.cfg.dt)))

    def _crowd_point(self):
        c = self.cfg
        # Uniform in the crowd disk.
        r = c.crowd_radius * math.sqrt(self.rng.random())
        a = self.rng.uniform(0.0, 2.0 * math.pi)
        return c.centre + r * math.cos(a), c.centre + r * math.sin(a)

    # -- mobility -----------------------------------------------------------

    def step_mobility(self):
        c = self.cfg
        step_p = c.protester_speed * c.dt
        step_c = c.courier_speed * c.dt
        for n in self.nodes:
            if not n.alive:
                continue
            if n.kind == "protester":
                n.walk_angle += self.rng.uniform(-1.5, 1.5)
                n.x += step_p * math.cos(n.walk_angle)
                n.y += step_p * math.sin(n.walk_angle)
                # Reflecting boundary at the crowd edge: wanderers stay bounded,
                # so their 2-min displacement (and thus outward) stays ~0.
                dx = n.x - c.centre
                dy = n.y - c.centre
                r = math.hypot(dx, dy)
                if r > c.crowd_radius:
                    nx, ny = dx / r, dy / r
                    n.x = c.centre + nx * (2 * c.crowd_radius - r)
                    n.y = c.centre + ny * (2 * c.crowd_radius - r)
                    vx, vy = math.cos(n.walk_angle), math.sin(n.walk_angle)
                    vdotn = vx * nx + vy * ny
                    n.walk_angle = math.atan2(vy - 2 * vdotn * ny, vx - 2 * vdotn * nx)
            else:  # courier
                n.x += step_c * math.cos(n.angle)
                n.y += step_c * math.sin(n.angle)
            n.history.append((n.x, n.y))

    # -- contact detection (grid) -------------------------------------------

    def _grid_add(self, n: Node):
        c = self.cfg
        cx = int(n.x // c.range)
        cy = int(n.y // c.range)
        self._grid.setdefault((cx, cy), []).append(n)

    def _grid_neighbors(self, n: Node):
        c = self.cfg
        cx = int(n.x // c.range)
        cy = int(n.y // c.range)
        for dx in (-1, 0, 1):
            for dy in (-1, 0, 1):
                bucket = self._grid.get((cx + dx, cy + dy))
                if bucket:
                    for other in bucket:
                        if other is not n:
                            yield other

    def active_nodes(self):
        """Adopted, alive nodes that can participate in the mesh."""
        return [n for n in self.nodes if n.alive and n.adopted]

    def contacts(self):
        """Yield (a, b) pairs within range, one connection per node per tick."""
        self._grid = {}
        active = self.active_nodes()
        for n in active:
            self._grid_add(n)
        r2 = self.cfg.range ** 2
        busy = set()
        order = active[:]
        self.rng.shuffle(order)
        for n in order:
            if n.id in busy:
                continue
            # nearest unbusy neighbour within range
            best = None
            best_d2 = r2
            for other in self._grid_neighbors(n):
                if other.id in busy:
                    continue
                d2 = n.dist2(other)
                if d2 <= best_d2:
                    best = other
                    best_d2 = d2
            if best is not None:
                busy.add(n.id)
                busy.add(best.id)
                yield n, best

    # -- escape / removal ----------------------------------------------------

    def check_escape(self) -> None:
        """Nodes that crossed the boundary deliver their bundles and leave."""
        c = self.cfg
        for n in self.nodes:
            if not n.alive:
                continue
            if (n.x < 0 or n.x > c.world_size or n.y < 0 or n.y > c.world_size):
                n.alive = False
                self._deliver(n)

    def _deliver(self, n: Node):
        for bid, arts in n.holdings.items():
            for art in arts:
                if art in self.escaped and bid not in self.escaped[art]:
                    self.escaped[art][bid] = self.time

    # -- step ---------------------------------------------------------------

    def step(self, exchange_fn):
        self.tick += 1
        self.time = self.tick * self.cfg.dt
        self.step_mobility()
        for a, b in self.contacts():
            exchange_fn(self, a, b)
        self.check_escape()
        if self.cfg.n_fixed_relays:
            self._relay_scan()

    def _relay_scan(self):
        """Fixed relays capture any bundle brought within BLE range.

        Relays are placed uniformly on a circle of radius `relay_radius` around
        the centre. A relay near the crowd edge is reachable by the swarm's own
        multi-hop BLE; a relay at the boundary is reachable only by a courier.
        """
        c = self.cfg
        relays = self._relay_positions()
        r2 = c.range ** 2
        for n in self.active_nodes():
            for (gx, gy) in relays:
                if (n.x - gx) ** 2 + (n.y - gy) ** 2 <= r2:
                    for bid, arts in n.holdings.items():
                        for art in arts:
                            if bid not in self.escaped[art]:
                                self.escaped[art][bid] = self.time
                    break

    def _relay_positions(self):
        c = self.cfg
        if c.n_fixed_relays <= 0:
            return []
        return [(c.centre + c.relay_radius * math.cos(2 * math.pi * i / c.n_fixed_relays),
                 c.centre + c.relay_radius * math.sin(2 * math.pi * i / c.n_fixed_relays))
                for i in range(c.n_fixed_relays)]

    # -- utilities ----------------------------------------------------------

    def outward(self, n: Node, origin_x: float, origin_y: float) -> float:
        """Sign of dot(displacement, vector origin->peer), in [-1, 0, 1].

        Displacement is measured over the utility window (2 min, §13.3). Below a
        displacement threshold the direction is "unknown" and outward is 0 — a
        random-walk protester has ~0 net displacement, while a courier walking
        out has a large, consistent one.
        """
        ox, oy = n.history[0]
        vx = n.x - ox
        vy = n.y - oy
        if math.hypot(vx, vy) < self.cfg.displacement_threshold:
            return 0.0
        dx = n.x - origin_x
        dy = n.y - origin_y
        dot = vx * dx + vy * dy
        if dot > 0:
            return 1.0
        if dot < 0:
            return -1.0
        return 0.0

    def utility(self, n: Node, origin_x: float, origin_y: float) -> float:
        """Normative weights from SPEC.md §13.3 (battery=1.0, screen/charger=0)."""
        return 0.45 + 0.40 * self.outward(n, origin_x, origin_y)
