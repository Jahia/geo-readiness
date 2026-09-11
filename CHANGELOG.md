# Changelog

All notable changes to GEO Readiness are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/); this project follows semantic-ish versioning
aligned with the Jahia module version.

## [Unreleased]

First release. Nothing here has shipped yet, so this section describes the whole module rather than
a delta. It needs nothing from any external vendor.

### Added

**Seeing what a crawler sees**

- **Crawler access check.** One server-side fetch per AI crawler user agent, fifteen of them with a
  normal browser first as the control. No cookies, no session, redirects not followed, and the
  **initial HTML** is read rather than a rendered DOM. A page whose content only appears after
  JavaScript runs looks perfect to an editor and empty here, which is the point. Fetches run three
  at a time, so sixteen requests take about a second rather than two minutes.
- **robots.txt parsing and cross-check.** Follows what the major crawlers actually do: consecutive
  `User-agent` lines share a group, the most specific group wins, the longest rule wins within it,
  `Allow` beats `Disallow` on a tie, and `*` and `$` are honoured. The matched rule is always shown
  so the reasoning can be checked rather than trusted. Names the two disagreements between policy
  and reality: **blocked but allowed**, and **reachable but disallowed**.
- **llms.txt report.** Presence, title, summary, section and link counts, and the common false
  positive where `/llms.txt` returns the HTML page with a 200.
- **Content no AI can ever read.** Published pages a visitor with no account cannot open, listed
  with pages closed *on purpose* separated from pages closed *by accident*. A members area is not a
  defect. Also covers visibility conditions that have expired, and pages marked `noindex`.

**Scoring**

- **Eighteen checks in three groups**, each reading one fact already observed and showing that fact
  beside the verdict. Deliberately a count of checks, not a rating out of 100: an editor can
  disagree with a specific line but not with a number they cannot see inside. Three severities,
  from "a crawler cannot read this at all" down to "worth doing".
- **Six signals read from HTML already in memory**, so they cost no extra request: declared page
  language, hreflang alternates, sub-heading outline, alt-text coverage, JSON-LD `@type` breakdown
  rather than a bare count, and whether a modification date is published.

**The whole site, not one page**

- **Scheduled site scan.** A Quartz cron job walks every published page, fetches each once as an AI
  crawler, and stores an aggregate plus the pages that failed something. The dashboard shows the
  overall figure, the movement since the last run, a breakdown by section, and the pages with
  findings. The schedule is built from dropdowns, with an optional scope and an enable toggle, and
  a running scan appears in the administration job list.
- **Findings roll up to the template that produced them.** Templates are ranked by the pages they
  render, so the biggest single fix is first, and what the template is answerable for is separated
  from what merely happens on some of its pages. The drawer says so on the affected check: which
  template, how many pages share it, and that one fix there covers all of them. Nothing outside
  Jahia can do this, because nothing else knows which template rendered a page.

- **The sitemap is compared against reality.** Every `sitemap.xml` entry is resolved back to a
  repository node instead of being fetched, so a 378-URL site costs no requests to check. Four
  disagreements are reported: published pages the sitemap never mentions, entries that resolve to
  nothing published, `lastmod` values that contradict the node's real modification date, and pages
  the sitemap advertises while their own markup says `noindex`. The published set is built as guest
  and across every site language, because a sitemap is language-aware and written for anonymous
  crawlers. The dashboard lists each finding with a link to the node, its public URL and, for a
  wrong date, the two dates. The drawer carries the three findings that are about one page -
  absent from the map, advertised while saying `noindex`, or listed with a date that no longer
  matches - matched on path and language so a French finding never surfaces on the English page.

**Writing, behind generate → diff → confirm**

- **llms.txt generated from the published page tree.** Deterministic: no model call and no external
  service, so the same site produces the same file every time and the rule can be read and
  predicted. Honours role visibility and `noindex` by walking as a guest, so a page a visitor cannot
  read is never advertised to an assistant.
- **AI crawler control in robots.txt.** Allow or block per crawler, merged into the existing file.
  Everything not asked about is left byte for byte as found, comments and `Sitemap` lines included.
  Each crawler carries a one-line description of what it does, and a split between the seven that
  answer questions and the seven that only train models: refusing a training crawler costs no
  visibility, refusing an answering one takes the site out of that assistant's replies. The panel
  warns only for the second kind.
- **Nothing is overwritten unseen.** Both write paths show a highlighted line diff against what is
  stored, unchanged runs collapsed, additions and removals marked with a sign as well as a colour.
  The apply button needs a second confirming click, and what gets written is exactly the text on
  screen, so hand edits made before applying survive.

**Where it lives**

- **A page drawer** for one page, and a **site settings page under Additional > SEO**, beside
  Robots.txt and Sitemap. Both files belong to the site, so they are edited there rather than from
  whichever page an editor happens to have open.
- English and French throughout, including every check label and its explanation.

### Notes on behaviour worth knowing

- **Being disallowed in robots.txt is not scored as a failure.** Refusing a crawler is a legitimate
  decision. A disagreement between the policy and the server is scored, because one of the two is
  then wrong.
- **The site scan evaluates 17 checks, the drawer 18.** Comparing what a crawler receives against
  what a browser receives needs a second fetch of the same page, so that check stays in the drawer.
  Both report the same failures; only the denominator differs, and the panel says so.
- **A check is blamed on a template only at nearly every page it renders**, over at least three
  pages. A missed roll-up costs one person some time; a wrong one sends them to edit a shared
  template over a colleague's typo.
- **Allow and block are not symmetric.** Block replaces a crawler's rules with a site-wide refusal.
  Allow only undoes a site-wide refusal, so path rules such as `Disallow: /docs/` survive rather
  than being flattened away.
- **A crawler sharing a `User-agent` group is split into its own group** when its stance changes, so
  its former siblings keep their rules.
- **The tested URL is built by Jahia, not guessed.** `node.getUrl()` then the outbound URL rewriter,
  which yields the vanity URL where one resolves on that host.
- **The check fetches a page even when robots.txt disallows it**, on purpose. Knowing that a
  disallowed page is nonetheless reachable is a finding.
- **Results are stored on the site**, on a hidden node that never publishes, as an aggregate plus
  the failing pages rather than a row per page. Deleting a site takes its scan with it.

### Known gaps

- The site scan is capped at 2000 pages per run, and is synchronous when triggered by the button. A
  larger site needs the schedule.
- One language per scheduled run, so a multilingual site needs one schedule per language.
- `llms-full.txt` cannot exist on this stack. The community `llms` module declares one property and
  one route, so the report calls the file absent when it is really unsupported.
- The robots.txt merge is line-based, not a full RFC 9309 rewrite. It does not reorder, deduplicate
  or tidy, so a hand-written file comes back recognisable.
- Vanity URLs are host-dependent by Jahia design: the rewriter emits one only when the request's
  server name resolves to the page's site.
- The sitemap comparison resolves rather than fetches, so it cannot see redirects. An entry that
  301s to somewhere else is reported as clean.
- The site walk lists `jnt:page`, while the sitemap lists everything with a public URL. On a site
  whose articles are `jmix:mainResource` content rather than pages, the scan scores far fewer items
  than the sitemap compares. The two counts are measuring different things by design, but only the
  sitemap comparison currently sees the whole addressable site.
