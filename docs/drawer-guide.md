# The page drawer, for content authors

When ChatGPT, Claude or Perplexity fetch one of your pages, what do they actually get? This panel
answers that for the page you have open, and tells you which parts are yours to fix.

Open it by selecting a page in jContent and choosing **GEO readiness**. Nothing in the panel
changes your page: it only reads and reports.

A companion guide covers the site-wide screen: [`dashboard-guide.md`](dashboard-guide.md).

## What happens when you click Run check

The server asks for your published page **sixteen times**: once pretending to be an ordinary web
browser, then once as each of the fifteen AI crawlers it knows about. It compares what they each
received.

Two things make this different from looking at your page in a browser.

- **It asks as a stranger.** No login, no cookies. If a visitor with no account cannot read the
  page, no crawler ever will.
- **It reads the page before any JavaScript runs.** That is all most AI crawlers ever see. A page
  that fills itself in after loading looks perfect to you and empty to them.

If the page has never been published you get a single message and no tabs, because there is no
public address to test yet. Publish it, then run the check.

## Coloured banners come first

Some things are facts about the page rather than findings, so they sit above the tabs. You may see
none of these, or several.

| Banner | What it means |
|---|---|
| No visitor can read this page | The page is closed to anonymous visitors. Everything below is moot: no crawler will ever get in. If that is deliberate, fine. If not, fix this first. |
| This page is set to noindex | The page itself asks search engines and crawlers to ignore it. |
| This page is not in the sitemap | Nothing tells crawlers the page exists. They can only find it by following a link. |
| In the sitemap **and** marked noindex | Your own two files contradict each other: one advertises the page, the other says to ignore it. Pick one. |
| Does not exist in every language | The page is missing in one or more of the site's languages. |
| A translation is not published | The translation was written but never published, so nobody can read it. |
| Address or sitemap date warnings | The page answers on several addresses, or the sitemap's "last changed" date disagrees with the real one. |

A tab showing an amber number has that many problems inside it. A tab with no number has nothing
that needs you.

## Tab 1 - Score

**What it answers.** "Overall, is this page in good shape, and what exactly is wrong with it?" It
is a count of checks that passed, not a mark out of a hundred, so you can disagree with one line
rather than with a number you cannot see inside.

**What you see.** A headline such as *16 of 18 checks passed*, red if anything critical failed,
amber if something important did, green if all is well. Then context, then the checks in three
groups.

| Group | Asks |
|---|---|
| Can a crawler reach it | Can they get the page at all, does robots.txt agree with what the server does, can a visitor with no account read it |
| Is what arrives usable | Is there real text before JavaScript, one clear heading, a title of sensible length, a description, a language, a date, alt text on images |
| Site-level files | Are `robots.txt` and `llms.txt` being served at all |

**Reading a failed check.** Each failed line tells you what to do about it and carries a tag.
*critical* means an AI crawler cannot read the page properly; *important* means it can, but less
well than it should. On the right you often see the actual value that was read, such as the length
of your title.

**The context lines.** Above the checks: how this page compares with the site average and with its
own section, how many pages link to it from navigation and from body content, and whether it
appears in `llms.txt`. A score means nothing on its own; these say whether the page is the problem
or the site is.

### Not yours to fix

Some failed checks carry a line like *"Comes from the template X, which renders 240 pages."* The
same thing fails on nearly every page using that template, so it is not something you did and not
something you can fix in your page. Send it to whoever owns the template, where one change fixes
all of them.

### One deliberate omission

Being disallowed in `robots.txt` is **not** counted as a failure. Telling a crawler to stay away is
a legitimate decision. What *is* counted is your file and your server disagreeing, because then one
of the two is wrong.

## Tab 2 - Crawler access

**What it answers.** "Which crawlers got my page, and did they get the same thing a browser got?"

**The verdict at the top.**

| You see | It means |
|---|---|
| N crawlers are blocked | Someone did not get a normal answer. Not always a refusal: a missing page or a timeout counts too. |
| Even a browser receives almost no text | The page is an empty shell until JavaScript fills it in. Every crawler sees nothing. The most serious result on the tab. |
| Some crawlers receive far less text | Crawlers got under half the words a browser got. Part of your content needs JavaScript. |
| Every crawler can read this page | Nothing to do. |

