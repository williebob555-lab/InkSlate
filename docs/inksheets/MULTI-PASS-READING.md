# InkSheets multi-pass music reading

## Outcome

Music reading becomes an incremental, evidence-preserving background process. A first reading is
available quickly, then independent passes audit and refine it. Green means that the whole measure
has a calibrated, independently supported probability of being correct; it no longer means only
that the rule-based reader did not raise a doubt.

Generated readings, shadow-audit candidates, Fix alternatives and musician-owned corrections remain
separate. No automatic pass may replace,
delete, or reinterpret a fixed measure, a structural correction, or a manual signature/rest-count
correction.

## Quality bar

The primary safety metric is **green trust**: exact semantic correctness among measures shown as
green. The target is at least 99.9% on held-out songs and held-out source/engraver families, reported
both per measure and as the share of parts with zero wrong green measures. The older 99% target is
not sufficient: over a 50-measure part it still implies roughly a 39% chance of at least one wrong
green measure when errors are independent.

Other release metrics:

- exact-measure accuracy (notes, rests, pitch, accidentals, value, dots and tuplets);
- green coverage at the required trust level;
- wrong-green measures by failure class, especially barline and carried-signature cascades;
- parts with zero wrong green measures and parts with at most two measures needing review;
- recall of a correct answer in the first 3, 6 and 12 candidates;
- expected calibration error and Brier score;
- background CPU time per 100 pages, peak memory and foreground-yield latency;
- results stratified by embedded notation, clean raster, scan/photo, full score, percussion and
  multi-staff keyboard music.

Benchmarks must split by song and, where metadata permits, by publisher/engraver/source. Random
measure splits leak nearly identical notation between training and validation and are not evidence
that confidence will transfer to a musician's library.

## Persistent model

The base reading remains the page cache used today. Additional passes write immutable candidate
evidence beside it, keyed by file-content id, reader version, page, staff, source box and pass
version. A later candidate bundle will retain:

- semantic events and signature/structure state;
- pass kind and parameters (raster size, threshold, skew and line-removal variant);
- component scores and source geometry;
- which candidates agree semantically;
- the selected candidate, posterior margin and reasons for its confidence;
- whether a musician correction owns the measure.

Printed measure numbers are attributes, not durable identity. Until canonical bar ids are available
for ordinary readings, page + staff + source box is the recognition identity; the displayed number
may itself be wrong.

## Passes

### 0. Inventory

Classify each source page before expensive recognition: embedded music notation, clean digital
raster, scan/photo, likely full score, staff count, skew, contrast, staff size and degradation.
Embedded notation continues to use the exact vector path. The classification selects applicable
passes and prevents spending scan work on exact sources.

### 1. Coverage

Read every page once with the current production recognizer. Save each page atomically and yield at
page boundaries. The current piece remains first, then the rest of its parts, then the library.

### 2. Independent audit

Audit every unfixed single measure, including measures currently called sure. Read a staff once per
variant and retain results for every measure on it. Initial variants are the existing scale and
vertical-offset looks; subsequent variants add raster size, grayscale/local threshold, line-removal
strength and small deskew alternatives.

This pass first runs in shadow: it records agreement and disagreement but does not change the base
reading or visible colour. It supplies evidence needed to measure how many green errors an audit
would catch before automatic selection is enabled.

### 3. Targeted repair

Rank unresolved measures by expected value:

`probability wrong * musical severity * probability this pass resolves it / estimated cost`

Run specialist searches only where relevant: barline hypotheses, signature alternatives, missing or
invented events, head/rest identity, beams/flags, dots, accidentals, tuplets and multi-measure-rest
counts. Reuse a page evidence pyramid and staff strips so candidate decoding does not repeat page
rendering, staff finding or neural feature extraction.

### 4. Passage resolution

Retain several candidates per measure and select a passage jointly. The sequence score combines
image evidence with clef/key/time continuity, duration, pickups, bar numbering, repeats/endings and
repeated passages. Structural and signature uncertainty is represented explicitly so one early
mistake cannot silently make a page of internally consistent green measures.

### 5. Song and ensemble resolution

Use sibling parts and full scores where alignment is safe. Shared evidence includes measure
structure, signature changes, rehearsal locations, repeats and rhythmic landmarks; different parts
are never required to have the same melody. Consensus can increase confidence, disagreement can
only lower it until a candidate is resolved.

### 6. Human queue

Present the remaining work in severity/confidence order. A correction becomes immutable truth for
that library and is also recorded as training evidence. Generated candidates never overwrite it.

## Confidence

Confidence is hierarchical rather than a single notehead score:

1. page/staff/measure structure;
2. clef, key and time state;
3. event inventory;
4. pitch and accidentals;
5. duration, beams, dots and tuplets;
6. exact whole-measure correctness.

A measure may become green only when its calibrated whole-measure probability crosses the release
threshold, at least one meaningfully different pass supports it, no critical structural/signature
disagreement remains, and the selected candidate has a sufficient margin over the runner-up.
Unanimity among correlated variants is a feature, not proof.

