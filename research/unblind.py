"""Unblinds a T3 review: research/unblind.py <key.edn> <scores.json>. Prints per agent and side
the mean rating, serious blunders and wasted clicks (rubric fields)."""
import json, re, sys
from collections import defaultdict

key = {}
txt = open(sys.argv[1]).read()
for rec in re.findall(r'\{[^{}]*?:file "log-\d+\.txt"[^{}]*?\}', txt):
    f = re.search(r':file "([^"]+)"', rec).group(1)
    key[f] = (re.search(r':reviewed :(\w+)', rec).group(1), re.search(r':side :(\w+)', rec).group(1))
agg = defaultdict(list)
for s in json.load(open(sys.argv[2])):
    who, side = key[s['file']]
    for k in [(who, 'all'), (who, side)]:
        agg[k].append(s)
for (who, side), xs in sorted(agg.items()):
    n = len(xs)
    print(f"{who:9s} {side:6s} n={n:2d} rating {sum(x['rating'] for x in xs)/n:.2f} "
          f"blunders {sum(x['serious_blunders'] for x in xs)/n:.2f} wasted {sum(x['wasted_clicks'] for x in xs)/n:.1f}")
