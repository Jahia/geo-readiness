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


## Where each one is computed

Everything in **Is what arrives usable** is read from the HTML of a single fetch, so it costs
nothing extra once the page has been fetched. `guestReadable` is a repository question and needs no
fetch at all. The two site files are fetched once per site, not once per page.

That is why a site-wide scan is one request per page: the expensive part is the request, and
almost every check rides along on it.
