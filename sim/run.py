"""
M1 experiments and CLI.

Runs the mesh simulation across the three routers and four adoption rates,
plus two sensitivity sweeps (courier fraction = the "density cliff" ring
problem; fixed-relay placement). Emits CSV files and a text summary.

Usage:
    python3 -m sim.run               # full experiment
    python3 -m sim.run --quick       # small smoke run
    python3 -m sim.run --trials 6 --out results
"""

from __future__ import annotations

import csv
import math
import os
import random
import statistics
import sys
import time

from . import routers
from .engine import Config, World


# --- Energy model (documented, order-of-magnitude; not measured) ------------

SCAN_CURRENT_MA = 30.0        # BLE scan current while active
DUTY = 0.20                   # representative scan duty (battery > 60%, §18.3)
SCAN_MAH_24H = DUTY * SCAN_CURRENT_MA * 24.0
BLE_TX_MAH_PER_MB = 0.07      # ~10 mA at ~40 KB/s effective
WIFI_TX_MAH_PER_MB = 0.014    # ~250 mA at ~5 MB/s


def _percentile(sorted_vals, p):
    if not sorted_vals:
        return float("nan")
    k = (len(sorted_vals) - 1) * p
    f = int(math.floor(k))
    c = int(math.ceil(k))
    if f == c:
        return sorted_vals[f]
    return sorted_vals[f] * (c - k) + sorted_vals[c] * (k - f)


def run_trial(cfg: Config, policy: str) -> dict:
    rng = random.Random(cfg.seed)
    world = World(cfg, rng)
    world.policy = policy
    n_ticks = int(cfg.t_max / cfg.dt)
    for _ in range(n_ticks):
        if (len(world.escaped["manifest"]) >= cfg.n_bundles
                and len(world.escaped["proxy"]) >= cfg.n_bundles):
            break
        world.step(routers.exchange)

    escaped_m = world.escaped["manifest"]
    escaped_p = world.escaped["proxy"]
    manifest_times = sorted(escaped_m.values())
    proxy_times = sorted(escaped_p.values())
    total_bytes = sum(n.bytes_sent for n in world.nodes)
    proxy_bytes = sum(n.bytes_proxy for n in world.nodes)
    manifest_bytes = total_bytes - proxy_bytes
    n_active = max(1, len([n for n in world.nodes if n.adopted]))
    return {
        "escaped_manifest": len(escaped_m),
        "escaped_proxy": len(escaped_p),
        "manifest_times": manifest_times,
        "proxy_times": proxy_times,
        "total_bytes": total_bytes,
        "manifest_bytes": manifest_bytes,
        "proxy_bytes": proxy_bytes,
        "bytes_per_node": total_bytes / n_active,
        "manifest_bytes_per_node": manifest_bytes / n_active,
        "proxy_bytes_per_node": proxy_bytes / n_active,
    }


def _aggregate(trials: list[dict], n_bundles: int) -> dict:
    n = len(trials)
    def mean(f):
        return sum(f(t) for t in trials) / n

    all_mt = [t for tr in trials for t in tr["manifest_times"]]
    all_pt = [t for tr in trials for t in tr["proxy_times"]]
    all_mt.sort()
    all_pt.sort()
    bytes_per_node = mean(lambda t: t["bytes_per_node"])
    manifest_bytes_per_node = mean(lambda t: t["manifest_bytes_per_node"])
    proxy_bytes_per_node = mean(lambda t: t["proxy_bytes_per_node"])
    # energy: scan is constant (duty-cycled); tx scales with bytes
    # (manifest over BLE, proxy over Wi-Fi).
    energy = (SCAN_MAH_24H
              + manifest_bytes_per_node / 1e6 * BLE_TX_MAH_PER_MB
              + proxy_bytes_per_node / 1e6 * WIFI_TX_MAH_PER_MB)
    return {
        "escape_rate_manifest": mean(lambda t: t["escaped_manifest"]) / n_bundles,
        "escape_rate_proxy": mean(lambda t: t["escaped_proxy"]) / n_bundles,
        "p50_manifest": _percentile(all_mt, 0.50),
        "p90_manifest": _percentile(all_mt, 0.90),
        "p50_proxy": _percentile(all_pt, 0.50),
        "p90_proxy": _percentile(all_pt, 0.90),
        "bytes_per_node": bytes_per_node,
        "energy_mah_24h": energy,
    }


def main_experiment(cfg: Config, trials: int, out_dir: str):
    rows = []
    for policy in routers.ROUTERS:
        for adoption in (0.05, 0.10, 0.20, 0.40):
            c = Config(**{**cfg.__dict__, "adoption": adoption})
            tr = []
            for i in range(trials):
                c.seed = 1000 + trials * adoption * 100 + i
                tr.append(run_trial(c, policy))
            agg = _aggregate(tr, cfg.n_bundles)
            rows.append({"router": policy, "adoption": adoption, **agg})
            print(f"  {policy:18s} adoption={adoption:.0%}  "
                  f"manifest={agg['escape_rate_manifest']:.0%}  "
                  f"proxy={agg['escape_rate_proxy']:.0%}  "
                  f"p90_manifest={agg['p90_manifest']/60:.0f}min")
    _write_csv(out_dir, "escape_vs_adoption.csv", rows,
               ["router", "adoption", "escape_rate_manifest", "escape_rate_proxy",
                "p50_manifest", "p90_manifest", "p50_proxy", "p90_proxy",
                "bytes_per_node", "energy_mah_24h"])
    return rows


