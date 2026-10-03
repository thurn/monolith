games 1600, stalled 7 (0.44%)
stall causes: livelock=7

| Corp | Runner | n | Corp win | 95% CI | turns | win types |
|---|---|---|---|---|---|---|
| heuristic | heuristic | 100 | 0.740 | [0.646, 0.816] | 16.5 | corp/Agenda 55, runner/Agenda 22, corp/Flatline 19, runner/Decked 4 |
| heuristic | ismcts | 100 | 0.720 | [0.625, 0.799] | 16.9 | corp/Agenda 52, runner/Agenda 21, corp/Flatline 20, runner/Decked 7 |
| heuristic | neural | 100 | 0.800 | [0.711, 0.867] | 17.9 | corp/Agenda 74, runner/Agenda 17, corp/Flatline 6, runner/Decked 3 |
| heuristic | planner{:rerank 3, :s1-margin 2.0} | 100 | 0.590 | [0.492, 0.681] | 18.1 | corp/Agenda 42, runner/Agenda 35, corp/Flatline 17, runner/Decked 6 |
| ismcts | heuristic | 100 | 0.690 | [0.594, 0.772] | 16.5 | corp/Agenda 48, runner/Agenda 25, corp/Flatline 21, runner/Decked 6 |
| ismcts | ismcts | 100 | 0.690 | [0.594, 0.772] | 17.5 | corp/Agenda 54, runner/Agenda 21, corp/Flatline 15, runner/Decked 10 |
| ismcts | neural | 100 | 0.750 | [0.657, 0.825] | 18.3 | corp/Agenda 68, runner/Agenda 19, corp/Flatline 7, runner/Decked 6 |
| ismcts | planner{:rerank 3, :s1-margin 2.0} | 100 | 0.580 | [0.482, 0.672] | 18.4 | corp/Agenda 45, runner/Agenda 35, corp/Flatline 13, runner/Decked 7 |
| neural | heuristic | 100 | 0.640 | [0.542, 0.727] | 14.2 | corp/Agenda 60, runner/Agenda 33, corp/Flatline 4, runner/Decked 3 |
| neural | ismcts | 100 | 0.610 | [0.512, 0.700] | 14.3 | corp/Agenda 57, runner/Agenda 34, runner/Decked 5, corp/Flatline 4 |
| neural | neural | 98 | 0.418 | [0.326, 0.517] | 15.5 | runner/Agenda 48, corp/Agenda 40, runner/Decked 9, corp/Flatline 1 |
| neural | planner{:rerank 3, :s1-margin 2.0} | 99 | 0.263 | [0.186, 0.357] | 14.7 | runner/Agenda 71, corp/Agenda 26, runner/Decked 2 |
| planner{:rerank 3, :s1-margin 2.0} | heuristic | 100 | 0.710 | [0.615, 0.790] | 14.5 | corp/Agenda 63, runner/Agenda 28, corp/Flatline 8, runner/Decked 1 |
| planner{:rerank 3, :s1-margin 2.0} | ismcts | 100 | 0.750 | [0.657, 0.825] | 14.4 | corp/Agenda 66, runner/Agenda 22, corp/Flatline 9, runner/Decked 3 |
| planner{:rerank 3, :s1-margin 2.0} | neural | 96 | 0.698 | [0.600, 0.781] | 16.3 | corp/Agenda 62, runner/Agenda 25, corp/Flatline 5, runner/Decked 4 |
| planner{:rerank 3, :s1-margin 2.0} | planner{:rerank 3, :s1-margin 2.0} | 100 | 0.510 | [0.413, 0.606] | 15.6 | corp/Agenda 48, runner/Agenda 46, corp/Flatline 3, runner/Decked 3 |

Null rates (self-play Corp win share): heuristic 0.740, ismcts 0.690, neural 0.418, planner{:rerank 3, :s1-margin 2.0} 0.510

| Agent | Corp rating | Runner rating |
|---|---|---|
| planner{:rerank 3, :s1-margin 2.0} | +0.13 | +0.06 |
| heuristic | +0.35 | -0.85 |
| ismcts | +0.18 | -0.84 |
| neural | -0.66 | -0.72 |

Mean think time per decision (ms): heuristic 0.4, ismcts 98.9, neural 9.9, planner{:rerank 3, :s1-margin 2.0} 126.0
