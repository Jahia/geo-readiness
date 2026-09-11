# Documentation index

Five files. Read them in this order depending on why you are here.

## If you are an agent about to change this code

1. **[`../.agents/README.md`](../.agents/README.md)** first, always. Invariants, traps, the
   robots.txt parsing rules, and the report-versus-write line. Several of the traps in it are
   bugs that were already made once. Skipping this file means making them again.
2. **[`../README.md`](../README.md)** for what the module does, its two entry points, the
   configuration keys, and the known gaps. The gaps section is not a to-do list. Two of the four
   entries are constraints of the platform, not defects.
3. **[`../test-fixtures/README.md`](../test-fixtures/README.md)** before claiming anything works.
   A zero-dependency simulator with flags for every failure mode, and a table of expected results
   per flag combination.

## If you are deciding what to build next

- **[`geo-backlog.md`](geo-backlog.md)** is GEO-17 to GEO-25: the Jahia-native stories. They need
  nothing from any vendor and they exist because Jahia knows things a crawler cannot know, at a
  scale a metered tool cannot afford. Start here. The suggested order is at the bottom of the
  file.
- **[`botrank-backlog.md`](botrank-backlog.md)** is GEO-1 to GEO-16: the BotRank integration.
  Wave 1 of it is what this module is. Wave 2 onward is gated on an API contract that does not
  exist yet, so treat it as a plan for a conversation rather than a plan for a sprint.

The placement rule that governs both files is stated once, at the top of `geo-backlog.md`: the
dashboard owns the list and the ranking, the drawer owns the one-line verdict about this page.

## If you want the history

**[`../CHANGELOG.md`](../CHANGELOG.md)**, which also carries a "notes on behaviour worth knowing"
section that explains choices the code cannot explain by itself.

## What is not in this repo

The narrative version of the BotRank analysis, with the screen-by-screen walk through their
product and the reasoning behind the criteria comparison, is a published page:
https://claude.ai/code/artifact/6e3ee49c-8b20-4fde-9a3e-e8ba9cc6afd8

Two decks exist outside the repo as well: an internal review deck, and a deck written for BotRank
setting out what we want to build and which API endpoints it needs. `botrank-backlog.md` carries
the substance of both, so the decks matter only if you need the slides themselves.

## Numbering

One sequence across both backlog files. GEO-1 to GEO-16 are BotRank-related, GEO-17 to GEO-25 are
Jahia-native. Do not reuse a number. If a story is dropped, leave the gap.