def courier_sensitivity(cfg: Config, trials: int, out_dir: str):
    """The 'density cliff': with no couriers the swarm cannot cross the ring."""
    rows = []
    for policy in routers.ROUTERS:
        for cf in (0.0, 0.006, 0.012, 0.024, 0.05):
            c = Config(**{**cfg.__dict__, "courier_fraction": cf, "adoption": 0.20})
            tr = []
            for i in range(trials):
                c.seed = 2000 + i
                tr.append(run_trial(c, policy))
            agg = _aggregate(tr, cfg.n_bundles)
            rows.append({"router": policy, "courier_fraction": cf,
                         "escape_rate_manifest": agg["escape_rate_manifest"],
                         "escape_rate_proxy": agg["escape_rate_proxy"]})
            print(f"  {policy:18s} courier={cf:.1%}  manifest={agg['escape_rate_manifest']:.0%}  "
                  f"proxy={agg['escape_rate_proxy']:.0%}")
    _write_csv(out_dir, "courier_sensitivity.csv", rows,
               ["router", "courier_fraction", "escape_rate_manifest", "escape_rate_proxy"])
    return rows


def relay_sensitivity(cfg: Config, trials: int, out_dir: str):
    """Fixed relays under a kettle (no couriers): placement decides reach.

    A relay at the cordon (just outside the crowd) is reachable by the swarm's
    own multi-hop BLE; a relay at the boundary is not (nothing bridges the empty
    ring when nobody leaves).
    """
    rows = []
    policy = routers.SPRAY_WAIT_UTILITY
    cordon = cfg.crowd_radius + 20.0
    for n_relays, radius, label in ((0, 0.0, "none"), (4, cordon, "cordon"),
                                    (8, cordon, "cordon"), (4, 1500.0, "mid-ring"),
                                    (4, 2500.0, "boundary")):
        c = Config(**{**cfg.__dict__, "n_fixed_relays": n_relays,
                      "relay_radius": radius, "adoption": 0.20,
                      "courier_fraction": 0.0})
        tr = []
        for i in range(trials):
            c.seed = 3000 + i
            tr.append(run_trial(c, policy))
        agg = _aggregate(tr, cfg.n_bundles)
        rows.append({"n_relays": n_relays, "relay_radius": radius,
                     "placement": label,
                     "escape_rate_manifest": agg["escape_rate_manifest"],
                     "escape_rate_proxy": agg["escape_rate_proxy"]})
        print(f"  relays={n_relays} @{label:<9s} ({radius:>5.0f}m)  "
              f"manifest={agg['escape_rate_manifest']:.0%}  proxy={agg['escape_rate_proxy']:.0%}")
    _write_csv(out_dir, "relay_sensitivity.csv", rows,
               ["n_relays", "relay_radius", "placement",
                "escape_rate_manifest", "escape_rate_proxy"])
    return rows


def _write_csv(out_dir: str, name: str, rows: list, fieldnames: list):
    os.makedirs(out_dir, exist_ok=True)
    path = os.path.join(out_dir, name)
    with open(path, "w", newline="") as f:
        w = csv.DictWriter(f, fieldnames=fieldnames, extrasaction="ignore")
        w.writeheader()
        for r in rows:
            w.writerow(r)
    print(f"  wrote {path}")


def run(quick=False, trials=8, out_dir="results"):
    cfg = Config()
    if quick:
        cfg.n_people = 200
        cfg.n_bundles = 10
        cfg.t_max = 1.0 * 3600
        trials = 2
    print(f"Blackout Relay M1 simulator — n_people={cfg.n_people}, "
          f"bundles={cfg.n_bundles}, window={cfg.t_max/3600:.0f}h, trials={trials}")
    print(f"energy model: scan={SCAN_MAH_24H:.0f} mAh/24h (duty {DUTY:.0%}), "
          f"BLE={BLE_TX_MAH_PER_MB} mAh/MB, Wi-Fi={WIFI_TX_MAH_PER_MB} mAh/MB")

    print("\n[1/3] Escape rate vs adoption")
    main_experiment(cfg, trials, out_dir)
    print("\n[2/3] Courier-fraction sensitivity (the ring)")
    courier_sensitivity(cfg, trials, out_dir)
    print("\n[3/3] Fixed-relay placement sensitivity")
    relay_sensitivity(cfg, trials, out_dir)

    print(f"\nDone. Results in {out_dir}/")


if __name__ == "__main__":
    args = sys.argv[1:]
    quick = "--quick" in args
    trials = 8
    out_dir = "results"
    if "--trials" in args:
        trials = int(args[args.index("--trials") + 1])
    if "--out" in args:
        out_dir = args[args.index("--out") + 1]
    t0 = time.time()
    run(quick=quick, trials=trials, out_dir=out_dir)
    print(f"elapsed {time.time() - t0:.1f}s")
