# Blind log review rubric (T3)

Pre-registered in `LOG.md` (2026-10-03); the pass rule is there, not here. The reviewer gets this file and a directory of game logs (never `key.edn`).

## Instructions for the reviewer

You are an experienced Android: Netrunner player reviewing game logs. Each file `log-XX.txt` is the public log of one full game (what a spectator sees: facedown cards stay hidden unless they are accessed, rezzed or revealed). The first line names the side to review, CORP or RUNNER. Review only that side's play; the opponent's play matters only as context. You do not know who or what played either side, and you should not guess.

For each log, count these, for the reviewed side only:

1. **Wasted clicks**: clicks that achieved nothing or clearly worse than an obvious alternative (for example clicking for credits while rich and holding nothing to spend them on, repeating a run that can only see a card already seen, drawing into a full hand that must discard).
2. **Reasonless facechecks** (Runner): running into unrezzed or unbreakable ice with no good reason (low credits, no breaker, small grip against damage ice, nothing worth accessing).
3. **Unprotected agendas** (Corp): agendas installed or kept where the Runner could take them cheaply (naked or barely-iced remote, flooded and open HQ), when better options existed.
4. **Missed lethal or missed scores**: a win, score or steal that was available this turn and not taken; letting the opponent win next turn when it was preventable.
5. **Economy collapse**: whether the side's economy broke down in a way a competent player would have avoided (yes/no).

Then give:

- **Serious blunders**: the number of game-losing-scale mistakes: a missed win or missed score/steal, an agenda left unprotected and then stolen, walking into a flatline that was foreseeable.
- **Rating** from 1 to 5 of "plays like a competent casual human": 5 = indistinguishable from a solid casual player; 4 = competent with minor oddities; 3 = mostly sensible but with clear mistakes; 2 = frequent bad or bizarre decisions; 1 = no coherent plan.

Judge decisions on the information the side had at the time. Bad luck is not a mistake.

## Output

Return one JSON array, one object per log, and nothing else:

```json
[{"file": "log-01.txt", "side": "corp", "wasted_clicks": 0, "reasonless_facechecks": 0,
  "unprotected_agendas": 0, "missed_lethal_or_scores": 0, "economy_collapse": false,
  "serious_blunders": 0, "rating": 4, "notes": "one or two sentences on the main issues"}]
```
