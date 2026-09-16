# The checks

Eighteen checks, in three groups. This is the reference; the module itself shows the same text in
the interface, in English and French.

**Severity** is about consequence, not effort.

| Severity | Meaning |
|---|---|
| **Critical** | An AI crawler cannot read the page at all. Everything else is noise until this is fixed. |
| **Important** | It can read the page, but materially less well than it should. |
| **Advisory** | Worth doing. Not worth blocking a release for. |

Two rules govern what is *not* here.

**Being disallowed in robots.txt is never a failure.** Refusing a crawler is a legitimate
decision, and a tool that reports a deliberate choice as a defect is one people stop reading. What
is reported is a *disagreement* between the file and the server, because then one of the two is
wrong.

**The site scan evaluates 17 of these, the page drawer 18.** Comparing what a crawler receives
against what a browser receives needs a second fetch of the same page, so that check is only
evaluated one page at a time. Both report the same failures; only the denominator differs.


## Can a crawler reach it

Nothing below matters if these fail.

### `reachable` · Every AI crawler gets the page

**Critical.** A refusal is usually a firewall or bot-protection rule rather than anything about the content, so the fix belongs to whoever owns the delivery tier.

*Shown in the interface as:* “At least one crawler was refused. That is usually a firewall or bot-protection rule rather than a content problem.”

### `policyMatchesReality` · robots.txt matches what the server does

**Important.** One of the two is wrong. Either the file permits a crawler the server turns away, or it forbids one the server serves anyway.

*Shown in the interface as:* “The policy and the server disagree for at least one crawler. One of the two is wrong, and the report names which.”

### `noClientRedirect` · No client-side redirect

**Critical.** A crawler does not follow a meta refresh, so the shell it lands on is the whole page as far as any model is concerned.

*Shown in the interface as:* “The page tells a browser to go elsewhere. Crawlers do not follow that, so this shell is all they read.”

### `namedInRobots` · AI crawlers named in robots.txt

**Advisory.** Without a named group the AI crawlers fall to the wildcard, which means no explicit decision has been taken about them.

*Fails when:* No AI crawler named anywhere in robots.txt.

*Shown in the interface as:* “No AI crawler is named. They fall to the wildcard group, so you have no explicit policy for them.”

### `guestReadable` · A visitor with no account can read it

**Critical.** Absolute. No amount of editing makes a gated page citable, and some servers answer one with a login form and a 200, which looks like success from outside.

*Shown in the interface as:* “A guest cannot read this page, so no crawler ever will. Some servers answer a gated page with a login form and a 200, which looks like success. Check the page's access restrictions.”


## Is what arrives usable

Read from the initial HTML, before any JavaScript runs.

### `contentInInitialHtml` · Real text in the initial HTML

**Critical.** Crawlers do not run JavaScript. A page whose content only appears afterwards is perfect to an editor and empty to a model.

*Fails when:* Fewer than 50 words of real text.

*Shown in the interface as:* “The page returns almost no text before JavaScript runs. Server-side rendering is the fix, not a robots.txt change.”

### `sameContentForCrawlers` · Crawlers get the same text as a browser

**Critical.** A gap here means the server varies its response by user agent, which is usually a bot rule nobody remembers adding.

*Fails when:* Any crawler receiving less than half the control browser's word count.

*Shown in the interface as:* “At least one crawler receives far less text than the browser did. Check what the server varies on the user agent.”

### `title` · Title present and a usable length

**Important.** The title is what gets quoted. Too short says nothing, too long is truncated wherever it appears.

*Fails when:* Missing, under 10 characters, or over 70.

*Shown in the interface as:* “A title under 10 or over 70 characters is either uninformative or truncated wherever it is quoted.”

### `singleH1` · Exactly one H1

**Important.** Zero or several leave the subject of the page ambiguous to anything parsing it.

*Fails when:* Zero or more than one.

*Shown in the interface as:* “Zero or several H1 headings leave the main subject of the page ambiguous.”

### `headingOutline` · Sub-headings structure the page

**Advisory.** Retrieval splits pages on headings. Without them the page is one undifferentiated block.

*Fails when:* No H2 at all.

*Shown in the interface as:* “Retrieval splits pages on headings. A page with no H2 is one undifferentiated block.”

### `metaDescription` · Meta description present

**Important.** Without one, the summary shown beside an answer is generated from whatever text happens to come first.

