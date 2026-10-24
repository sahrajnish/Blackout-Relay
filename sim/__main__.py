"""`python3 -m sim` entry point (delegates to sim.run)."""
from .run import run
import sys

if __name__ == "__main__":
    args = sys.argv[1:]
    quick = "--quick" in args
    trials = 8
    out_dir = "results"
    if "--trials" in args:
        trials = int(args[args.index("--trials") + 1])
    if "--out" in args:
        out_dir = args[args.index("--out") + 1]
    run(quick=quick, trials=trials, out_dir=out_dir)
