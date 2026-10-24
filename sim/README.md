# sim — M1 mesh simulator

A dependency-free Python simulator for the Blackout Relay store-carry-forward
mesh. It produces the M1 deliverable (SPEC.md §23.4): escape-rate and
time-to-escape curves for three routers across four adoption rates, plus
sensitivity to the "density cliff" (the empty ring) and to fixed-relay
placement.

This is a model, not a measurement. Its job is to answer "does the design work
at plausible adoption levels" *before* anyone builds hardware — because the
project will not have field users (SPEC.md §23.4).

## Run

```sh
python3 -m sim.run                # full experiment -> results/*.csv
python3 -m sim.run --quick        # fast smoke run
python3 -m sim.run --trials 6 --out results
```

No dependencies beyond the Python standard library.

## Model

**Geometry (SPEC.md §5 "honest geometry").** A 5 km × 5 km square is the
blackout zone. Leaving it = "uploaded". The crowd is a disk of radius 750 m in
the centre; the ~1.75 km ring between the crowd edge and the boundary is empty.

**Nodes.** `n_people` people in the crowd. A fraction `adoption` run the app and
form the relay mesh (non-adopters occupy space but do not relay). A fraction
`courier_fraction` are *couriers* — people who leave the zone by walking
straight out at 1.4 m/s — and they always run the app (they are the motivated
leavers). The rest are *protesters* who do a reflecting random walk at 0.8 m/s
and never leave.

**Capture.** Each trial captures `n_bundles` bundles at t=0 from random adopted
protesters. Each bundle has two artefacts routed independently, with the copy
budgets from SPEC.md §13.1: manifest (32 copies, 2 KB) and proxy (4 copies,
2 MB). The thumbnail (16 copies) is omitted; its behaviour sits between the two.

**Contact.** Two adopted nodes within 100 m (BLE) can exchange, one connection
per node per tick (the §12.1 v1 limitation), dt = 5 s.

**Routers (SPEC.md §13.1, §23.4).**

| Router | Behaviour |
|---|---|
| `epidemic` | forward a copy on every contact, unbounded |
| `spray_wait` | binary spray-and-wait; the holder of the last copy waits |
| `spray_wait_utility` | binary spray, then the last copy is handed off only to a carrier with `utility > self + 0.15` |

**Utility (SPEC.md §13.3).** `0.45·battery + 0.40·outward + 0.10·screen +
0.05·charger`, with battery=1, screen/charger=0 in the sim. `outward` is the
sign of the dot product of the node's displacement over a 2-minute window with
the vector from the bundle's capture point to the node; displacement below
100 m is treated as "unknown" (`outward = 0`), which is what keeps the random
walk of a protester from reading as directional motion.

**Escape.** A bundle's artefact escapes when a node holding it crosses the
boundary (courier), or reaches a fixed relay (see below). There is no delivery
acknowledgement and no return path — escape is one-way, as in the real system.

**Fixed relays.** Placed uniformly on a circle at `relay_radius`; any adopted
node within 100 m delivers to the relay. Used only in the relay-placement
sensitivity.

**Energy.** A documented order-of-magnitude estimate, not a measurement:
scanning dominates (BLE scan ~30 mA at 20 % duty ≈ 144 mAh/24 h); transmission
is BLE (~0.07 mAh/MB) for manifests and Wi-Fi (~0.014 mAh/MB) for proxies. The
conclusion — router choice barely moves the energy needle, duty cycling does —
is robust to these constants.

## Outputs

| File | Contents |
|---|---|
| `escape_vs_adoption.csv` | 3 routers × 4 adoption rates: manifest/proxy escape rate, p50/p90 time-to-escape, bytes/node, energy |
| `courier_sensitivity.csv` | 3 routers × courier fraction (the ring: 0 couriers → 0 escape) |
| `relay_sensitivity.csv` | kettle scenario (0 couriers) × relay count/placement |

## Honest limitations

- Contact is modelled as simultaneous pairwise exchange each tick; the "one
  connection at a time" rule is enforced per node per tick, but real BLE
  throughput, MTU, and duty-cycled scanning are not modelled.
- Movement is a random walk + straight-line leavers; no roads, no obstacles, no
  crowd-density field, no acceleration.
- `outward` uses the capture point as reference (SPEC.md §13.3's "deliberate,
  cheap proxy"); the 100 m displacement threshold and 50 m crowd model are
  modelling choices, not spec values.
- Energy is an estimate. Real mAh needs hardware measurement (SPEC.md §23.3).
- Escape = crossing the boundary. Real upload still needs a network on the far
  side; that is outside this simulator's scope.

The simulator is evidence the design is *plausible*, not proof it works.