*Shown in the interface as:* “Without it the summary shown alongside an answer is generated from whatever text comes first.”

### `canonical` · Canonical URL declared

**Important.** Without it, the same content at several addresses is counted as several pages and the signal is split.

*Shown in the interface as:* “Without a canonical, the same content reachable at several addresses is treated as several pages.”

### `langDeclared` · Page language declared

**Important.** The language has to be guessed otherwise, which on a multilingual site goes wrong quietly.

*Shown in the interface as:* “The html lang attribute is missing, so the language has to be guessed. On a multilingual site that is a real risk.”

### `structuredData` · Structured data present

**Important.** Schema types are the most direct way to state what a page is about, rather than leaving a model to infer it.

*Shown in the interface as:* “No JSON-LD was found. Schema types are the most direct way to state what this page is about.”

### `freshness` · Modification date published

**Advisory.** Assistants weigh recency, and a page with no date cannot show it is current.

*Shown in the interface as:* “No dateModified was found. Assistants weigh recency, and an undated page cannot show it is current.”

### `imageAlt` · Images carry alt text

**Advisory.** To a crawler that reads only text, an image with no alt text does not exist.

*Fails when:* Fewer than 80% of images carry non-empty alt text. A page with no images passes.

*Shown in the interface as:* “Some images have no alt text. To a text-only crawler those images simply do not exist.”


## Site-level files

The same answer for every page on the site.

### `robotsPresent` · robots.txt is served

**Important.** No file means everything is allowed by default, which is a decision nobody took.

*Shown in the interface as:* “No robots.txt. Everything is allowed by default, which is a decision you did not get to make.”

### `llmsPresent` · llms.txt is served

**Advisory.** It is the one file that tells an assistant which pages matter and why.

*Shown in the interface as:* “No llms.txt. It is the one file that tells an assistant which pages matter and why.”


## The sitemap comparison

Not one of the eighteen, and on its own tab rather than inside the score. It answers a different
kind of question - not "is this page readable" but "does the map we hand crawlers match the site we
actually publish" - nothing in it is a check a page passes, and none of it moves the percentage.
It refreshes on its own too: resolution costs no requests, so it never needs to wait for a walk of
every page. The scheduled scan also refreshes it.

Three things have to be true before any of it means anything, and they are reported apart because
they call for different responses: nothing answers `/sitemap.xml`, something answers but it is not
a sitemap (a proxy catch-all or an error page served with a 200 - which would otherwise parse to
zero entries and make every published page look missing), or it could not be reached at all.

Both sides belong to us, so the comparison is resolution, not fetching. Every `<loc>` in
`sitemap.xml` is resolved back to a repository node; the published set is built as **guest**, in
**every language the site has**, because a sitemap is language-aware and an anonymous crawler is
what it is written for. Five findings come out of it:

| Finding | Means |
|---|---|
| `missing` | Published, guest-readable, and not in the sitemap. Crawlers are not being told it exists. |
| `unknown` | In the sitemap, resolves to nothing published. A stale entry. |
| `staleDate` | The entry's `lastmod` disagrees with the node's real modification date. Shown as `sitemap date -> real date`. |
| `noindexListed` | Listed in the sitemap and carrying `noindex`. The two files contradict each other. |
| `redirects` | The page has a vanity URL and the sitemap names the address Jahia redirects away from. |

`agrees` is true only when all five are empty.

The drawer carries three of these per page, because each calls for a different
action: the page is absent from the map, the map advertises it while the page says `noindex`, or
the map's date for it is wrong (shown as the two dates). Matching is on path **and** language, so a
finding that belongs to the French URL does not appear on the English page.

`redirects` exists because the sitemap module does not use vanity URLs while the page does. Left
alone that reads as two unrelated findings - the real address missing, the listed one unknown - when
it is one thing: crawlers are being sent through a redirect to reach content that has a direct
address, and search engines want the final URL in a sitemap. The pair is recognised and reported
once.

**Redirects within the sitemap's own entries are not detected.** Resolution cannot see them. A sitemap entry that 301s to another
page resolves to whatever it names and is reported as clean.

### Why the page count and the entry count do not match

A scan of the luxe site scores **13 pages** and compares **378 sitemap entries**. That is not a
defect in either. The site walk lists `jnt:page` nodes. The sitemap lists everything with a public
URL, which on that site is mostly `jmix:mainResource` content - blog posts and property listings
that have their own address but are not pages.

