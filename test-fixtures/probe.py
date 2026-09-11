#!/usr/bin/env python3
"""
Reference implementation of the geo-readiness check. Run it from a machine that
can actually reach the site, which usually means your own terminal and not a
container.

    python3 test-fixtures/probe.py http://luxe.local.com:8080/en/home.html

It does exactly what CrawlerCheckServlet does: one fetch per bot user agent, no
cookies, no redirect following, and it reads the initial HTML. Then it fetches
robots.txt and llms.txt from the site root and cross-checks policy against
reality.

Use it to answer "is the module wrong, or is the site really like that?".
"""
import re, sys, time, urllib.request, urllib.error
from urllib.parse import urlparse

AGENTS = {
    "Browser (control)": "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36",
    "GPTBot": "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko); compatible; GPTBot/1.2; +https://openai.com/gptbot",
    "OAI-SearchBot": "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko); compatible; OAI-SearchBot/1.0; +https://openai.com/searchbot",
    "ChatGPT-User": "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko); compatible; ChatGPT-User/1.0; +https://openai.com/bot",
    "ClaudeBot": "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko); compatible; ClaudeBot/1.0; +claudebot@anthropic.com",
    "Claude-SearchBot": "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko); compatible; Claude-SearchBot/1.0; +claudebot@anthropic.com",
    "PerplexityBot": "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko); compatible; PerplexityBot/1.0; +https://perplexity.ai/perplexitybot",
    "Perplexity-User": "Mozilla/5.0 AppleWebKit/537.36 (KHTML, like Gecko); compatible; Perplexity-User/1.0; +https://perplexity.ai/perplexity-user",
    "Google-Extended": "Mozilla/5.0 (compatible; Google-Extended/1.0)",
    "GoogleOther": "Mozilla/5.0 (compatible; GoogleOther)",
    "Bingbot": "Mozilla/5.0 (compatible; bingbot/2.0; +http://www.bing.com/bingbot.htm)",
    "CCBot": "CCBot/2.0 (https://commoncrawl.org/faq/)",
    "Bytespider": "Mozilla/5.0 (compatible; Bytespider; spider-feedback@bytedance.com)",
    "Amazonbot": "Mozilla/5.0 (compatible; Amazonbot/0.1; +https://developer.amazon.com/support/amazonbot)",
    "Applebot-Extended": "Mozilla/5.0 (compatible; Applebot-Extended/0.1; +http://www.apple.com/go/applebot)",
    "meta-externalagent": "meta-externalagent/1.1 (+https://developers.facebook.com/docs/sharing/webmasters/crawler)",
}
TOKENS = {
    "GPTBot": "GPTBot",
    "OAI-SearchBot": "OAI-SearchBot",
    "ChatGPT-User": "ChatGPT-User",
    "ClaudeBot": "ClaudeBot",
    "Claude-SearchBot": "Claude-SearchBot",
    "PerplexityBot": "PerplexityBot",
    "Perplexity-User": "Perplexity-User",
    "Google-Extended": "Google-Extended",
    "GoogleOther": "GoogleOther",
    "Bingbot": "bingbot",
    "CCBot": "CCBot",
    "Bytespider": "Bytespider",
    "Amazonbot": "Amazonbot",
    "Applebot-Extended": "Applebot-Extended",
    "meta-externalagent": "meta-externalagent",
}


def fetch(url, ua, timeout=10):
    req = urllib.request.Request(url, headers={
        "User-Agent": ua,
        "Accept": "text/html,application/xhtml+xml,application/xml;q=0.9,*/*;q=0.8",
        "Accept-Language": "en"})
    op = urllib.request.build_opener(NoRedirect)
    t0 = time.time()
    try:
        with op.open(req, timeout=timeout) as r:
            body = r.read()
            return dict(status=r.status, ms=int((time.time() - t0) * 1000), body=body.decode("utf-8", "replace"),
                        ctype=r.headers.get("Content-Type", ""), loc=r.headers.get("Location"), err=None)
    except urllib.error.HTTPError as e:
        body = e.read()
        return dict(status=e.code, ms=int((time.time() - t0) * 1000), body=body.decode("utf-8", "replace"),
                    ctype=e.headers.get("Content-Type", ""), loc=e.headers.get("Location"), err=None)
    except Exception as e:
        return dict(status=None, ms=int((time.time() - t0) * 1000), body="", ctype="", loc=None,
                    err=f"{type(e).__name__}: {e}")


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *a, **k):
        return None


def text_of(h):
    h = re.sub(r"(?is)<(script|style|noscript|template)[^>]*>.*?</\1>", " ", h)
    return re.sub(r"\s+", " ", re.sub(r"(?s)<[^>]+>", " ", h)).strip()


def analyse(h):
    t = re.search(r"(?is)<title[^>]*>(.*?)</title>", h)
    h1 = re.findall(r"(?is)<h1[^>]*>(.*?)</h1>", h)
    return dict(
        title=(re.sub(r"\s+", " ", t.group(1)).strip()[:70] if t else None),
        h1=len(h1),
        desc=bool(re.search(r'(?is)<meta[^>]+name=["\']description["\']', h)),
        canon=bool(re.search(r'(?is)<link[^>]+rel=["\']canonical["\']', h)),
        links=len(re.findall(r"(?is)<a\s[^>]*href=", h)),
        ld=len(re.findall(r"(?is)application/ld\+json", h)),
        words=len(text_of(h).split()))


