# GEO backlog, Jahia-native

Stories that need nothing from any external vendor. They exist because Jahia knows things a
crawler cannot know, and can compute them at a scale a metered tool cannot afford.

Numbering continues from the sixteen stories in the BotRank integration analysis, so the last
one there was GEO-16, the crawler access check, which is built.

## The placement rule

**The dashboard owns the list and the ranking. The drawer owns the one-line verdict about this
page.**

An author standing on a page needs to know whether *this* page has a problem and what to do
about it. They do not need a table of 400 other pages. A site manager needs the opposite.

So most stories below have two halves. Read the "Drawer" line as strictly optional: if an author
cannot act on it while looking at this page, it does not go there. When in doubt, leave it out of
the drawer. The drawer gets crowded fast and a crowded drawer stops being read.

Where the dashboard lives is settled in the internal analysis: **jContent > Additional > SEO**,
beside the community `llms.txt manager` panel. It is where people already look for this, and a
shipping module proves the placement works.

## What Jahia has that no external tool has

Worth keeping in front of us, because it is the reason this backlog is not just "another SEO
plugin".

- **No quota.** BotRank audits 5 or 25 pages. A background job can score 50,000.
- **Unpublished and untranslated content.** A crawler sees what exists. Jahia sees what is
  missing, which is usually the more useful half.
- **Permissions and visibility conditions.** A crawler hitting a gated page sees a login form and
  reports a cheerful 200. Jahia knows the page can never be read by anyone.
- **The template that rendered the page.** So a finding can roll up from 500 pages to one fix.
- **The content model.** Node types carry meaning that rendered HTML has lost.
- **The link graph.** A crawler reports what it found. Jahia can report what nothing links to.

---

## GEO-17 · Score the whole site, not one page at a time

**BUILT.** Quartz cron job, results on the site, dashboard tab with schedule and scope.

**As a** site manager, **I want** every published page scored for AI readability on a schedule,
**so that** I can see where the site is weak instead of discovering it one page at a time.

This story also delivers the dashboard shell that the rest of the backlog hangs off.

**Acceptance**
- A `BackgroundJob` walks published pages per site and per language, guarded with
  `isProcessingServer()` so it runs on one node only.
- Results persist under `/sites/systemsite/contents/` so every editor sees the same numbers,
  rather than each browser holding its own copy.
- The dashboard shows: overall score, score by section, the worst pages, and movement since the
  last run.
- A page never scanned reads as "not scanned yet", never as zero.
- The job is interruptible and reports progress. A first run on a large site takes a while and
  silence reads as a hang.
- Scan scope is configurable per site. Nobody should discover this job by watching their CPU.

**Dashboard** owns all of it.
**Drawer** shows one line: where this page ranks against the rest of the site. "3 citations" means
nothing without a scale.

**Effort** L. This is the plumbing story, so it costs more than it looks and everything after it
costs less.

**How it shipped.**

*One fetch per page, not sixteen.* The drawer fetches as every crawler because comparing them is
the point there. Site-wide that is 800,000 requests for a 50,000 page site and about fifteen
hours. Once per page as a single AI crawler is roughly an hour. The cost is the thin-content
ratio, which needs a second fetch to compare against; a page that only renders through JavaScript
is still caught by the word-count check, so the ratio stays a drawer diagnostic. The one check
that genuinely needs several agents is dropped from the site score rather than allowed to pass for
free, which is why a site score is out of 17 and a page score out of 18.

*One scorer.* A scanned page is scored by building a report shaped exactly like the drawer's and
running the same `GeoScore` over it. The two can therefore not drift apart.

*Storage.* A hidden `nt:unstructured` node on the site, so no CND ships and nothing can fail to
register. `jmix:nolive` keeps it unpublished. Aggregate plus the pages that failed something,
never a row per page: measured at 2.7 KB for 13 pages, about 2 MB extrapolated to 10,000. The
previous aggregate is kept so "movement since the last run" costs one extra property.

*Schedule.* A Quartz cron trigger per site and language, validated before anything is stored, with
the scope and an enable toggle in the dashboard. A running scan appears in the administration job
list for free, and `isProcessingServer()` keeps a cluster from scanning the same site on every
node.

Not done: the template is recorded on every finding but nothing groups by it yet, which is GEO-18.
Only the current language is scanned per run, so a multilingual site needs one schedule per
language.

