# GEO backlog, BotRank integration

Stories GEO-1 to GEO-16. These come from the first-pass analysis of integrating
[botrank.ai](https://www.botrank.ai/) into jContent as a GEO panel.

**Wave 1 is built.** GEO-1, GEO-2, GEO-3 and GEO-16 all ship in this module, with two deliberate
deviations from the original scope and one thing that turned out to be impossible. Those are
called out in each story. Wave 2 onward is unbuilt and gated on BotRank.

Read `geo-backlog.md` for GEO-17 to GEO-25, which are the Jahia-native stories that need no
vendor at all.

## Status of the partnership

No API contract exists yet. BotRank publishes thirteen MCP tool names in one article and nothing
else: no parameter names, no response fields, no auth mechanism, no rate limits. Everything in
this file that touches their API is an assumption, not a specification.

That is why the waves are ordered the way they are. Wave 1 depends on nobody.

## The one sentence that explains the whole analysis

BotRank splits in two. Their page audit largely duplicates what Jahia already computes, and is
capped at 5 or 25 pages. Their citation and visibility data is irreplaceable, because it lives
outside the repository.

So: import the small part we cannot compute, compute the rest natively, publish one score.

## The 24 audit criteria, against what Jahia already has

BotRank publishes its 24 criteria in full, with pass conditions. Mapped against page-audit and
the platform:

| Criterion | Family | In Jahia? | Where |
|---|---|---|---|
| Web accessibility | Technical | exceeded | page-audit runs the full axe-core WCAG A/AA/AAA set, far beyond their contrast + alt + label checks |
| Loading time | Technical | covered | page-audit Web Vitals: TTFB, CLS, LCP est., weight, requests, DOM size |
| Image optimization | Content | covered | page-audit alt coverage, Ecodesign oversized/lazy/format, Vitals image issues |
| Meta title and description | Structure | covered | page-audit SEO tab length bands, plus AI rewrites |
| Hn tags | Structure | covered | page-audit SEO heading checks and Readability structure checks |
| Structured data | Structure | covered | page-audit JSON-LD presence and validity |
| Canonical URLs | Structure | covered | page-audit SEO tab; Jahia Site Settings SEO manages them |
| Hreflang | Technical | covered | Jahia Site Settings SEO; page-audit checks `<html lang>` |
| XML sitemap | Technical | covered | Jahia sitemap module, language and role and noindex aware |
| robots.txt | Technical | covered | Jahia robots.txt module, though AI bot agents are not pre-templated |
| HTTPS and SSL | Authority | covered | Infrastructure, trivially assertable |
| Conversational content | Content | exceeded | page-audit Flesch and Kandel-Moles, plus plain-language rewrites |
| Inappropriate content | Content | covered | Not page-audit, but `ai-content-sentinel` does exactly this |
| Mobile compatibility | Technical | partial | Ecodesign covers lazy-load, fonts, formats. Viewport, media queries, 48px touch targets are not checked. Cheap to add |
| Content quality | Content | partial | Readability covers sentence and paragraph stats. Word-count floors, named author, cited sources are not checked |
| Content freshness | Content | partial | page-audit reads `lastModified` from JCR. A visible ISO 8601 date in the rendered page is not checked |
| Content relevance | Content | partial | SEO assist proposes a focus keyword. The keyword-in-title/h1/meta check is not implemented |
| Semantic HTML5 | Structure | gap | Not checked. One afternoon of DOM assertions |
| URL structure | Structure | gap | Not checked: length under 115, lowercase, hyphens, depth 3 or less. Trivial, and Jahia controls the URL |
| llms.txt | Technical | partial | The community `llms.txt manager` module publishes the file per site, but the body is hand-written. Generation is the gap, not the file. `llms-full.txt` is uncovered |
| Google index | Authority | **import** | Needs Search Console. One of the five worth taking |
| Bing index | Authority | **import** | Needs Bing Webmaster or IndexNow. Matters for Copilot |
| Brand authority | Authority | **import** | Credited in-app to Moz. Not computable from the repository, but resold, so also buyable direct |
| Domain authority | Authority | **import** | Moz DA, 1 to 100. Also resold |

Split: Authority 5, Content 6, Structure 6, Technical 7. Sixteen of 24 are covered or partly
covered today. Two are trivial gaps. The five Authority criteria are the only ones a repository
cannot compute, and four of those are external lookups.

Observed BotRank scores line up with exactly that split: Authority 86%, Content 64%,
Structure 86%, Technical 60%.

## Where each capability would land in jContent

| Capability | Surface | Mechanism | Confidence |
|---|---|---|---|
| Per-page citation panel | jContent header action, right drawer | `action` / `headerPrimaryActions:N` + component, portal drawer, `useNodeChecks` with `showOnNodeTypes: ['jnt:page','jmix:mainResource']` | proven in page-audit |
| Citation mix by source type | Site GEO dashboard, opening panel | Their Types view relocated, with our own domain broken out of the Brand bucket and "Not categorised" shown rather than redistributed | screen exists, reuse it |
| Competitor by engine matrix | Site GEO dashboard panel | Shape of their visibility heatmap, over a curated actor list | shape proven in their product |
| Site GEO dashboard | Site administration | `adminRoute` / `administration-sites:N`, with `render` not `iframeUrl` | documented, not yet run |
| Cited/uncited badge in listings | jContent content table column | `tableConfig` exists on `accordionItem` but its properties are not enumerated | unverified, inspect `data-registry-*` on a live instance |
| GEO tab while editing | Content Editor header tab | `action` / `editHeaderTabsActions:N` + `displayableComponent` | documented, jExperience uses this exact target |
| "Prompts to answer" queue | Custom accordion in jContent | `accordionItem` targeting `jcontent:N` | documented, changed in 8.2, check upgrade notes |
| Pre-publish GEO check | Publish menu entry | `action` / `publishMenu:N` | documented |
| llms.txt body generation | jContent > Additional > SEO | Whatever `llms.txt manager` uses. Read Jahia/llms and match it rather than inventing a second placement | precedent in a shipping module |
| Site GEO rollup, alternative | jContent > Additional, sibling of SEO | Same mechanism as the llms.txt manager panel | proven by a module in the store |

"Proven" means read out of working page-audit source. "Documented" means in Jahia 8.2 docs but
not exercised by us. "Unverified" means neither. Establish ground truth with
`registry.find({target:'...'})` in the console of a running 8.2.1+ instance before designing
around it.

---

# Wave 1, no partner dependency

## GEO-1 · Am I readable by AI crawlers at all?

**BUILT, with a deliberate deviation.** Jahia-native.

**As a** content editor, **I want** a GEO readiness score on the page I am editing, computed from
the same criteria the AI-visibility tools use, **so that** I can fix what blocks a model from
quoting me before I publish.

**Acceptance**
- Score out of 100 with the four families broken out, mirroring their Authority / Content /
  Structure / Technical split so the two are comparable.
- The nineteen non-Authority criteria computed in Jahia, on the draft, with no page quota. The
  five Authority criteria shown as imported signals and labelled as such.
- Every finding says what is wrong and how to fix it, in the editor's UI language.
- The UI states plainly which criteria are not covered and why. It never implies a full audit.
- Runs against the draft, not the published page.

**What shipped instead.** A count of checks, not a rating out of 100. Eighteen checks in three
groups - the eighteenth, `guestReadable`, is added only when the repository could answer, so a
drawer can legitimately show seventeen - each reading one fact already in the report and shown next to that fact, with three
severities (critical, important, advisory). The reason is trust: an editor can disagree with a
specific line, but not with a number they cannot see inside. The four-family split was dropped
with it, so the two scores are no longer directly comparable to BotRank's. That is a conscious
trade and it should be revisited only if the partnership makes comparability worth something.

One rule is deliberately absent. Being disallowed in robots.txt is not counted as a failure,
because refusing a crawler is a legitimate decision. What is counted is a disagreement between the
policy and what the server actually does, since one of the two is then wrong.

**Data** none, server-side fetch. **Surface** page drawer. **Effort** was S.

## GEO-2 · Stop hand-writing my llms.txt

**BUILT.** Jahia-native, builds on the community `llms.txt manager` module.

**As a** site manager, **I want** the llms.txt body proposed from my actual site structure, with
editorial control over what it includes, **so that** I am not maintaining a curated map of my site
by hand in a text box.

**Acceptance**
- Proposes a body from published pages, honouring noindex, language and role visibility, as the
  sitemap module already does. *(done: the walk runs in a guest session, so role visibility and
  noindex both fall out of it)*
- The editor curates before saving: include or exclude a section, override a page's one-line
  summary. Never overwrites hand edits silently.
- Writes through the existing `llms.txt manager` module rather than serving a competing file. Two
  modules answering `/llms.txt` is a bug, not a feature.
- Flags drift: "14 pages published since this file was last generated".
- ~~Stretch: `llms-full.txt`~~. **Not possible on this stack.** The community `llms` module
  declares one mixin with one property (`jmix:llms` / `j:llms`) and one servlet. There is no
  second property and no second route, so no amount of editing produces an `llms-full.txt`. The
  report says the file is absent, which is true but reads as a to-do. It is really unsupported by
  the installed module. Closing this would need a change in Jahia/llms, or our own servlet, which
  reintroduces the two-modules-one-file problem.

Generation is deterministic: no model call, no external service, so the same site produces the
same file every time. The write is never silent: generate shows a line diff against what is
stored, apply needs a second click, and what gets written is exactly the text on screen, so hand
edits made before applying survive.

**Data** JCR only. **Surface** page drawer, write side. **Depends** Jahia/llms (community).
**Effort** was S to M.

## GEO-3 · Let the AI bots in on purpose

**BUILT.** Jahia-native.

**As a** site manager, **I want** the AI crawler user agents presented as named, toggleable
choices in robots.txt, **so that** I decide deliberately who may read my content instead of
discovering months later that GPTBot was blocked.

**Acceptance**
- GPTBot, OAI-SearchBot, ChatGPT-User, ClaudeBot, Claude-SearchBot, PerplexityBot,
  Perplexity-User, Google-Extended, GoogleOther, CCBot each listed by name with a plain-language
  description. *(done, fifteen crawlers, each saying what blocking it costs)*
- Shows the currently effective directive per bot, not just the file. *(done, and beside it what
  the server actually did when we fetched as that crawler)*
- Warns when a rule blocks a bot the site is otherwise trying to be visible in. *(done, see the
  search-versus-training split below)*

**How it shipped.** Fifteen crawlers, each with an allow or block choice, merged into the site's
existing `robots.txt` rather than replacing it. The two choices are deliberately not symmetric:
block replaces that crawler's rules with a site-wide `Disallow: /`, while allow only undoes a
site-wide refusal. A crawler carrying path rules such as `Disallow: /docs/` already allows the
crawler, just not everywhere, and flattening that to `Allow: /` would throw away the operator's
work.

Everything the module was not asked about is left byte for byte: the wildcard group, comments,
`Sitemap:` lines, crawl delays, and any group naming an unmanaged agent. Merging is stable, so
applying the same choices twice produces no phantom diff.

The merge is line-based, not a full RFC 9309 rewrite. It does not reorder, deduplicate or tidy,
because a robots.txt an operator wrote by hand should come back recognisable.

**Search versus training is the distinction that matters.** Seven of the fifteen answer questions
(OAI-SearchBot, ChatGPT-User, Claude-SearchBot, PerplexityBot, Perplexity-User, Bingbot,
Amazonbot): blocking one of those takes the site out of that assistant's replies. Seven only
collect content to train models (GPTBot, ClaudeBot, Google-Extended, CCBot, Bytespider,
Applebot-Extended, meta-externalagent): refusing those is a normal decision that costs no
visibility. GoogleOther is classed as mixed because its use is not published, which is more honest
than picking a side. Each crawler carries a one-line description of what it does and what blocking
it costs, and the panel warns only when an answering crawler is blocked, whether by the file or by
the server.

**Data** the site's own robots.txt. **Surface** page drawer, write side. **Effort** was S.

## GEO-16 · Prove an AI crawler can actually read this page

**BUILT.** This is `CrawlerCheckServlet` plus the AI Readiness drawer in this module.

**As a** content editor or site manager, **I want** Jahia to fetch my published page the way an AI
crawler would (no session, neutral user agent, no JavaScript) and show me what the crawler
actually receives, **so that** I find out the delivery tier is blocking them before I spend a
quarter writing content nobody can read.

**Acceptance**
- Server-side fetch of the published URL with a neutral UA and no cookies, reporting the HTTP
  status. A 403 or a challenge page is a critical finding, not a warning. *(done)*
- Repeats the fetch as each named AI crawler UA and reports per-bot differences. That is how a WAF
  rule gets caught. *(done, fifteen crawlers plus the control)*
- Checks the initial HTML, not the rendered DOM, for H1, meta description, canonical, nav links
  and body text, catching client-side-only rendering. *(done)*
- Compares initial-HTML text volume against the rendered page and flags a large gap. *(done)*
- Cross-checks robots.txt intent against what actually happened, so a bot that is allowed but
  blocked, or reachable but disallowed, is called out. *(done, and not in the original scope)*
- Runs on publication as well as on demand. *(not done)*
- Says plainly which layer is at fault: robots.txt, WAF or rendering, because the fix belongs to
  three different teams. *(partly done)*

**Why this mattered more than the scoring.** Every other technical criterion is a matter of
degree. Crawler access is binary. If the delivery tier returns 403 to GPTBot, or the content only
exists after JavaScript runs, the page scores zero citations no matter how good it is, and every
other finding is noise. It is a total-failure condition hiding inside a product where nothing else
is.

It is also invisible from page-audit by construction: page-audit renders the page in an iframe
using the editor's own session, which is the wrong lens. The editor is authenticated, runs
JavaScript, and is never challenged by the WAF.

**Known gap.** Vanity URLs are host-dependent by Jahia design. The rewriter only emits one when
the request's server name resolves to the page's site, so on a local instance where many sites
share `localhost` the report falls back to the `/sites/<key>/...` form, which is the form that
actually works on that host. Verified on 8.2.3.2. See `README.md` for the full list.

---

# Wave 2, needs the citation data

Gate: a real API contract. Do not start until the open questions below are answered.

## GEO-4 · Is this page one that AI engines quote?

The keystone. It is the only story that puts external AI-visibility data on a specific node, and
every later story reuses its plumbing.

**As a** content editor, **I want** to see, on the page I am editing, whether AI engines cite it,
which engines, for which questions, and how that has moved, **so that** I know whether this page
is earning its keep in AI answers.

**Acceptance**
- Citation count for this exact URL, matched on the node's published absolute URL per language,
  with a trend over the period.
- The prompts this page is cited for, listed and clickable.
- Uses the node's own title, not the title BotRank extracted. Theirs falls back to a body snippet
  and looks broken.
- Header reads "as of <date>" from the last run timestamp. No refresh button that cannot deliver
  fresher data.
- A page with no citation record says "not cited yet", never "0".
- Works for the published URL. States clearly that a draft has no data yet.
- Shows where this page stands against the site's other cited pages, so "3 citations" has a scale.

**Data** `list-cited-pages`, `get-site-citation-share-history`. **Surface** page drawer.
**Effort** M.

## GEO-5 · Tell me when a page stops being cited

**As a** content manager, **I want** to be told when one of my pages loses its citations, **so
that** I can look at what changed while the loss is still recoverable.

**Acceptance**
- Two comparable windows are diffed nightly. Drops above a configurable threshold raise an item.
- Each item links straight to the node in jContent, not to an external dashboard.
- Correlates the drop with the page's own publication history: "this page was edited two days
  before the drop".
- Detection is polling. The UI never implies real time.

**Data** `list-cited-pages`, two windows. **Surface** dashboard and accordion. **Effort** M.

## GEO-6 · Which questions am I losing, and to whom?

**As a** content strategist, **I want** the tracked questions where a competitor is cited and we
are not, ranked by demand, **so that** my editorial backlog is driven by demand rather than by
opinion.

**Acceptance**
- Table of prompt by engine by who is cited, filtered to "competitor yes, us no". The competing
  brands are already a field on the prompt record, so no computation is needed.
- Sorted by the prompt's volume band (LOW, MEDIUM and so on) and intent class. Not by an absolute
  search volume, because that number is not exposed.
- Each row offers "create content for this", creating a real node with the question as a working
  title.
- Engine coverage is stated. If the site's plan tracks 3 of 8 engines, the UI says so.

**Data** `list-prompts`, `get-prompt-visibility-history`. **Surface** accordion or dashboard.
**Effort** M.

## GEO-7 · Show me the answer, not the score

The demo moment. Showing the editor the sentence Perplexity actually wrote lands harder than any
chart.

**As a** content editor, **I want** to read the actual sentences ChatGPT or Perplexity wrote about
us, **so that** I can see the gap between what the model says and what we actually publish.

**Acceptance**
- The verbatim extracts mentioning us, each with its source prompt, engine, date and sentiment
  label. Filterable by sentiment, as they already are upstream.
- Negative extracts are reachable in one click, because those are the ones that need an editorial
  answer.
- Third-party provenance shown where the extract carries it. That is the evidence our page was not
  the source.
- Sources that resolve to our own site are visually distinguished from third-party ones. The case
  where none of them is ours is called out explicitly, because that is the common case and the
  actionable one.
- Response text is treated as untrusted third-party content and escaped, never rendered as markup.

**Data** `get-last-prompt-responses`, `get-prompt-responses`. **Surface** drawer or dashboard
detail. **Effort** S.

## GEO-13 · My Japanese site is invisible and nobody told me

The most Jahia-shaped story in the set, and the one to lead the partnership with.

**As the** contributor who owns one language of a multilingual site, **I want** AI visibility for
my language, next to the content I actually edit, **so that** I stop being measured by a global
average that hides my market entirely.

**Acceptance**
- Every visibility figure is scoped to the language the contributor is editing in, matching
  jContent's existing language switcher.
- States plainly when a site language has no tracking at all. That is the common case and it is
  invisible in BotRank's own global score.
- Side by side comparison across the site's active languages, so a 100% / 0% split is impossible
  to miss.
- Never averages across languages into a single site number without saying so.

**Why it leads.** On the tenant we looked at, a brand scoring 100% on a French question scored 0%
on the same question in Japanese. BotRank surfaces that as rows in a 50-slot list sorted by
nothing in particular, and the global score on their dashboard reads 56% and hides it completely.
Jahia's contributors are organised by language. That is the shape of the product and of the teams
using it. A GEO panel that is language-aware by construction is something a general-purpose GEO
dashboard structurally cannot be, and it costs almost nothing on top of GEO-4 and GEO-6.

**Data** `list-prompts`, grouped by locale. **Surface** page drawer and dashboard. **Effort** S on
top of GEO-4 and GEO-6.

## GEO-14 · Write for the question the engine actually asked

**As a** content editor, **I want** the literal sub-queries AI engines generated while answering a
question I care about, **so that** I can write the paragraph that answers them instead of guessing
at intent.

**Acceptance**
- Query fan-outs for a tracked question, with their frequency, shown as an editorial brief rather
  than a table of metrics.
- Flags fan-outs naming competitor products. Those are comparison questions our page probably does
  not answer at all.
- Checks the fan-out phrasing against the page's actual text and says which are unanswered.
- Never auto-inserts the phrases into content. It suggests, the editor writes.

**Data** query fan-outs per prompt. No MCP tool is named for this. Blocked on the API question
below. **Surface** page drawer, beside SEO assist. **Effort** S, high perceived value.

## GEO-15 · Say it on our page before someone else says it for us

The sharpest editorial action in the set.

**As a** product content owner, **I want** to know which claims AI engines make about my product
that my own page does not state, **so that** I can write the missing sentence instead of leaving a
blog to be the source.

**Acceptance**
- Extracts about this product are matched against the page's own text. Claims the model makes that
  the page does not contain are listed as gaps.
- Names where the model appears to have got it (a competitor, a blog, a resale site) when the
  extract carries provenance.
- Flags claims that are wrong or outdated as well as merely absent, since a page can be the
  corrective source.
- Never drafts the claim as fact automatically. A model asserting our product does something is
  not evidence that it does. The editor confirms, then writes.

**Data** chat extracts plus page text. No MCP tool is named. **Surface** page drawer, GEO tab.
**Effort** M, needs an LLM compare step.

---

# Wave 3, close the editorial loop

Depends on Wave 2's cached citation data being reliable. This is where the integration stops being
a dashboard and starts changing behaviour, and it is the part a competitor cannot copy by
embedding an iframe.

## GEO-8 · A GEO to-do list that lives where I work

**As a** content editor, **I want** BotRank's recommendations as tasks in jContent, resolved
against the nodes they concern, **so that** I do not maintain my editorial work in two tools.

**Acceptance**
- Routed by category, not dumped in. Content tasks reach the editor of the named node. Technical
  tasks reach a site manager. Authority tasks are third-party outreach and belong outside jContent
  entirely. Scope tasks can only be a deep link, since prompt editing is theirs.
- Each task resolves to a JCR node where the scope is a URL or product we own. Their instruction
  bodies do name specific pages.
- Tasks that cannot be resolved to a node are shown, not silently dropped.
- Task language is theirs and mixed FR/EN. Present it as quoted guidance, never machine
  translated, since the instructions specify exact wording.
- Completion is tracked locally. The MCP is read-only and their board also accepts user-authored
  tasks, so the two will diverge in both directions. Say so in the UI rather than implying a sync.

**Data** `get-recommendations`. **Surface** accordion and node action. **Effort** M.

## GEO-9 · Warn me before I publish something that will cost citations

**As a** content editor, **I want** a warning at publish time when I am about to change a page that
AI engines currently cite, **so that** I do not quietly break the passage that was being quoted.

**Acceptance**
- Non-blocking notice in the publish flow when the node is a currently cited URL.
- Names the engines and questions at stake.
- Never blocks publication. It informs only.
- Absent entirely for pages with no citation record.

**Data** cached `list-cited-pages`. **Surface** `publishMenu` action. **Effort** S.

## GEO-10 · Did my rewrite actually work?

**As a** content manager, **I want** to see citation and visibility movement against the dates we
published changes, **so that** I can tell my stakeholders whether the editorial work paid off.

**Acceptance**
- Citation-share trend with publication events from JCR marked on the same timeline. BotRank
  already plots its own completed GEO tasks this way, so the differentiator is specifically our
  events, which they cannot see.
- Marks the actual node and language published, so a drop can be traced to a specific edit.
- Correlation is labelled as correlation. No causal claim in the copy.
- Honest about the lag. Engines refresh on their own schedule, not ours.

**Data** `get-site-citation-share-history` plus JCR publication history. **Surface** site
dashboard. **Effort** M.

---

# Wave 4, opportunistic

Real value, narrower audience. Build if a specific customer or prospect asks. Do not build
speculatively.

## GEO-11 · What are the engines saying about us?

**As a** brand or comms manager, **I want** the sentiment and recurring themes the engines attach
to our brand, traced back to the source that caused them, **so that** I can go fix the article
that is poisoning the well.

**Acceptance**
- Sentiment score with its band, the three-class distribution, and named themes with counts. All
  of this exists upstream.
- Negative themes lead, with their counts. "Increased industrial production, 15 mentions" is the
  item a comms team acts on.
- Themes drill down to the extracts that produced them, and to the external source where the
  extract carries one.
- Terms are deduplicated across languages before display.

**Data** sentiment, themes, extracts. No MCP tool is named. **Blocked** on confirming the data is
reachable over the API at all. **Surface** site dashboard. **Effort** M.

## GEO-12 · Which of my products win the AI shelf?

**As an** e-commerce content manager, **I want** presence, win rate and rank for my products in AI
shopping answers next to the product content in jContent, **so that** I can improve the products
that appear but never win.

**Acceptance**
- Presence as a fraction ("3 AI answers out of 63"), not a bare percentage. Win rate suppressed
  below their sample floor rather than shown as 0%.
- Own products distinguished from competitors'. Competitors include cheap direct-to-consumer
  entrants, which is often the surprise.
- Prices shown per market in their own currency, never aggregated.
- Only appears on sites where a product content model is actually in use.

**Why this points at a second buyer.** On one product, the merchant column read: the brand itself,
plus three resale platforms, with an observed price range of 2,214 to 4,000 euros. Elsewhere:
Vestiaire Collective, eBay, Amazon sellers, noon.com. So AI shopping answers are quoting the
brand's products at resale prices, through merchants the brand never authorised, in markets it may
not have chosen. That is a brand-protection and distribution-governance question, and the person
who cares about it is legal, brand or channel management, not the content marketer. For a DXP
vendor that is a second door into an account.

It stays in Wave 4 for a general audience. For a commerce or luxury prospect it jumps to the
front.

**Data** `list-observed-products`. **Surface** product node drawer. **Effort** M, narrow audience.

---

# Explicitly not building

| Tempting | Why not |
|---|---|
| Surfacing BotRank's 0-100 audit score | Its page slots are fed by clicking "Analyze" on cited pages one at a time, capped at 5 or 25, computed on the published page from outside. It duplicates a score page-audit already produces on the draft, unlimited. Two disagreeing scores for one page is worse than one. The exception is the five Authority criteria |
| Passing a "Bad" status through unexplained | Their check marks a luxury house's homepage "Bad" on HTTPS, most likely for a missing HSTS header. A red status a contributor cannot act on, on a page that is fine, spends trust we only get to spend once |
| Passing their extracted page titles through | Their title extraction falls back to a body snippet often enough to look broken. We own the node. Use its title and treat their URL as the only field worth reading |
| Embedding their dashboards in an iframe | Fast, and it teaches contributors nothing about their own content. Also the single most likely thing to break: CSP and X-Frame-Options behaviour in the back office is unverified, more so behind Jahia Cloud's ingress |
| Surfacing their Authority tasks as content to publish | Their board includes prepared replies for named Reddit threads, and outreach briefs written to be placed on third-party editorial sites. Jahia handing a contributor generated text to post on Reddit as an apparently independent voice is a different act from helping them improve their own site. Surface Authority tasks, if at all, as read-only PR guidance for a comms team. Never as drafted content with a copy button |
| Proxying "Bob", their GEO agent | A positional call, not a not-invented-here one. Half his real usage is meta tags, schema.org, headings, hreflang and FAQ blocks, which page-audit already assists with, grounded in the live DOM, beside the field, in the page's language. The other half is outreach we have decided does not belong in jContent |
| Writing BotRank config from Jahia | Not possible. The MCP is read-only by explicit design. A "track this page" button would have to be a link into their UI, which is honest and fine |
| Real-time anything | Upstream refreshes weekly, or daily at best. Any live-feeling UI would be a lie told to a contributor |
| Leading the panel with the visibility score | It is the number they lead with, and it is mostly not ours to move. A brand can sit at 100% visibility with zero citations of its own site. Leading with it makes the panel decoration. Citations first, visibility as context |

---

# Open questions

## For BotRank

These were sent by email. Answers gate Wave 2.

1. Is there a REST API distinct from the MCP server, or is "API & MCP" one product? Pricing lists
   them as a single line item, and `api.botrank.ai` resolves but publishes nothing.
2. Full tool schemas: parameter names and types, response fields, pagination, error model. The
   thirteen names are public, nothing else is.
3. **Are Perception, Technical and the fan-outs exposed over the API?** All are first-class in the
   app, and Perception is the richest dataset in the product. None appears among the thirteen MCP
   tools. GEO-11, GEO-14 and GEO-15 are all blocked on this one answer, which makes it the first
   question.
4. Auth mechanism and transport. API key, OAuth or session? Streamable HTTP, SSE or stdio?
5. What is the real refresh cadence? The site says daily, a prompt's run log shows roughly weekly.
   Our sync schedule and every freshness label depend on the answer.
6. Rate limits and quota accounting. A nightly per-site sync across a customer base is a very
   different read pattern from one marketer in Claude Desktop.
7. Confirm the slot arithmetic for a multilingual site. A prompt row appears to be question by
   locale, so one question across five Jahia site languages costs five of fifty slots. If so, a
   large multilingual customer needs a quote sized by language count, and that belongs in the
   commercial conversation from day one.
8. Confirm SoC is share of the total citation pool. The Types view exposes a 5,149-citation
   denominator, and 54/5,149 reproduces the stated 1%. Worth confirming, and worth knowing whether
   the denominator moves with the prompt set.
9. What are the two numbers in the citations column? Each source shows a pair such as 115 / 590.
10. Are the analysis screens addressable by URL? The app appears to route in client state only. If
    there is no deep link, then "open this in BotRank" from jContent is impossible and every hand
    off has to be a data hand off.
11. Does "Analyze" on a cited page consume a permanent page slot, or is it a one-off run?
12. What are the source Type values, in full? Nine were observed and the Types view implies about
    thirteen. It is the most useful undocumented field in the product, and "Not categorised" is
    11.8% of citations.
13. Entity model and access control. One Jahia site should map to one BotRank entity. How many
    entities does Business include, and can access be scoped per entity?
14. Any webhook or push on the roadmap? Absent one, we design for polling and say so in the UI.
15. Can the five Authority criteria be read per page over the API, independently of the audit's
    page quota? That is the only part of the audit we want, and the part least likely to be quota
    bound since four of the five are domain-level lookups.
16. Is the 0-100 weighting published, and what does per-criterion "importance" mean numerically?

## For us

1. **Module or platform capability?** This changes almost everything downstream: multi-site config
   UI, licensing, support commitments, upgrade guarantees, and whether a partner's API may sit on
   a critical path. Wave 1 is deliberately identical under both tracks, so the decision can stay
   open a while longer, but the two diverge right after it.
2. **Who holds the credential?** One Jahia-owned account for demos is a different module from a
   customer bringing their own key, which is different again from Jahia reselling capacity.
3. **Does the back-office CSP allow what we need?** Verify framing and outbound behaviour on a real
   8.2 instance and on Jahia Cloud, including whether Cloud filters egress to a partner API. This
   is the classic late surprise.
4. **Are content-table columns and node badges extensible?** `tableConfig` exists but its
   properties are not documented. Inspect `data-registry-*` and `registry.find()` on a live
   instance before promising the listing badge.
5. **What is the 8.2 upgrade exposure?** Custom accordions, content tables and the Redux current
   path all changed in 8.2.0. page-audit already survives this. Copy its choices rather than
   rediscover them.
6. **Is llms.txt a module or a platform feature?** If it belongs in the platform, Wave 1 splits.

---

# Risks

In rough order of how likely they are to bite.

| Risk | Shape | What reduces it |
|---|---|---|
| The API contract never materializes | No public docs, quote-only tier, small young company. The integration could stall indefinitely on a document that does not exist yet | Wave 1 ships without them. Build against a thin internal abstraction so a second GEO vendor is a driver swap, not a rewrite |
| Vendor lock-in of the UI | If BotRank's vocabulary leaks into our node types and GraphQL schema, the panel becomes theirs, not ours | Model the domain in Jahia's terms (cited page, tracked question, engine) and keep their field names inside the driver |
| Machine-extracted entities reach the contributor unfiltered | Their discovered "market actors" include generic phrases, parent companies and adjacent-category brands. Passing that straight through names nonsense competitors to the person least able to judge it | Curate before display: filter by relationship classification, apply a visibility floor, let a site manager hide an actor. Never present the raw discovered list as "your competitors" |
| Scraped data is probabilistic | Browser-simulated sessions against consumer chat interfaces. Numbers move for reasons unrelated to our content, and a contributor gets blamed | Show trends, not single-day values. Never present a day-over-day change as the result of an edit |
| Page quota makes site-wide coverage impossible | 5 or 25 audited pages is meaningless for a site with thousands. Citations are uncapped, audits are not | Never build a feature on the audit quota. Wave 1 covers page scoring natively and without limit |
| Back-office CSP and iframe behaviour | Any embedded external view can fail silently in jContent, differently on Jahia Cloud | Render our own components against our own servlet. No `iframeUrl` in the critical path |
| Two scores for one page | A BotRank score and a page-audit score that disagree destroys trust in both | One score, ours, computed on the draft. BotRank contributes citation facts, not a competing grade |
| Untrusted third-party content in the UI | Engine responses and cited-source titles are text written by systems outside our control, rendered inside the authoring app | Escape and treat as data, never as markup or instructions. page-audit's server-side field whitelist is the pattern to copy |
| GDPR and data flow | Their hosting is Azure EU and their agent runs on Azure OpenAI, which is favourable, but we would be sending customer site URLs to a subprocessor | Document the flow before any customer pilot. No ISO 27001 or SOC 2 is claimed on their site |

---

# Provenance

Jahia-side statements marked "proven" were read out of the page-audit source and README in
`~/Runtimes/0.Modules/page-audit`. Extension points marked "documented" come from Jahia 8.2
Academy documentation and were not exercised.

BotRank's screen inventory and metric names were read from the running app at `app.botrank.ai` on
8 September 2026, on the account we have access to, across two brand tenants. Everything else
about BotRank comes from their public site. **They publish no API reference, so no statement here
about their endpoints, schemas, parameters or auth should be treated as a specification.**

One claim in the first draft, that Jahia had no llms.txt support, was wrong. The community
`llms.txt manager` module (v1.0.0, August 2026, min 8.1.5.0, github.com/Jahia/llms) publishes and
serves the file per site from jContent > Additional > SEO. It is a manual editor, not a generator,
which is why GEO-2 is scoped as generating the body that module publishes.

The full narrative version of this analysis, with the screen-by-screen product walk, is the
published page at
https://claude.ai/code/artifact/6e3ee49c-8b20-4fde-9a3e-e8ba9cc6afd8
