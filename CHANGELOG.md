# Changelog

All notable changes to GEO Readiness are documented here. The format is based on
[Keep a Changelog](https://keepachangelog.com/); this project follows semantic-ish versioning
aligned with the Jahia module version.

## [Unreleased]

### Added

- **Charts, without a chart library.** Four panels now carry a visual form drawn in plain HTML and
  CSS: a meter under the site score whose fill carries severity; horizontal bars for the score by
  section; a histogram of content age plus a range bar per type and section running from newest
  to oldest with the median marked; coverage and readiness score side by side per language; and a
  single stacked bar for structured-data coverage. Every mark has a hover and keyboard tooltip. A
  charting dependency inside a Module Federation bundle is weight every jContent page pays, plus a
  second theme to keep in step with Moonstone - and the forms needed are bars and a meter.
- **Colors from Moonstone's own tokens, validated rather than eyeballed.** The two series hues
  (dark accent, purple) were run through a colorblind-safety and contrast validator on Moonstone's
  light surface as an adjacent pair. The plain accent fails 3:1 and is not used for marks; gray is
  a track, never a series. A missing value is never drawn as a zero-length bar - an unmeasured
  language renders as text saying so.

## [1.0.0] - 2026-09-11

First release, so this section describes the whole module rather than a delta. It needs nothing
from any external vendor.

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
  the sitemap advertises while their own markup says `noindex`. A fifth, reported as one finding
  rather than two: the sitemap module does not use vanity URLs, so a page that has one is listed
  under the address Jahia redirects away from - crawlers sent through a redirect to reach content
  with a direct address. The published set is built as guest
  and across every site language, because a sitemap is language-aware and written for anonymous
  crawlers. It has its own dashboard tab with its own refresh, because it is a comparison and not a
  score, and a presence test that requires the response to actually be a sitemap: "200 with a tag
  in it" would accept an HTML error page, parse to zero entries and report every published page as
  missing. No sitemap, not a sitemap, and could not be reached are three different messages. The
  dashboard lists each finding with a link to the node, its public URL and, for a
  wrong date, the two dates. The drawer carries the three findings that are about one page -
  absent from the map, advertised while saying `noindex`, or listed with a date that no longer
  matches - matched on path and language so a French finding never surfaces on the English page.

- **Pages nothing links to.** A link graph built from the rendered HTML of every page the scan
  already fetches, so it costs no extra requests and every way of producing a link counts the same:
  a menu built from the page tree, a listing, a rich text link, a configured button. Repository
  references cover sources the scan never rendered. Two findings, because they are two problems: a
  page nothing links to *and* the sitemap does not list is unreachable; one reachable only through
  the menus or only through the sitemap is found but uncommitted. Navigation and content links are
  counted apart, since being in a menu that lists everything is not the same as somebody choosing
  to link to you. The drawer says which of the two this page is.

- **One page, several addresses.** Jahia owns the vanity URL service, so every address a page
  answers on is a repository fact rather than something to be crawled for. Three findings: a vanity
  URL filed under a language the site does not serve, which returns a 404 to everybody; one page
  answering on several live addresses in the same language, loudest when none is marked default;
  and a page with aliases whose canonical tag is missing or names something else entirely.
  Deliberately no check for two pages claiming one address - Jahia renames the second on save, so
  it cannot happen.

- **llms.txt is checked against the site it describes.** The file is written once and served
  unchanged, so it goes stale silently - an assistant is handed a map of a site that has moved on,
  and nothing anywhere says so. The served copy is diffed against what regenerating would produce
  right now, so the finding is exactly as trustworthy as the button offered to fix it, and the
  reason is named: the address now redirects, the page is no longer part of the generated list, it
  is gone, or it is published and missing from the file. Not tied to the readiness score, which
  regenerating does not move.

- **Where the site has gone stale.** The age of everything a visitor can read, taken from the
  repository rather than from the dates pages choose to publish, in five buckets and broken down by
  content type and by section. Grouped rather than ranked, because a single oldest-first list puts
  a legal notice next to a news article and gets ignored; each type is judged against its own list
  instead, and nothing tries to guess which types are meant to age. A group is flagged only when
  its *newest* item is past a configurable threshold. A repository query, so it answers without
  waiting for a scan, and changing the threshold recomputes rather than filtering.

- **Readiness one language at a time.** Coverage and score for every language the site declares,
  side by side, so a strong language cannot average out a weak one. A language that has never been
  scanned reports as *not measured* rather than as zero - a zero reads as "this market is broken"
  when it means "nobody has looked", and the two need opposite responses. Coverage is a repository
  read separating translated from published, over pages and content items alike. The drawer names
  the languages this page has no translation in, and any translation that exists but has not been
  published.

- **Structured data derived from the content model.** Every other tool infers schema.org output
  from the words on a page; the content type already says what the content *is*, so it is derived
  instead. Map each type once - defaults only for Jahia's own types, since a custom type is
  somebody's model and its name is not evidence - and every item of it produces JSON-LD, with
  property sources found by convention rather than configuration. Required properties with no
  source are named rather than invented, and a generated name that disagrees with the title the
  page renders is reported as a conflict, because structured data that contradicts its page is
  worse than none. The drawer shows the snippet with a copy button; nothing is written into a page.
- **The page drawer now says where a page stands.** A score with no reference point is not
  information, so the drawer carries the site average and the page's own section average beside it,
  how many pages link here from navigation and from content, and whether the page is in llms.txt.

**Finding your way around it**

- **Ten panels grouped into four.** The dashboard reuses the same three groups the page drawer
  already sorts its checks into - can a crawler reach it, is what arrives usable, site-level files -
  so there is one vocabulary to learn rather than two, with the site score standing outside them as
  the summary of all three.

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

### Fixed

- **A page with a default vanity URL was scored against the wrong address.** The module used the
  page tree path, which Jahia 301-redirects to the vanity URL, so every crawler fetch received a
  redirect rather than the page: the score collapsed to 7 of 18 reporting "no text in the initial
  HTML", the sitemap comparison called the page missing, and the link graph could not match links
  pointing at it. The default vanity URL is now read from the repository, which is the authority.
  The same page returned to 16 of 18.

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
- The link graph judges only the language the scan read, since only that language's pages were
  fetched. A multilingual site needs one scan per language to see all of it.
- The link graph sees links on pages, not on content items with their own URL, unless they are
  modelled as repository references. An article linking to a page through rich text alone is not
  counted.
- The sitemap comparison resolves rather than fetches, so it cannot see redirects. An entry that
  301s to somewhere else is reported as clean.
- The site walk lists `jnt:page`, while the sitemap lists everything with a public URL. On a site
  whose articles are `jmix:mainResource` content rather than pages, the scan scores far fewer items
  than the sitemap compares. The two counts are measuring different things by design, but only the
  sitemap comparison currently sees the whole addressable site.