# --- robots.txt, same rules as RobotsRules.java -----------------------------
def parse_robots(body):
    groups, sitemaps, cur, last_agent = {}, [], [], False
    for raw in (body or "").split("\n"):
        raw = raw.split("#")[0].strip()
        if not raw or ":" not in raw:
            continue
        f, v = raw.split(":", 1)
        f, v = f.strip().lower(), v.strip()
        if f == "user-agent":
            if not last_agent:
                cur = []
            cur.append(v.lower())
            groups.setdefault(v.lower(), [])
            last_agent = True
        elif f in ("allow", "disallow"):
            for a in cur:
                groups.setdefault(a, []).append((f == "allow", v))
            last_agent = False
        elif f == "sitemap":
            sitemaps.append(v)
            last_agent = False
        else:
            last_agent = False
    return groups, sitemaps


def rule_matches(pattern, path):
    p, anchored = pattern, pattern.endswith("$")
    if anchored:
        p = p[:-1]
    parts, idx = p.split("*"), 0
    for i, part in enumerate(parts):
        if not part:
            continue
        if i == 0:
            if not path.startswith(part):
                return False
            idx = len(part)
        else:
            f = path.find(part, idx)
            if f < 0:
                return False
            idx = f + len(part)
    if anchored:
        return idx == len(path) if not parts[-1] else path.endswith(parts[-1])
    return True


def evaluate(groups, token, path):
    lower = token.lower()
    group = None
    for cand in groups:
        if cand == "*":
            continue
        if lower.startswith(cand) or cand.startswith(lower):
            if group is None or len(cand) > len(group):
                group = cand
    named = group is not None
    if group is None:
        group = "*" if "*" in groups else None
    if group is None:
        return True, None, False
    best = None
    for allow, pat in groups[group]:
        if not pat:
            continue
        if rule_matches(pat, path):
            if best is None or len(pat) > len(best[1]) or (len(pat) == len(best[1]) and allow):
                best = (allow, pat)
    if best is None:
        return True, None, named
    return best[0], ("Allow: " if best[0] else "Disallow: ") + best[1], named


def main(url):
    u = urlparse(url)
    base = f"{u.scheme}://{u.netloc}"
    path = (u.path or "/") + (("?" + u.query) if u.query else "")

    print(f"\nGEO readiness probe\n  page  {url}\n  root  {base}\n" + "=" * 92)

    robots = fetch(base + "/robots.txt", AGENTS["Browser (control)"])
    groups, sitemaps = parse_robots(robots["body"]) if robots["status"] == 200 else ({}, [])

    print(f"\n{'agent':<20}{'HTTP':>6}{'ms':>7}{'words':>8}  {'robots.txt':<12}{'named':<10}{'rule':<26}mismatch")
    print("-" * 92)
    control = None
    for name, ua in AGENTS.items():
        r = fetch(url, ua)
        a = analyse(r["body"]) if r["body"] else None
        w = a["words"] if a else 0
        if control is None and r["status"] == 200:
            control = w
        rob, named, rule, mm = "—", "—", "", ""
        tok = TOKENS.get(name)
        if tok and robots["status"] == 200:
            allowed, matched, is_named = evaluate(groups, tok, path)
            rob = "allowed" if allowed else "DISALLOWED"
            named = "by name" if is_named else "wildcard"
            rule = matched or "(none)"
            if allowed and r["status"] not in (200, None):
                mm = "blockedButAllowed"
            elif not allowed and r["status"] == 200:
                mm = "reachableButDisallowed"
        st = r["status"] if r["status"] else "ERR"
        print(f"{name:<20}{str(st):>6}{r['ms']:>7}{w:>8}  {rob:<12}{named:<10}{rule:<26}{mm}")
        if r["err"]:
            print(f"{'':<20}  {r['err']}")
        if r["loc"]:
            print(f"{'':<20}  redirect -> {r['loc']}")

    c = analyse(fetch(url, AGENTS["Browser (control)"])["body"] or "")
    print(f"\ninitial HTML (control): h1={c['h1']}  desc={c['desc']}  canonical={c['canon']}  "
          f"links={c['links']}  json-ld={c['ld']}  words={c['words']}")
    print(f"title: {c['title']}")

    print("\nsite files\n" + "-" * 92)
    print(f"robots.txt      HTTP {robots['status']}  "
          f"{'no AI crawler named' if robots['status'] == 200 and not any(t.lower() in groups for t in TOKENS.values()) else ''}")
    if sitemaps:
        print(f"                sitemaps: {', '.join(sitemaps)}")
    for f in ("/llms.txt", "/llms-full.txt"):
        r = fetch(base + f, AGENTS["Browser (control)"])
        note = ""
        if r["status"] == 200 and ("text/html" in (r["ctype"] or "") or r["body"].lstrip()[:15].lower().startswith("<!doctype")):
            note = "  <-- HTML, not a real llms.txt"
        print(f"{f:<16}HTTP {r['status']}{note}")

    thin = control and control > 0
    print("\nverdict\n" + "-" * 92)
    blocked = [n for n, ua in AGENTS.items() if fetch(url, ua)["status"] != 200]
    print("BLOCKED: " + ", ".join(blocked) if blocked else "All tested crawler user agents receive HTTP 200.")


if __name__ == "__main__":
    if len(sys.argv) < 2:
        print("usage: probe.py <published page url>")
        sys.exit(1)
    main(sys.argv[1])
