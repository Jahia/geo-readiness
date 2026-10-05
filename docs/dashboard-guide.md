# The site dashboard, for content authors

The page drawer answers for one page. This answers for the whole site: where it is weak for AI
crawlers, which template is to blame, and what the two files that speak for the site currently say.

Open it under **Additional > SEO > GEO readiness**. Ten panels in four groups, measuring one
language at a time.

A companion guide covers the per-page panel: [`drawer-guide.md`](drawer-guide.md).

## Three things that explain everything else

- **Most of it is instant, one part is not.** Nearly every panel is a question put to the content
  store, so it answers as soon as you open it. Only the site score needs to fetch every page, which
  is why it has a Scan button and a schedule.
- **It covers one language at a time.** The language in the title is the one being measured. A
  second language needs its own scan, and a language nobody has scanned reports as *not measured*,
  which is not the same as scoring badly.
- **It is read-only except in two places.** Only the `robots.txt` and `llms.txt` panels write
  anything, both show the exact change first, and both need a second click.

If you cannot see the screen at all, you lack the **publish** permission on the site. Without it
the dashboard does not appear, and nor do the two file editors. That is deliberate: these settings
affect every page.

## Overview

### Site score

The summary of everything else. A headline such as *78% of checks pass across the site*, with the
movement since the last run, so you can tell whether things are getting better.

Underneath: how many pages were scored, how many have a critical failure, and how many no visitor
can read. Then a breakdown by section, so you can see one part of the site dragging the average
down rather than guessing.

There is also a list of the pages with findings, and this is the panel that answers "is it me or
the template?". When a check fails on nearly every page a template renders, it is reported against
the template, not the author.

**The scan.** Press *Scan now* for an immediate run, or set a schedule with the dropdowns. A run
fetches every published page once, so it takes a while and shows progress as it goes. It covers one
language and stops at 2000 pages, so a large site needs the schedule rather than the button.

### Report

Only appears when an administrator has configured an AI provider. Everything else on this dashboard
is measured; this asks a model to read those measurements and say what they add up to.

You get a verdict, a status per area, a ranked list of what to fix first with who can fix it and how
much effort, quick wins, and a now/next/later plan. It can be exported.

**What leaves the building:** scores, the names of failing checks, page paths and titles, and
counts. Never the text of your pages, and never the key. It is written only when you ask, because
each one costs money.

## Can a crawler reach it

### Invisible content

A crawler served a login form gets a perfectly successful response and reports success. Only the
content store knows the page was never readable.

You get *N of M published pages can be read by a visitor with no account*, and the rest split into
**probably not intended** and **closed on purpose**. A members' area is not a defect; a press
release nobody can open is.

Start here when the site score looks bad for no obvious reason. A page in this list fails
everything downstream.

### Internal links

A crawler can report what it found. It cannot report what it missed, because it never knew the page
existed. This compares everything published against everything actually linked.

Two findings. **Unreachable**: nothing links to it and the sitemap does not list it, so nothing
will ever find it. **Weak**: reachable only through a menu that lists everything, or only through
the sitemap.

Navigation links and body-content links are counted separately on purpose. Being in a menu that
lists every page is not the same as somebody choosing to link to you, and crawlers weigh them
differently too.

Needs a site scan, because the links are read from the rendered pages.

### Addresses

Every alternative address a page answers on, taken from Jahia's own vanity URL list rather than
guessed by crawling.

What it looks for: an alias filed under a language the site does not serve, which returns a
not-found to everybody; one page live on several addresses, which splits its reputation; and a page
with aliases but nothing saying which address is the real one.

If the site uses no vanity URLs, it says so and there is nothing to do.

## Is what arrives usable

### Structured data

Structured data tells a machine "this is an article, published then, written by them". This panel
is where a content type is mapped to its schema.org meaning, once, so every item of that type
produces it.

Nothing is guessed: a type is whatever the person who defines it says it is. Required properties
with no source in the content model are named rather than invented, and a value that would
contradict the page is reported instead of emitted.

Nothing is written into your pages. The drawer shows the finished snippet for somebody to place,
normally in a template.

### Languages

One figure for the whole site hides the market that is failing. This puts every language the site
declares next to each other.

Per language: how many items are published out of the total, the percentage covered, how many were
never translated, and the score where one exists. A language with no scan says *not measured* -
which is not a zero, and the panel is careful about the difference.

### Freshness

Dates taken from the content store, not from whatever date a page chooses to display. You pick the
threshold: flag a group when nothing in it has changed in six months, a year, whatever suits.

It groups rather than ranks, which matters: a legal notice that has not moved in two years is fine,
and a news section in the same state is not. A group is flagged only when its *newest* item is past
your threshold.

Then every published page, oldest first, with its type, its section and when it last changed. Each
row opens in jContent.

## Site-level files

### Sitemap vs reality

Every entry in the sitemap is resolved back to real content rather than fetched, so checking costs
the site nothing however large it is.

It finds: published pages the sitemap never mentions, entries pointing at nothing, dates that
disagree with the content's real date, pages advertised while their own markup says to ignore them,
and pages listed at an address the site redirects away from.

### robots.txt - writes to the site

A row per crawler with an **Allow** or **Block** stance, plus what blocking it would cost you.
Roughly half of them answer people's questions, so refusing one takes you out of that assistant's
replies; the others only train models, so refusing those costs no visibility at all.

**Nothing is written until you confirm.** You get a *What would change* view showing the exact
lines added and removed, and a second click applies it. Groups you did not touch are left exactly
as they are, comments and sitemap lines included.

If the site has no robots.txt yet, applying creates one. If you lack permission, the panel says so
instead of failing silently.

### llms.txt - writes to the site

Generated from the published page tree. No model is involved and nothing external is called, so the
same site produces the same file every time.

Pages a visitor cannot read, and pages marked to be ignored, are left out automatically. The panel
tells you whether the stored file is still current or has gone stale against what the site now
contains.

Same safety as robots.txt: you see the exact difference first, and what gets written is exactly
what is on screen, so hand edits made before applying survive.

## A sensible order

1. **Invisible content first.** A page no stranger can read fails everything else, so fixing these
   moves the score most.
2. **Then the site score by section.** Find the section dragging the average down rather than
   working page by page.
3. **Check whether it is the template.** If the same check fails across a whole template, one
   change fixes hundreds of pages and none of them are yours.
4. **Then internal links and the sitemap.** Pages nothing points at are invisible however good they
   are.
5. **Freshness and languages last.** These are planning tools: they tell you where to spend next
   quarter, not what is broken today.

### Before you change robots.txt

It applies to the **whole site**, not the page you came from, and blocking a crawler that answers
people's questions removes you from that assistant's replies. The panel tells you which are which.
Worth a conversation rather than a quick toggle.

---

**Dashboard or drawer?** The drawer checks one page, live, each time you run it. The dashboard
covers the whole site from the last scan. If the two disagree, the scan is probably old - run it
again.

Some lists are capped so a very large site stays usable, and only one of them says so on screen. If
a list looks suspiciously round, it may have been cut. For the exact definition of every check and
every site-level comparison, read [`checks.md`](checks.md).
