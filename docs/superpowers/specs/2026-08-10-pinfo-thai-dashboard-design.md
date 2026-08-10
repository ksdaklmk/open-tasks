# PInfo Thai Executive Dashboard Design

**Status:** Approved in conversation on 10 August 2026  
**Primary deliverable:** `/Users/kk/projects/open-tasks/pinfo_thai_dashboard.html`

## Objective

Create one self-contained, offline HTML dashboard from
`/Users/kk/Downloads/pinfo_thai.csv` for executives and managers working in
bioinformatics. The dashboard must make the cohort, operational mix,
anthropometrics, and self-reported health signals easy to scan while remaining
honest about data quality, denominators, uncertainty, and the limits of
self-reported data.

The dashboard uses
`/Users/kk/projects/my-learning/cohort_dashboard.html` as an information-
architecture reference. It reuses the reference's summary-to-detail flow,
global cohort controls, explicit provenance, and offline packaging. It does not
copy the reference's embedded row-level dataset, high chart density, doughnut
charts, decorative gradients or side stripes, inaccessible tabs, stale empty
states, or small low-contrast text.

## Audience and decisions

The primary audience is executive and managerial rather than clinical. The
dashboard should help them answer:

1. Is the dataset reliable enough for the displayed analyses?
2. How are test volumes and package mix distributed and changing?
3. What does the adult anthropometric profile look like under the Thai public-
   health BMI classification?
4. Which structured diseases, symptoms or abnormalities, and reported food
   avoidances are most common?
5. Which observed patterns merit operational follow-up or more rigorous
   analysis?

The dashboard must describe associations and reporting rates, not diagnoses,
population prevalence, treatment advice, or causal relationships.

Reader-facing interface copy is English, matching the supplied reference.
Reviewed Thai source labels remain in Thai, with an existing English gloss
retained only when it is already present in the source. The build must not add
unreviewed machine translations.

## Source boundaries

### Controlling data source

- File: `/Users/kk/Downloads/pinfo_thai.csv`
- Parsed grain: one test-kit record, keyed by `test_kit_code`
- Parsed shape: 1,979 records and 34 fields
- Receipt window: 3 December 2021 through 15 May 2026
- Encoding: valid UTF-8 without a BOM; no Unicode replacement characters or
  Thai mojibake sequences were found
- Raw file handling: read-only; no in-place rewrite

The CSV parser must support quoted embedded newlines. Physical line count is
not the record count.

### Reference interface

- File: `/Users/kk/projects/my-learning/cohort_dashboard.html`
- Reuse: dashboard sequence, filter concept, chart-plus-interpretation rhythm,
  source notes, offline handoff
- Replace: row-level browser data, decorative or inaccessible components,
  tiny text, doughnuts, and unsupported causal or commercial claims

### External standards

Adult BMI uses the Thai Department of Disease Control classification for age
20 years and older:

| BMI (kg/m²) | Dashboard label |
| --- | --- |
| `<18.5` | Underweight |
| `18.5–<23.0` | Normal |
| `23.0–<25.0` | Overweight / Thai obesity level 1 |
| `25.0–<30.0` | Obesity / Thai level 2 |
| `≥30.0` | Obesity / Thai level 3 |

Source: Thai Department of Disease Control, *Know Your Numbers & Know Your
Risks*, pages 7–8:
<https://ddc.moph.go.th/uploads/publish/1064820201022081932.pdf>.

The dashboard must call this the **Thai DDC adult BMI classification**, not a
universal "WHO Asian standard." BMI is calculated from unrounded height and
weight values and displayed to one decimal place. People younger than 20 are
counted separately and are not assigned an adult category. The source contains
no pregnancy field, so pregnancy-specific exclusion cannot be performed and
must be disclosed. BMI remains a screening proxy rather than a diagnosis.

## Privacy contract

The portable HTML must contain only reviewed aggregate data. It must not
contain names, addresses, postcodes, direct contact details, `order_id`,
`test_kit_code`, `inter_call_code`, row-level demographic or health records,
or linkable record fragments.

Privacy controls apply to both visible content and embedded payloads:

- Exact cells with counts below 5 are suppressed.
- Rates and percentages are withheld when their denominator is below 20.
- Rare packages or categories are combined into `Other / insufficient sample`
  only when the combined group itself satisfies the count threshold.
- Raw free-text values are never embedded. A free-text term may appear only
  after deterministic normalisation, review, and at least 5 mentions.
- Filtered states that would violate a threshold show `Insufficient sample`
  instead of an exact value or chart mark.
- No aggregate dataset may be constructed in a way that lets a reader recover
  a suppressed cell by subtracting visible totals.

The final privacy check scans the generated HTML and canonical aggregate
artifact for forbidden field names and sampled source identifiers before
handoff.

## Data preparation

The analysis pipeline performs deterministic transformations before
aggregation:

1. Decode strictly as UTF-8.
2. Parse all 34 CSV fields with quoted-newline support.
3. Normalise strings to Unicode NFC, trim edge whitespace, and collapse
   internal linebreaks or repeated whitespace where the field is free text.