So the two numbers count different things on purpose, and the sitemap comparison is the only part
of the scan that sees the whole addressable site. Scoring the content items as well is a separate
piece of work, not a bug fix.


## Pages nothing links to

Also not one of the eighteen, and also its own tab. A crawler can report what it found; it cannot
report what it missed, because it never knew the page existed. The repository knows what is
published, so the gap between that and what the site links to is knowable.

Links are read from the **rendered HTML** of every page the scan fetches, which is the only thing
that decides whether a crawler can follow them. That makes a menu built from the page tree, a
listing that queries content, a rich text link and a configured button all count identically, and
it costs no request the scan was not making anyway. Repository references are added for sources the
scan never rendered - an article linking to a landing page - but never for nodes on pages it did,
since the HTML already said whether that link came out in a menu or in the body.

| Finding | Means |
|---|---|
| Unreachable | No page links here and the sitemap does not list it. Nothing will ever find it. |
| Weak | Reachable, but only through a menu that lists everything, or only through the sitemap. |

Navigation and content links are counted apart: a page in a menu that lists every page was not
chosen by anyone, and a crawler weighting links treats it accordingly. The split is by whether the
anchor sits inside `<nav>` or `<footer>`; `<header>` is deliberately excluded, because it is also
used for the heading of a card.

Counted once per source page: linking to the same target twice from one page is one editorial
decision. Only the language the scan read is judged - the other languages' pages were never
fetched, so they have no links *observed*, which is not the same as having none. The site home page
is never reported.


## Is llms.txt still about this site

`llms.txt` is generated once and then served unchanged, so it goes out of date silently: nothing
fails, nothing returns an error, an assistant is simply handed a map of a site that has moved on.
That happened on the test site the moment a vanity URL was added - the file kept pointing at
`/home/agencies.html`, which now redirects, with no signal anywhere.

It is compared against **the generator**, not against a second set of rules. Asking "which pages
belong in it" a different way would be a second implementation to keep in step, and the two would
drift. The served file is diffed against what regenerating would produce right now, which makes the
finding exactly as trustworthy as the button offered to fix it: if they differ, regenerating changes
something; if they agree, it would not.

| Finding | Means |
|---|---|
| `addressChanged` | The listed address now redirects - usually a vanity URL added after the file was written. |
| `noLongerListed` | Still published, but no longer part of what the generator produces. |
| `gone` | Unpublished, or no visitor can read it. |
| not listed | Published and in the current generation, but absent from the file. |

Deliberately **not tied to the readiness score**. Regenerating this file does not move the score -
the score is about crawler access and what arrives in the HTML - so prompting for it there would
send somebody to do something that changes nothing they were looking at.


## How old the content is

Freshness is one of the criteria a crawl-based tool scores a site on, and one of the few it cannot
compute properly: it sees the dates a site chooses to publish, on the pages it happened to crawl.
The repository has the real modification date of everything published.

Reported as a distribution - last month, 1-3 months, 3-6 months, 6-12 months, over a year - and
then twice more, **by content type** and **by section**. Those two breakdowns are the whole design.
Ranking every page by age would put a legal notice next to a news article and call both old, which
is how a freshness report gets ignored; judging each type against its own list is what keeps them
apart. Nothing attempts to decide which types are "evergreen", because that would be a guess at
somebody's content model.

A group is flagged when its **newest** item is past the threshold - not its oldest, not its
average. "Nothing in here has been touched in a year" is actionable. "The oldest item is old" is
true of every site that has ever existed.

The threshold is configurable and remembered, so a scheduled run measures against the same line an
editor chose. Measured per language: a translation carries its own dates.

**How it is drawn.** The distribution is a timeline, read left to right from oldest to most recent,
with the bucket that lies entirely past the threshold in the warning colour: the line the editor
chose is then visible in the picture rather than only in a caption. Each group is a single bar for
how long it has been since anything in it changed, sorted worst first, which is the same number the
flag is computed from - so a flagged group and a long bar say one thing, not two.

An earlier version drew a range per group, newest to oldest with the median marked. It encoded
spread, which is not a question anyone asks of a site, and on most groups the newest and oldest
item share a date, so the range collapsed to a dot. It was removed rather than tuned.

**The full list.** Under the groups, every published item in the language being measured: title,
type, section, public path, the date it last changed and how long ago. Anything carrying no date
comes first, then oldest to newest, so the list opens on the work. Rows open in jContent and page
like every other list here. Long lists are cut at five thousand rows and say so; the counts and the
groups above them still measure everything.