---

## GEO-18 · Roll findings up to the template

**As a** site manager, **I want** findings grouped by the template that produced them, **so that**
I fix one template instead of editing four hundred pages.

This is the story that makes the module feel like a platform feature rather than a page tool. No
external product can do it, because none of them know which template rendered the page.

**Acceptance**
- Every finding records the template of its page.
- The dashboard can group by template and show "this template affects N pages, M failing".
- Templates are ranked by pages affected, so the biggest single fix is at the top.
- A finding that genuinely belongs to one page's content is not attributed to its template.
  Getting this wrong sends people to edit a template over somebody's typo.

**Dashboard** owns it.
**Drawer** shows one line when it applies: "this finding comes from the template, and 480 other
pages share it". That stops an author trying to fix something they cannot fix, which is worth the
space.

**Effort** M, once GEO-17 exists.

---

## GEO-19 · Content no AI can ever read

**BUILT.** Scan in the settings panel, one critical check plus a banner in the drawer.

**As a** site manager, **I want** to know which published pages are unreachable to a crawler
because of permissions or visibility conditions, **so that** I stop counting them as content that
works for us.

A crawler hitting a gated page gets a login form and a 200. It reports success. Only Jahia knows
the page is invisible.

**Acceptance**
- Lists published pages that a guest user cannot read: ACL restrictions, visibility conditions,
  and time-based conditions that have expired.
- Separates "gated on purpose", which is fine, from "gated by accident", which is not. The second
  is the finding. A members area is not a defect.
- Cross-checks against the crawler result: a page that returns 200 to a bot but is unreadable by
  guest is serving a login page and calling it content.

**Dashboard** owns the list.
**Drawer** shows it prominently for this page, because it is absolute. No amount of editing makes
a gated page citable, and an author needs to know before spending a week on it.

**Effort** M. High signal for the cost, and it demonstrates well.

**How it shipped.** `GuestVisibility` lists published pages with a system session, then reads each
one again in a session owned by `guest`. Readability is asked, not reasoned about from ACLs.
Classification is by position in the tree: closed inside an open section is the finding, closed
inside a closed branch is a members area and is listed separately. An expired visibility condition
is read generically off any condition carrying `j:end`, so a custom condition with the same
property is handled and an unknown one is ignored rather than guessed at.

Two things had to be learned the hard way and are now in `.agents/README.md`.
`doExecuteWithSystemSessionAsUser` does not enforce ACLs, so the first version reported every page
readable. And a `DENY` ace does not close a page against an inherited grant; breaking ACL
inheritance does, which is what jContent's restrict-access uses. A fixture built on the deny would
have proved nothing.

Pages marked `noindex` are listed as well, read from the `jmix:noindex` mixin the community
robots-noindex module sets, so it costs no fetch. They sit under "closed on purpose": the page
will not be cited, but someone chose that, and a tool that calls a deliberate choice a defect gets
ignored. The same signal now keeps those pages out of the generated `llms.txt`.

Not done: the scan is synchronous and capped at 2000 pages, so a large site needs the background
job from GEO-17 before this covers everything. The cross-check against the crawler result is
implicit rather than explicit: the drawer shows both facts on the same tab, but nothing yet says
"this page returns 200 to a bot and is unreadable by guest, so it is serving a login page".

---

## GEO-20 · AI readiness per language

**As the** owner of one market, **I want** readiness for my language, **so that** a global average
stops hiding my market.

The BotRank analysis found a brand at 100% on a French question and 0% on the Japanese
equivalent. This computes the Jahia-side half of that from the repository, with no vendor.

**Acceptance**
- Per language: pages published, pages translated, pages missing, average score.
- Names languages with no coverage rather than showing them as zero. Untracked and bad are
  different things and must never look the same.
- Comparison across the site's active languages, so a 100 versus 0 split is impossible to miss.

**Dashboard** owns the comparison.
**Drawer** shows one line: which of this site's languages this page exists in, and which it does
not. That is directly actionable while editing.

**Effort** M.

---

## GEO-21 · Sitemap against reality

**As a** site manager, **I want** to know where the sitemap and the site disagree, **so that**
crawlers are not being handed a stale map.

Both sides are ours, so the comparison costs nothing and it catches real rot.