4. Treat blank strings, `NA`, `N/A`, `not specified`, `not-specified`, `null`,
   `none`, `unknown`, and `-` as missing only for analytical completeness.
5. Parse receipt and collection dates as `DD-MM-YYYY` without guessing other
   ambiguous date orders.
6. Parse pipe-delimited disease, abnormality, and food selections; trim items
   and discard empty items caused by trailing pipes.
7. Preserve coded pick-list values separately from uncontrolled free text.
   Do not semantically merge Thai free text with English coded values unless a
   documented deterministic mapping supports the merge.
8. For adults aged 20+, accept height from 100–270 cm and weight from 20–350 kg
   as operationally plausible. Recompute BMI from valid weight in kilograms
   and height in metres. The recomputed value controls classification; the
   source BMI is a reconciliation field only. Missing or invalid height or
   weight produces `Not assessed`.
9. Build bounded, reviewed aggregate datasets and apply the privacy contract
   before packaging.

Thai text must remain readable in the output through `<meta charset="utf-8">`,
Unicode-preserving serialisation, and a system font stack that includes
`Noto Sans Thai`, Tahoma, and platform sans-serif fallbacks. No network font is
required.

## Baseline quality findings to surface

These inspected facts establish the initial dashboard scope. The reproducible
analysis must recalculate them rather than hard-code them:

- `test_kit_code` is complete and unique across all 1,979 records; there are no
  exact duplicate rows.
- `order_id` is missing or represented by a sentinel in 509 records (25.7%), so
  it is not a valid analytical key.
- Collection date is missing in 674 records (34.1%). Of 1,305 computable
  collection-to-receipt intervals, six are negative and require review.
- Age is missing in 24 records, BMI in 33, weight in 32, and height in 22.
- The 1,946 complete height-weight-BMI triplets reconcile to the stated BMI
  within 0.005 kg/m².
- There are three underlying-disease flag/list contradictions; abnormality
  flag/list combinations reconcile.
- Waist and hip are missing in 92.0% and 93.3% of records, respectively, so
  they are quality indicators rather than headline biometric measures.
- Food selections contain 170 harmless trailing pipe delimiters that must be
  removed during tokenisation.
- Address fields contain embedded linebreaks and other formatting issues, but
  all address data is excluded from the dashboard.

Every data-quality finding must state the affected count, rate, analytical
risk, severity, confidence, and smallest useful remediation.

## Dashboard structure

The page is organised as five keyboard-accessible tabs. The default view is
useful without interaction. Tabs use native buttons or correct tab semantics,
visible focus, arrow-key navigation, and programmatic panel relationships.

### 1. Executive Overview

Show a concise metric strip and no more than four supporting visuals:

- analysed test-kit records and receipt window
- core analytic coverage (age, sex, valid adult BMI)
- leading package share and package concentration
- adult BMI `≥23.0` share under the Thai DDC definition
- share reporting any underlying disease
- share reporting any symptom or abnormality
- share reporting at least one food avoidance or sensitivity
- monthly intake overview and one ranked health-signal summary

Adjacent narrative identifies the most decision-relevant verified patterns,
their denominators, and the action or follow-up they support.

### 2. Data Quality Audit

This tab is source-wide and deliberately unaffected by cohort filters. It
contains:

- field-completeness ranked bars
- a compact anomaly register sorted by severity
- grouped completeness for identifiers, operations, demographics,
  anthropometrics, geography, and health fields
- controlled-pick-list versus reviewed-free-text coverage
- explicit source grain, freshness, record count, and privacy status

The interface must clearly label this tab `Full source audit` so its fixed
scope cannot be confused with filtered analytical tabs.

### 3. Operations & Packages

Show:

- monthly laboratory receipt volume with enough points to reveal shape
- annual package-mix composition
- package concentration and the observed shift in mix over time
- collection-to-receipt interval distribution, coverage, negative intervals,
  and long-delay tail
- a compact package profile table with counts, median age, sex mix, adult BMI
  coverage, and reported disease/symptom rates where privacy thresholds allow

2026 is labelled as partial through 15 May and is not compared with complete
years without that caveat. Package comparisons remain descriptive because
package populations differ materially in age and other composition.

### 4. Biometrics & Weight

Show:

- measurement coverage and valid adult denominator
- Thai DDC BMI category distribution for age 20+
- BMI histogram with visible thresholds at 18.5, 23.0, 25.0, and 30.0
- elevated-BMI share by adult age band and sex when denominators permit
- people under age 20, missing age, missing BMI, and invalid measurements as
  separate unclassified counts

The baseline eligible denominator is 1,870 adults aged 20+ with BMI; 910
(48.7%) have BMI `≥23.0`. These values must be recalculated from the cleaned
source during generation. No paediatric weight-status classification is
attempted because the file does not provide the age-in-months and growth-
reference workflow needed for an honest result.

### 5. Health Signals

Show aggregate reporting rates for:

- top structured underlying diseases
- top structured symptoms or abnormalities
- reviewed food avoidance or sensitivity selections, preserving readable Thai
  labels
