# Primitive NPC hover slabs – measured qualification

Only the prepared hover-ray slab loop changes. The public API, normalization, arithmetic order and hit/miss distance bits are preserved.275 repository/check tests and275 exact-Stage-JAR tests pass; 270,336 actual old/new API comparisons include 70,247 hits, 191,897 misses and 8,192 rejections. Independent code review found no Critical/Important issue.

Component allocation:653→141 bytes/ray under tier 1;485→21 bytes/ray under optimized compilation. These are component results, not whole-server gains. The exact candidate `dba22bf8fb91a675ffa61af6a0209caccaa16d65e33f4c7322477dfdb7fe27fb` changes one class in the accepted Chat2.20.1/composite Stage JAR 67965ec6.40,422 physical entries, all other content, duplicate resolution and entry order are preserved.

## Connected comparison

Fresh baseline→candidate→candidate→baseline, two independent 20/60-client pairs, 512 MiB heap / one active processor pinned to CPU23; own clients/controller/proxy exclude CPU23 and sibling 22. Same frozen prelit Map9, 557 chunks before admission,103 queue + 348 platform displays in settled phases, movement, horizontal boundaries and two lobby-spawn/platform reentries per count. No diagnostic recording in these runs.

| Phase | JVM allocation MB/s: baseline → candidate | Allocation change | CPU change | Tick mean change | TCP payload change |
| --- | --- | --- | --- | --- | --- |
| steady-20 | 15.839 → 15.775 | -0.40% | -3.87% | +3.51% | -0.72% |
| moving-20 | 19.129 → 17.248 | -9.83% | -3.87% | -1.27% | +0.23% |
| boundary-20 | 9.129 → 9.133 | +0.05% | -18.48% | -2.35% | +1.11% |
| reentry-20-0 | 18.666 → 15.835 | -15.16% | -8.43% | -6.70% | -0.09% |
| reentry-20-1 | 18.692 → 15.897 | -14.95% | -4.31% | +3.35% | -0.21% |
| steady-60 | 26.890 → 21.811 | -18.89% | +2.45% | +8.23% | +0.18% |
| moving-60 | 31.036 → 25.320 | -18.41% | +0.35% | +3.57% | -0.04% |
| boundary-60 | 14.679 → 14.393 | -1.95% | +9.68% | +16.13% | +0.45% |
| reentry-60-0 | 31.354 → 23.016 | -26.59% | -1.76% | -0.91% | +1.21% |
| reentry-60-1 | 31.414 → 23.019 | -26.72% | +3.27% | +4.02% | +1.01% |

JVM allocation is temporary memory allocation, not network throughput or retained heap. TCP payload bytes include Minecraft framing, not TCP/IP overhead. Percentages compare the arithmetic means of the two complete runs per variant; two pairs on shared hardware do not establish statistical significance.

## Adverse and early windows

- 20 standing: aggregate allocation−0.4%, not a demonstrated saving in both runs; p99+21.3% on the aggregate and+62.8% in the first pair.
- 60 standing: tick mean+8.23%; CPU+2.45%.60moving: p99+24.65%; CPU+0.35%, tick mean+3.57%.
- 60 horizontal boundary: tick mean+16.13%, CPU+9.68%; boundary element counts intentionally vary. This is not a uniform CPU/tick improvement.
- One first-pair reentry TCP window is+9.41%; opposite-order results vary and aggregate reentry TCP is about+1%. No network saving claimed.
- Initial 20-client join / activation maxima: baseline 59.029/49.644 ms, candidate 35.036/37.964 ms. Before first client admission: baseline 226.865/128.417 ms, candidate 139.488/137.185 ms. Single early maxima and shared hardware do not prove a cold-start gain.
- The first batch controller exited 143 during an incomplete candidate. That partial run is retained and excluded, its own listener/clients were stopped, and source-map integrity was verified. Complete runs were then executed separately; none of their adverse windows is discarded.

## Decision

Qualified only for a bounded, reversible Stage trial as an allocation/GC-pressure optimization. At 60 clients, aggregate standing/moving/reentry allocation is 18–27% lower; individual paired savings recur at approximately 17–28%. The boundary phase saves about 2%. exact interaction results are preserved. No general CPU/TPS, bandwidth, cold-start or client FPS gain is claimed. The increased mean/p99 windows remain part of acceptance evidence. Stage and Source merge status are recorded separately.

Private reproducibility evidence remains under `/home/lukas/grounds/artifacts/npc-primitive-hover-2026-11-04/`: frozen hashes, raw counters/tickCSVs, phase markers, full paired statistics, old/new API and allocation probes, class-origin proofs and cleanup. Headless offline protocol 777 clients and server-owned trajectories do not qualify rendered client FPS, Bedrock or cross-proxy behavior.