No drawer line, deliberately. An author editing a page already knows how old it is.


## Readiness per language

A site-wide average hides the market that is failing. This is coverage and score for each language
the site declares, side by side.

The rule that shaped it: **a language nobody has scanned is unmeasured, never zero.** A zero in a
comparison table reads as "this market is broken" when it means "nobody has looked", and those need
opposite responses - one is a content problem, the other is a scan that was never scheduled. It is
rendered as plain text rather than as a chip, so it cannot be misread as a low score. The spread
between best and worst appears only when at least two languages have been measured.

Coverage comes from the repository and needs no scan: a node exists in a language when it carries
that language's translation, and the two workspaces separate *translated* from *published*. Scores
come from whatever scan last ran in each language, so two languages scanned weeks apart are not
measured against the same site - which the interface says.

The drawer names the languages a page has no translation in, and any translation that exists but is
not published. Both are things the author in front of the page can act on.


## Structured data from the content model

Every other tool infers schema.org output from rendered text, which means guessing what a page is
about from the words on it. The definition already says what the content *is*: a type carrying
`price`, `images` and `address` is a product listing whatever the prose reads like.

**Defaults only where the answer is not a matter of opinion.** Jahia's own types have one - a page
is a `WebPage`. A custom type is somebody's model, and deciding `luxe:estate` is a `Product` from
the word "estate" is reading intent out of a string. Custom types start unmapped, say so, and are
mapped by the person who defined them.

**Property sources are conventions, and those do hold.** `title`, `description`, `image`, `date`,
`price`, `address`, `phone`, `email` mean the same thing across almost every content model, so a
candidate list per schema property finds them with nothing configured.

**Gaps are output, not failure.** A required property with no source is named. Filling it from
somewhere plausible - the page title, a sibling property, a default - is exactly how structured data
ends up disagreeing with the page it describes.

**Contradiction is checked.** The generated name is compared with the title the page actually
rendered, allowing for the suffix a template adds, so "Fees" and "Fees | Demo Site Luxe" agree while
"Fees" and "Our pricing" do not.

Nothing is injected. The drawer shows the snippet for the page in front of you and a person places
it, ideally in the template for that type rather than on one page.


## The written report

Every section above is a measurement and says so. The report is the one place a model is asked
for judgement, and it is asked on a digest of those measurements, never on page bodies: the last
scan's score, failing check ids and their severity, the worst pages with their failing checks, the
template roll-up, the sitemap, link, address, llms.txt and freshness findings as counts with a few
examples, language coverage and schema coverage. One fact per line, ids rather than prose, and a
hard size cap so a large site cannot run the prompt past the model's window.

The answer is asked for as JSON in a fixed shape: a summary, a verdict, one compliance status per
area (the dashboard's own eight areas), a ranked list of priorities each with a severity, an owner
(editor, developer or administrator), an effort, why it matters and how to do it in this CMS, quick
wins, and a now/next/later roadmap. The server parses it against that shape and nothing else
reaches the browser: strings are clipped, enums are normalised to known values, lists are capped,
and a page path survives only if the digest itself listed it. Everything is rendered as text.

It answers only on a click, is metered per user, and is stored per language so the tab reads back
instantly afterwards. The prose is written in the interface language, which may differ from the
content language being measured; the tab says so when a stored report was written for another.
Export produces Markdown built from the parsed shape, or the shape itself as JSON.

Anthropic, OpenAI and DeepSeek are supported, through the same configuration keys page-audit uses.
Without a provider the tab does not exist and nothing leaves the server.

## What the drawer says about where a page stands

Three facts that live on the dashboard but are about one page, because a score with no reference
point is not information:

- the site average and the page's own **section** average, so "16 of 18" can be read
- how many pages link here, from navigation and from content separately
- whether the page is in **llms.txt** - and if not, whether regenerating would add it or the
  generator deliberately does not include it

Freshness is deliberately absent: an author editing a page already knows how old it is.


## Where each one is computed

Everything in **Is what arrives usable** is read from the HTML of a single fetch, so it costs
nothing extra once the page has been fetched. `guestReadable` is a repository question and needs no
fetch at all. The two site files are fetched once per site, not once per page.

That is why a site-wide scan is one request per page: the expensive part is the request, and
almost every check rides along on it.