**Acceptance**
- Published pages missing from the sitemap.
- Sitemap entries that 404, redirect, or carry `noindex`.
- `lastmod` values that do not match the node's real modification date.
- Works per language, since the sitemap module is language aware.

**Dashboard** owns it.
**Drawer** shows one line: whether this page is in the sitemap. Cheap, and occasionally the whole
explanation for why a page is invisible.

**Effort** S.

---

## GEO-22 · Pages nothing links to

**As a** content manager, **I want** published pages with no inbound internal link, **so that** I
find the content crawlers never reach.

A crawler reports what it found. It cannot report what it missed. Jahia can walk the link graph.

**Acceptance**
- Published pages with zero inbound internal links from other published pages.
- Navigation links counted separately from in-content links. A page reachable only through a
  mega-menu is weaker than one editors actually link to, and the distinction is worth showing.
- Excludes pages deliberately outside navigation, such as campaign landing pages, when they are
  in the sitemap. Being unlinked is only a problem when nothing else points at them either.

**Dashboard** owns it.
**Drawer** shows one line: how many pages link here. Zero is a finding an author can fix that
afternoon.

**Effort** M. The link graph is the work.

---

## GEO-23 · Structured data from the content model

**As a** content editor, **I want** correct schema.org output derived from the content type,
**so that** I am not asking a language model to guess what my page is about.

Every other tool infers schema from rendered text. We can derive it from the definition. That is
both cheaper and more accurate, and it is a genuinely different approach.

**Acceptance**
- A mapping from node type to schema.org type, configurable, with sane defaults.
- Property-level mapping for the obvious cases: name, description, image, dates, price.
- Emits JSON-LD that passes validation, and says plainly when a required property has no source
  in the content model rather than inventing one.
- Never contradicts what is on the page. Structured data that disagrees with the visible content
  is worse than none.

**Dashboard** shows coverage: which content types are mapped, how many pages emit valid JSON-LD.
**Drawer** shows this page's generated JSON-LD with a copy button, and what is missing.

**Effort** L. Genuinely useful, and the most product-shaped item here.

---

## GEO-24 · Where the site has gone stale

**As a** content manager, **I want** to see which sections have not been updated in a long time,
**so that** I can plan a refresh before an engine decides we are out of date.

Content freshness is one of BotRank's own 24 criteria. We can compute it for the whole tree
instantly and they cannot.

**Acceptance**
- Age distribution of published content, by section and by content type.
- Flags sections where nothing has changed beyond a configurable threshold.
- Distinguishes evergreen content from time-sensitive content by type, so a legal notice does not
  get flagged next to a news article.

**Dashboard** owns it. **Drawer** shows nothing. An author editing a page already knows how old
it is, and a date in the drawer would be noise.

**Effort** S.

---

## GEO-25 · One page, several addresses

**As a** site manager, **I want** content reachable at several URLs without a canonical, **so
that** engines stop splitting the signal across duplicates.

Jahia owns the vanity URL service, so this is a repository query rather than a crawl.

**Acceptance**
- Pages with multiple vanity URLs and no canonical, or a canonical pointing somewhere unexpected.
- Vanity URLs that no longer resolve.
- Language mismatches: a vanity URL registered under the wrong language.

**Dashboard** owns it.
**Drawer** shows one line when this page has a conflict.

**Effort** S.

---

## Suggested order

1. ~~**GEO-19**, invisible content.~~ **Built.** Small, startling in a demo, needed no new
   plumbing, exactly as predicted.
2. ~~**GEO-17**~~ **built.** **GEO-18** is now the cheap half: every finding already records its
   template, so the roll-up is a grouping and a UI rather than new plumbing.
3. **GEO-21, GEO-22, GEO-25**, the cheap structural checks, once the job exists to run them.
4. **GEO-20**, per-language, which pairs with the strongest story in the BotRank analysis.
5. **GEO-23**, structured data, when there is appetite for something larger.

GEO-24 is the smallest thing here and can be slipped in whenever.

## Deliberately excluded

- **Cache configuration analysis.** Real, but it belongs to an administrator, not an author.
- **Workflow state.** Same reason.
- **Anything already covered by page-audit.** Two scores for one page is worse than one.
- **Writing to robots.txt or llms.txt from a scan.** Reporting is safe to run anywhere. Writing is
  not, and the two must stay separate features with separate permissions.
