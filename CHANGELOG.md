# Changelog

All notable changes to GEO Readiness are documented here. The format is based
on [Keep a Changelog](https://keepachangelog.com/); this project follows
semantic-ish versioning aligned with the Jahia module version.

## [Unreleased]

Wave 1. Nothing here has shipped yet, so this section covers the whole module
rather than a delta. It needs nothing from any external vendor.

### Added

- **Crawler access check.** One server-side fetch per AI bot user agent,
  fifteen of them with a normal browser first as the control. No cookies, no session,
  redirects not followed, and the **initial HTML** is read rather than a
  rendered DOM. A page whose content only appears after JavaScript looks
  perfect to an editor and empty to a crawler, and this is the lens that shows
  it. Reports status, time, size, word count and what the crawler receives
  before JavaScript runs.
- **robots.txt report.** A parser that follows what the major crawlers
  actually do: consecutive `User-agent` lines share a group, the most specific
  group wins, the longest rule wins within it, `Allow` beats `Disallow` on a
  tie, `*` and `$` are honoured. The matched rule is always shown so the
  reasoning can be checked rather than trusted. Cross-checks the policy
  against reality and names the two disagreements: **blocked but allowed**
  (the file says yes, the server says no) and **reachable but disallowed**.
- **llms.txt report.** Presence, title, summary, section and link counts, and
  the common false positive where `/llms.txt` returns the HTML page with a 200.
- **GEO score.** Seventeen pass/fail checks in three groups, each reading one
  fact already in the report and showing that fact beside the verdict.
  Deliberately not a rating out of 100: a score nobody can argue with is a
  score nobody can act on. Three severities, from "a crawler cannot read this
  at all" down to "worth doing".
- **Six initial-HTML signals** that cost no extra request, since the HTML is
  already in memory: declared page language, hreflang alternates, sub-heading
  outline, alt-text coverage, JSON-LD `@type` breakdown rather than a bare
  count, and whether a modification date is published.
- **llms.txt generation** from the site's own published page tree.
  Deterministic: no model call and no external service, so the same site
  produces the same file every time and the rule can be read and predicted.
  Pages hidden from the navigation are left out and descriptions are never
  invented.
- **robots.txt AI-crawler control.** Allow or block per crawler, merged into
  the existing file. Everything not asked about is left byte for byte as
  found, comments and `Sitemap` lines included.
- **Site settings panel** under Additional > SEO, beside Robots.txt and
  Sitemap, with a tab per file. Both files belong to the site, so they are
  edited there rather than from a page drawer.
- **Highlighted diff before any overwrite.** Added and removed lines carry a
  sign as well as a colour, unchanged runs are collapsed, and the apply button
  needs a second confirming click. What gets written is exactly the text on
  screen, so hand-edits made before applying are kept rather than regenerated
  over.
- English and French throughout, including every check label and its
  explanation.

### Changed

- All fifteen AI crawlers are now fetched, not eight. The robots.txt checker
  always evaluated fifteen tokens while the fetch covered eight, so seven
  crawlers had a stated policy that nobody had verified against the server.
  That is the exact gap this module exists to close. Both halves now read one
  registry, `AiCrawlers`, and the fetches run three at a time so sixteen
  requests stay near a second rather than two minutes.

- The score and the crawler results are rendered as lists rather than
  tables, and the drawer is 820px wide instead of 620px. Moonstone table
  rows are a fixed height and the cell wraps its children in a Typography,
  so a check's explanation was clipped and the crawler's markup indicators
  overlapped the line above. Tables remain where every value is short and
  fits one line.

### Notes on behaviour worth knowing

- **Being disallowed in robots.txt is not scored as a failure.** Refusing a
  crawler is a legitimate decision. A disagreement between the policy and the
  server is scored, because one of the two is then wrong.
- **Allow and block are not symmetric.** Block replaces a crawler's rules with
  a site-wide refusal. Allow only undoes a site-wide refusal, so path rules
  such as `Disallow: /docs/` survive rather than being flattened away.
- **A crawler sharing a `User-agent` group is split into its own group** when
  its stance changes, so its former siblings keep their rules.
- **The tested URL is built by Jahia, not guessed.** `node.getUrl()` then the
  outbound URL rewriter, which yields the vanity URL where one resolves on
  that host.
- **The check fetches a page even when robots.txt disallows it**, on purpose.
  Knowing that a disallowed page is nonetheless reachable is a finding.

### Known gaps

- `llms-full.txt` cannot exist on this stack. The community `llms` module
  declares one property and one route, so the report calls the file absent
  when it is really unsupported.
- The robots.txt merge is line-based, not a full RFC 9309 rewrite. It does not
  reorder or tidy a file, so a hand-written robots.txt comes back
  recognisable.
- Vanity URLs are host-dependent by Jahia design: the rewriter emits one only
  when the request's server name resolves to the page's site.
