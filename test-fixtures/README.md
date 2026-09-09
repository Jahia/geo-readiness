# Test fixtures

A local Jahia has no firewall and no bot protection, so every crawler returns 200 and the
crawler check looks like it always passes. That proves nothing. These fixtures let you produce
the cases that actually matter.

## Run it

```bash
node test-fixtures/serve.js --block GPTBot,CCBot
```

Then point the module at it. In
`digital-factory-data/karaf/etc/org.jahia.se.modules.georeadiness.cfg`:

```properties
PUBLIC_BASE_URL=http://localhost:9090
```

That file reloads live, so no redeploy. Open any published page in jContent and run the check.
Remember to blank `PUBLIC_BASE_URL` again when you are done, or every check will keep pointing
at the fixture.

## Flags

| Flag | What it simulates |
|---|---|
| `--block GPTBot,CCBot` | Bot protection returning 403 to those agents. robots.txt stays reachable, as a real WAF behaves. |
| `--js-only` | A page whose content only appears after JavaScript. Perfect in a browser, empty to a crawler. |
| `--no-llms` | No llms.txt. |
| `--llms-html` | `/llms.txt` returns the HTML page with a 200. The common false positive. |
| `--llms-full` | Also serve `/llms-full.txt`. |
| `--no-robots` | No robots.txt at all, so everything is allowed by default. |
| `--port 9090` | Change the port. |

## Expected results

Verified by replaying the parser against the fixture. If the module disagrees with this table,
the module is wrong.

Started with `--block GPTBot,CCBot`, page `/en/home.html`:

| Agent | HTTP | robots.txt | Named | Rule | Mismatch |
|---|---|---|---|---|---|
| Browser (control) | 200 | n/a | n/a | | |
| GPTBot | 403 | allowed | by name | `Allow: /` | **blockedButAllowed** |
| OAI-SearchBot | 200 | allowed | by name | `Allow: /` | |
| ChatGPT-User | 200 | allowed | wildcard | none | |
| ClaudeBot | 200 | allowed | wildcard | none | |
| PerplexityBot | 200 | allowed | by name | none | |
| Google-Extended | 200 | allowed | wildcard | none | |
| Bingbot | 200 | allowed | wildcard | none | |
| CCBot | 403 | **disallowed** | by name | `Disallow: /` | none, and that is right |

CCBot is the control for the mismatch logic. It is blocked **and** disallowed, so the two agree
and the module must stay quiet. A tool that shouts about every red cell is a tool people learn
to ignore.

Other paths worth testing, same server:

| Path | What it proves |
|---|---|
| `/docs/internal.html` | PerplexityBot is disallowed by `Disallow: /docs/` yet gets 200 → **reachableButDisallowed** |
| `/docs/public/api.html` | `Allow: /docs/public/` beats the shorter `Disallow: /docs/`. Longest match wins. |
| `/en/login` | The wildcard rule `Disallow: /*/login` matches, so every unnamed agent is disallowed. |
| `/private` | The `$` anchor matches exactly. `/private/sub` must NOT match. |

## The three runs worth doing

1. **Default.** Everything green. This is the control. If it is not green, the bug is ours.
2. **`--block GPTBot,CCBot`.** The table above. This is the case a local Jahia can never produce
   and the reason this server exists.
3. **`--js-only`.** The browser sees roughly 130 words, the crawlers see almost none. The verdict
   should switch to the thin-content warning rather than reporting success.

Then `--llms-html` on its own, to confirm a 200 HTML response for `/llms.txt` is reported as
**not having one** rather than as a pass.