**The list of crawlers.** One row each, with a green or red number (200 is the healthy one), how
long it took, and how many words arrived. Underneath, five marks that are green when present and
grey when not: H1 heading, meta description, canonical URL, the number of links, and structured
data. A row may also be flagged *thin*, meaning that crawler got far less than the browser did.

**The two sentences that matter most.** At the bottom you may get one of these, and they decide
*who* fixes the problem.

- *"Your robots.txt allows these crawlers, but the server refused them anyway."* Something between
  the crawler and your site is blocking them, usually a firewall or bot protection. Nothing you
  write in a page or in robots.txt will help. This goes to whoever runs the infrastructure.
- *"Your robots.txt tells these crawlers not to fetch this page."* The opposite: they could read
  it, but your own file asks them not to. If you want them in, change the file.

## Tab 3 - Site files

**What it answers.** "What do the two files that speak for the whole site currently say?" These
belong to the site, not to your page, so this tab is the same whichever page you opened it from.

**robots.txt.** The file that tells crawlers where they may go. A headline says how many AI
crawlers it *names* and how many it turns away, then a table with one row per crawler.

| Column | Means |
|---|---|
| Declared | *by name* - the file mentions this crawler specifically. *by wildcard* - it is only covered by the catch-all rule. |
| Effective | *allowed* or *disallowed*: what the file actually permits for this crawler. |
| Rule | The exact line that decided it, so you can see why. |

**llms.txt.** A newer, simpler file: a plain summary of the site for AI tools. If it exists you see
its heading, whether it has a summary, and how many sections and links it contains. If the server
returns a web page instead of the file, that is called out in red, because a crawler asking for the
file gets something it cannot use.

**What to do.** Nothing here is edited here. Both files are managed on the site dashboard under
**Additional > SEO > GEO readiness**, and changing them affects every page. If a crawler you want
is turned away, that is a conversation to have, not a change to your page.

## Tab 4 - Structured data

**What it answers.** "Can a machine tell what kind of thing this page is?" Structured data is a
small block of hidden text that says, in a standard vocabulary, "this is an article, published on
this date, written by this person". Crawlers rely on it heavily.

**What you see.** One of three things.

- *"This content type is not mapped yet."* Nobody has said what kind of thing this type of content
  represents. That is a one-off setup job, not a per-page one.
- A type and a verdict, such as *schema.org Article* with either "complete" or "N properties
  missing". Missing ones are listed, with where each would come from.
- A conflict warning. The strongest signal on the tab: the data would say something the page
  contradicts. Wrong structured data is worse than none, so this is said loudly.

**What to do.** The tab shows the finished snippet with a Copy button. Nothing is ever written into
your page automatically: somebody places it, usually once in a template rather than page by page.
If properties are missing, the usual fix is to fill in the matching fields on the content itself.

## Which problems are actually yours

| If you see | Who fixes it |
|---|---|
| Missing description, thin text, no heading, no alt text, missing translation | **You.** These are content fields on the page. |
| "Comes from the template X" | **Whoever owns the template.** Fixing it once fixes every page using it. |
| "The server refused them anyway", a 403, crawlers blocked | **Infrastructure.** A firewall or bot-protection rule. No content change will help. |
| "Even a browser receives almost no text" | **Developers.** The page needs to be built on the server rather than in the browser. |
| robots.txt or llms.txt wording | **Whoever owns the site settings.** It affects every page, so it is not a per-page decision. |

## Terms you will meet

| Term | Means |
|---|---|
| AI crawler | A program that fetches your pages so an AI assistant can read or cite them. GPTBot, ClaudeBot and PerplexityBot are three of the fifteen checked here. |
| Initial HTML | The page exactly as the server sends it, before any JavaScript runs. Most crawlers never see more than this. |
| robots.txt | A file at the root of the site telling crawlers where they may and may not go. Polite crawlers obey it; it is not a lock. |
| llms.txt | A newer file offering AI tools a plain-language summary of the site and its key pages. |
| Canonical URL | A line in the page saying "this is my real address", so the same content on several addresses is not counted several times. |
| noindex | An instruction in the page asking search engines and crawlers to leave it out of their results. |
| Structured data | Hidden, standardised facts about the page ("article", "published on", "written by") that machines read directly. |
| Sitemap | A list of every page on the site, published for crawlers so they do not have to discover pages by following links. |

---

The drawer checks one page, live, each time you run it. The site dashboard covers the whole site
and is based on the last full scan, so the two can disagree if that scan is old. For the exact
definition of every check, read [`checks.md`](checks.md).
