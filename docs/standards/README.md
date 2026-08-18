# Standards, as actually read

This directory holds the standards this platform claims to follow, **read from primary or freely
published sources, with access dates**. It exists so that no phase re-researches them and no document
cites a text nobody opened — the defect class this programme has corrected five times.

Every file states four things: what was read, from where, on what date, and **what was not readable**.

| File | Covers |
|---|---|
| [`iso-27560-consent-records.md`](iso-27560-consent-records.md) | The consent record and receipt structure, field by field, against `ConsentReceipt` and `ReceiptService` |
| [`dpv-v2-vocabulary.md`](dpv-v2-vocabulary.md) | W3C DPV v2 legal-basis, consent-status and purpose terms against the platform's vocabulary |
| [`tcf-and-consent-mode.md`](tcf-and-consent-mode.md) | IAB TCF's purpose/vendor/legal-basis model, why its consent string is the wrong shape for a hash-chained ledger, Google Consent Mode v2's contract, and what to borrow and refuse from each |

The competitor teardown that reads *products* rather than standards is
[`../competitive-analysis.md`](../competitive-analysis.md); §C of it is where this platform is behind the
field.

---

## The paywall, stated rather than worked around

**ISO/IEC TS 27560:2023 is not freely readable and this project does not hold it.** Checked 17 August
2026: `https://www.iso.org/standard/80392.html` refuses automated fetch (HTTP 403) and its catalogue
entry shows CHF 0, but the text sells through ANSI and en-standard and there is no public
read-for-free. The authors of the DPV rendering note they have *"submitted a proposal to the relevant
ISO committees to make ISO-27560 freely accessible"* — i.e. it is not.

Therefore **every conformance statement in this repository names the rendering, not the standard.**
The sources actually held are:

- **[S1]** W3C DPVCG, *Consent Records and Receipts as per ISO/IEC TS 27560:2023 using DPV* —
  <https://w3c-cg.github.io/dpv/guides/consent-27560> — Final Community Group Report, 15 February
  2026. Accessed **17 August 2026**. *Not a W3C Standard and not on the Standards Track; its own text
  says all guidelines, diagrams, examples and notes in it are non-normative.*
- **[S2]** Pandit, Lindquist & Krog, *Implementing ISO/IEC TS 27560:2023 Consent Records and Receipts
  for GDPR and DGA* — <https://arxiv.org/pdf/2405.04528>. Accessed **17 August 2026**. Field-level
  tables 3–7 and §2 structure.
- **[S3]** Pandit et al., *Data Privacy Vocabulary (DPV) — Version 2.0* —
  <https://arxiv.org/pdf/2404.13426>. Accessed **17 August 2026**.
- **[S4]** The live DPV 2.0 taxonomy pages, for term spellings —
  <https://w3c-cg.github.io/dpv/2.0/dpv/modules/legal_basis.html> and `…/purposes.html`. Accessed
  **17 August 2026**.

**Nobody can certify against 27560.** [S2] records that it is a Technical Specification which "only
provides guidance and is intended to obtain feedback to create a (future) international standard." The
value of the platform's claim is therefore honesty to a downstream reader, not compliance evidence —
which is exactly why it has to be precisely true. See
[`iso-27560-consent-records.md` §4](iso-27560-consent-records.md).

## If UDS buys the standard

This file is where the delta gets recorded. The nine items that cannot be settled without the paid
text are listed in [`iso-27560-consent-records.md` §5](iso-27560-consent-records.md). **The one
worth the purchase on its own is item 3** — whether the rule that a consent record's mandatory fields
are also mandatory in a receipt is normative or advisory. It is one clause, and it decides whether the
platform's receipt claim is *partial* or *overstated*.