The existing `train/sure.py` experiment is a starting point, not a production model. Production
calibration must include source quality, component disagreement, candidate margin and dependency on
carried state, and must be evaluated out of song/source.

## Delivery stages

1. **Shadow audit foundation.** Audit green and doubtful measures, batch by staff, retain alternate
   readings, calculate explicit semantic disagreement, and change no visible colours or selections.
   Implemented with two geometry and two independently thresholded image variants, plus a local
   augmentation-dot specialist whose evidence is stored separately from candidate measures.
2. **Benchmark and calibration.** Extend `ReadingBenchmark` to report audit capture, candidate
   recall, calibration and per-part wrong-green counts. Build held-out source splits.
3. **Conservative confidence UI.** Replace `doubts.isEmpty()` as the meaning of green. Roll out with
   a stricter threshold and allow coverage to fall before improving it.
4. **Adaptive scheduler and specialist candidates.** Add expected-value ranking, reusable page
   evidence and pass budgets. Yield and memory limits remain platform-controlled. The first
   operational ranking layer is in place: current music remains first, then library scores are
   ordered by urgent/review/unaudited measure evidence rather than filename order.
5. **Passage resolver.** Jointly select candidates and model carried state/structural hypotheses.
6. **Song/ensemble resolver.** Add cross-part evidence with guarded alignment.
7. **Automatic promotion.** Enable only for candidate classes that improve held-out exact accuracy
   without violating the green-trust threshold. Retain rollback/provenance for every promotion.

## Stage 1 implementation boundary

The first implementation intentionally does not recolour or replace measures. It:

- includes currently green measures in the idle audit;
- excludes fixed and multi-bar measures;
- batches all target measures on a staff into four deeper looks instead of rereading that staff four
  times for every doubtful measure;
- persists the resulting candidates through a separate atomic shadow-audit cache, never Fix's
  alternate-reading cache;
- persists augmentation-dot and conservative rest-template evidence in a separate versioned
  specialist sidecar, together with a numbered multi-measure-rest detector;
- exposes a pure semantic assessment of agreement and failure classes;
- computes an operational confidence tier/index for ordering work without treating agreement as
  verification or changing the existing measure colour.

On the expanded scanned calibration slice (20 parts, first page of each), the four general passes
challenged 6 of 44 wrong green measures and 48 of 830 correct green measures. A local dot shape by
itself overfit; requiring non-zero trained-reader dot odds retained 2 of the 3 known missed-dot
catches while challenging 0 of 830 correct greens. Component-level event consensus was selective
(3 wrong and 2 correct green measures). The rest-template hint challenged 2 wrong and 6 correct
greens, so it remains only a small ordering penalty. Combining pass disagreement, retained model
probabilities, component consensus and specialists put 29 of 44 wrong greens into review/urgent
while also putting 147 of 830 correct greens there: useful work ordering (about 3.4 times the base
error concentration), but deliberately not a visible confidence claim. Larger held-out source
splits remain required before recolouring or silently rewriting measures.

An early permissive numbered-rest detector overfit beams and was rejected. The retained rule is
limited to a main reading of one ordinary whole rest, independently accompanied by a readable count
and compact bar with an end cap but no internal stems. On 11 Planet B T/H/C/P parts it challenged
16 of 55 wrong green measures and 1 of 457 correct greens. On the unrelated 20-part scanned split it
fired zero times among 771 correct and 26 wrong greens. It can therefore force internal review and
work priority, but remains shadow-only: it cannot recolour or alter a reading. With the other audit
signals, the targeted split sends 38 of 55 wrong greens and 67 of 457 correct greens to
review/urgent; 17 wrong greens remain stable, so visible-green promotion is still blocked.

## Memory safety boundary

Multi-pass work is allowed to improve accuracy only within a bounded working set. The Android
reader now uses heap-aware concurrency: one worker below 768 MB, no more than two below 1.5 GB, and
no more than four on larger heaps. Page read-ahead is disabled below 768 MB. The raster pixel budget
reserves both whole-heap and currently available headroom before decoding a page, and live headroom
is checked again after the staff-size probe before committing to the full-resolution render.

Before an audit starts, redrawable page/Fix bitmaps are released. Each alternate threshold page is
created, used across all staffs, saved, and then allowed to fall out of scope before the next
variant; alternate pages are never accumulated. Specialist passes scan measure-local regions and
do not create another full-page raster. Symbol-class evidence is compacted to three scalar margins
instead of retaining twelve boxed probabilities on every note/rest, and small devices retain at
most six decoded scores while planning a library. If allocation still fails, automatic reading/auditing is
paused, queued background work and rebuildable caches are cleared, and the failed page is not
immediately retried. Foreground reading reports the stopped operation rather than entering an OOM
retry loop.

Peak heap, native bitmap pressure, and recovery behavior must be exercised on the same device/build
combination that crashed twice in 318 before multi-pass reading is enabled by default. A clean
accuracy result is not a release result if it increases memory crashes.
