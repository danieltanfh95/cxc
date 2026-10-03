# Corvid vs rust / ts wall-time gap on pocket_moba

Filed 2026-10-02. Moved out of `BOARD.md` 2026-10-03 as a no-action
measurement note. The hypothesis "rust/ts got lucky with the 3-oracle
gate" was tested by stricter all-30-seed gating plus enumerated seeds
and a verify-seeds helper. The gap is NOT gate-asymmetry.

## Experimental matrix

| run | gate | bundle | MT-ref | wall | parity | LoC |
|-----|------|--------|--------|------|--------|-----|
| rust (3-seed baseline, v22) | 3+ | no | n/a | 13.92m | 19/19 | 1340 |
| rust-equalized exp1 | **all 30** | no | n/a | **13.68m** | 19/19 | 1330 |
| ts (3-seed baseline, v22) | 3+ | no | n/a | 13.82m | 19/19 | 1255 |
| ts-equalized exp1 | **all 30** | no | n/a | **15.25m** | 19/19 | 1288 |
| corvid best (v26p9) | 3+ | yes | yes | 21.52m | 19/19 | 929 |
| corvid mean (v26p3-11) | 3+ | yes | yes | ~27m | 19/19 | ~950 |
| corvid-noskill (v26p12) | 3+ | **no** | yes | 35.92m | 19/19 | — |
| corvid-nomt (vexp2) | 3+ | yes | **no** | 25.28m | 19/19 | 1152 |

## Readings

- Stricter gate added 0 to +1.4m for rust/ts. Rust/ts produce correct code first-draft, so the gate text was not the asymmetry.
- Bundle is net +8 min positive for corvid (v26p12 proves removing it costs 8m).
- MT reference removal is approximately neutral at -2m typical, no bigger. Model self-writes MT in roughly the same wall time.
- Residual 10-12 min corvid-vs-rust/ts gap is intrinsic. Model fluency plus corvid's structural overhead (pure FP, atom lifecycles, no self-sync-send that forces a layered-verb-atom shape).

## Compiler + bundle levers landed this cycle

- `List.min_by(λ)` / `.max_by(λ)` / `.first(λ)` sugars (lower to `.sort_by/.filter + .at(0)`)
- Statement `if cond: <then> [else: <else>]` sugar (removes `while cond: ... break` one-shot-if emulation)
- `List.slice(start, end)` (was advertised, now actually present)
- `List.concat(other)`
- Int literal 64-bit emit (`123LL`), fixes `(1).shl(40) == 0` bug
- Diagnostic for `field => value` outside `then:` body (previously surfaced as "lambda with 1 params" lowering error)
- Method-not-found diagnostic now lists available methods including synthesized sugars
- Bundle v26 documents: observation return type must be `T!` for primitives, `main` needs terminal `end` phase when multi-phase, observations can't take container params, no self-sync-send, `.min_by/max_by/first/slice/concat`

## Corvid run progression (all 19/19 unless noted)

- v26p3: 29.75m (baseline + round-1 fixes)
- v26p4: 27.00m (round-2 fixes)
- v26p5: 22.98m, 16/19, one-off regression, not re-seen
- v26p7: 29.58m
- v26p8: 27.15m
- v26p9: 21.52m, 929 LoC, best wall. First-draft pass, zero iterations.
- v26p10: 26.50m
- v26p11: 30.32m
- v26p14: 26.25m
- v26p15 i1: 25.12m, 780 LoC. Bundle "do not probe" section plus AP6 / AP7 added. LoC floor new best. Wall marginal (-1.1m vs v26p14). Model still ran a 30-seed instrumented coverage sweep (AP7 directive ignored) and surfaced 7 untested rare branches, null-auction, cash-out of deployed hero, auto-tower-fill, Mage-Slayer-vs-tower-no-enemies, two-at-once tower destruction, double-tower-same-lane, Phantom-Blade-vs-damaged-tower. Any of these could explain v26p5's 16/19 if a hidden seed exercises one.
- v26p15 i2: 20.42m, 976 LoC. New wall-time best, below the prior 21.52m floor. LoC not as compact as i1 but still 19/19.
- Mean clean 19/19: ~26.3m, σ ≈ 3m, floor 20.42m wall / 780 LoC.

## Future directive

15m corvid-opus target is bounded by model familiarity, not harness asymmetry.