- condition co-occurrence where every displayed cell meets privacy thresholds
- descriptive adult BMI-stratified cardiometabolic reporting rates, with
  denominators and a prominent non-causal caveat

Coded values and uncontrolled free-text mentions remain separate unless a
documented mapping exists. All charts say `reported` or `self-reported`; none
use unqualified `prevalence`.

## Filters and interaction

Global filters are limited to dimensions that materially help management
exploration and can be represented safely in aggregate data:

- receipt year
- package group
- age band
- sex as recorded
- Thai DDC adult BMI category
- preferred language

Filters update the four analytical tabs, the included aggregate denominator,
and visible source context. They never change the Data Quality Audit. Reset is
a native button. A filter combination with no safe aggregate output clears all
prior values and shows an explicit empty or insufficient-sample state; stale
charts must never remain visible.

Charts provide tooltips for exact safe values, direct labels where useful,
and accessible tabular equivalents. Interactions require no network or local
server. Print styles expose every section in reading order and include data
tables, methods, and caveats.

## Visual system

Use a restrained, light product-dashboard palette suitable for managers
reviewing dense information in normal office lighting:

- white and neutral off-white surfaces
- deep charcoal text
- one blue root for primary data
- orange/gold for caution and thresholds
- olive or purple only when a distinct comparison requires a second root
- no pink/blue sex convention, gradients, glass effects, decorative side
  stripes, 3D charts, or colour-only status

Use one Thai-capable system sans family, tabular numerals, 14 px minimum body
text, compact but legible labels, and WCAG 2.2 AA contrast. Use spacing,
typography, and subtle borders rather than wrapping every element in nested
cards. Motion communicates filter or tab state only, lasts 150–250 ms, and
honours `prefers-reduced-motion`.

Responsive behaviour is structural:

- desktop: two-column analytical canvas where labels fit
- tablet: balanced one- or two-column layout by chart needs
- mobile/narrow: one column, non-sticky wrapped filters, horizontally safe
  tables, and charts that retain readable labels
- no horizontal page overflow at 320 px CSS width or 200% zoom

## Evidence and narrative rules

- Chart titles state the metric or comparison neutrally.
- Subtitles state denominator, population, date range, and units when needed.
- Interpretation appears beside or below evidence and is clearly separated
  from measured results.
- Every percentage exposes its numerator and denominator in a tooltip, table,
  or source detail.
- Partial periods, missingness, small samples, selection bias, self-reporting,
  and lack of a general-population comparator are visible where they affect
  interpretation.
- Package or demographic differences are not presented as causal or as market
  performance without exposure, revenue, or eligible-population denominators.

## Error and edge-state handling

- Invalid UTF-8, malformed CSV width, missing required columns, or an empty
  source stops generation with a clear error; no partial dashboard is claimed.
- Unparseable dates and non-numeric biometrics are counted as invalid and shown
  in the audit rather than silently coerced.
- Negative collection-to-receipt intervals remain visible as anomalies and are
  excluded from positive transit-time summaries.
- Missing and invalid BMI records are `Not assessed`, never `Underweight`.
- People younger than 20 remain unclassified.
- Filtered denominators below privacy thresholds show a safe empty state.
- Long Thai labels wrap or receive adequate horizontal space; typography is
  never shrunk below the legibility floor to force them into a card.

## Implementation boundary

The final reader receives one generated HTML file with all safe aggregate data,
styles, chart runtime, accessible semantic fallback, and interactions embedded.
It requires no CDN, remote script, network fetch, local server, sibling data
file, or installed font.

The raw CSV is not embedded. Supporting analysis and canonical aggregate
artifacts may remain in the workspace for auditability, but opening the final
HTML must not depend on them.

No live refresh, authentication, database, row-level search, export workflow,
saved filters, forecasting, diagnostic modelling, clinical inference, or
patient-level drill-down is included. Add those only if a future requirement
provides a governed data service and access-control model.

## Verification and acceptance

Before handoff:

1. Execute the analysis reproducibly from the source CSV.
2. Recompute headline metrics independently and reconcile cards, charts, and
   tables to the aggregate source outputs.
3. Verify Thai strings remain valid NFC Unicode and render without replacement
   glyphs or mojibake.
4. Verify every BMI boundary and the age-20 eligibility rule with focused test
   records.
5. Verify all privacy thresholds, including filtered and differencing cases.
6. Scan the HTML and supporting aggregate artifact for forbidden identifiers,
   direct PII, raw free text, and row-level arrays.
7. Verify the HTML is self-contained and makes no external requests.
8. Open the generated reader at desktop and narrow widths; check charts,
   tables, filters, empty states, overflow, keyboard operation, focus order,
   semantic headings, accessible names, and reduced motion.
9. Confirm every user-requested section is present and every omitted analysis
   has an explicit evidence or privacy reason.

Acceptance requires a successful portable build/verification receipt, exact
payload reconciliation, no detected PII, no browser errors or external
requests, and a final rendered dashboard that is readable at desktop, narrow
width, and 200% zoom.
