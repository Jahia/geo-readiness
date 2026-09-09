#!/usr/bin/env node
/*
 * Fixture server for the geo-readiness module. No dependencies, just node.
 *
 * A local Jahia has no firewall and no bot protection, so every crawler comes
 * back 200 and the check looks like it always passes. That proves nothing.
 * This server can simulate the cases that actually matter.
 *
 *   node serve.js                              everything allowed, healthy page
 *   node serve.js --block GPTBot,CCBot         those agents get 403, like a WAF
 *   node serve.js --js-only                    page content only appears via JS
 *   node serve.js --no-llms                    /llms.txt returns 404
 *   node serve.js --llms-html                  /llms.txt returns the HTML page (the false positive)
 *   node serve.js --llms-full                  also serve /llms-full.txt
 *   node serve.js --no-robots                  no robots.txt at all
 *   node serve.js --port 9090
 *
 * Then point the module at it, in
 * digital-factory-data/karaf/etc/org.jahia.se.modules.georeadiness.cfg:
 *
 *   PUBLIC_BASE_URL=http://localhost:9090
 *
 * That file reloads live, so no redeploy is needed.
 */
const http = require('http');
const fs = require('fs');
const path = require('path');

const argv = process.argv.slice(2);
const flag = n => argv.includes('--' + n);
const val = (n, d) => {
    const i = argv.indexOf('--' + n);
    return i >= 0 && argv[i + 1] ? argv[i + 1] : d;
};

const PORT = parseInt(val('port', '9090'), 10);
const BLOCK = (val('block', '') || '').split(',').map(s => s.trim().toLowerCase()).filter(Boolean);
const JS_ONLY = flag('js-only');
const NO_LLMS = flag('no-llms');
const LLMS_HTML = flag('llms-html');
const LLMS_FULL = flag('llms-full');
const NO_ROBOTS = flag('no-robots');

const read = f => fs.readFileSync(path.join(__dirname, f), 'utf8');

const richPage = url => `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>GEO Readiness fixture — ${url}</title>
<meta name="description" content="A fixture page with a real description, so the initial-HTML check has something to find.">
<link rel="canonical" href="http://localhost:${PORT}${url}">
<script type="application/ld+json">{"@context":"https://schema.org","@type":"WebPage","name":"Fixture"}</script>
</head>
<body>
<nav><a href="/en/home.html">Home</a> <a href="/en/products.html">Products</a> <a href="/docs/public/start.html">Docs</a></nav>
<h1>This page is readable by a crawler</h1>
<p>Everything a crawler needs is present in the initial HTML. There is a single H1, a meta
description, a canonical link, structured data, real navigation links, and enough body text
that the word count is clearly above zero.</p>
<p>The point of this fixture is that the module should report it as healthy. If it does not,
the bug is in the module and not in the page. That makes it a useful control.</p>
<p>Paragraph three exists purely to push the word count somewhere comfortably above the
threshold used to detect thin, JavaScript-only pages. Real sites have far more text than this,
but a few hundred words is enough to tell the two cases apart.</p>
<footer><a href="/en/contact.html">Contact</a></footer>
</body></html>`;

// The nasty case: looks perfect in a browser, empty to a crawler.
const jsOnlyPage = url => `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<title>GEO Readiness fixture — ${url}</title>
</head>
<body>
<div id="root"></div>
<script>
  // A crawler never runs this, so it sees an empty page.
  document.getElementById('root').innerHTML =
    '<h1>Rendered by JavaScript</h1><p>' + 'Only a browser can see this text. '.repeat(60) + '</p>';
</script>
</body></html>`;

const send = (res, code, type, body) => {
    res.writeHead(code, {'Content-Type': type, 'Cache-Control': 'no-store'});
    res.end(body);
};

const server = http.createServer((req, res) => {
    const ua = (req.headers['user-agent'] || '').toLowerCase();
    const url = req.url.split('#')[0];
    const bare = url.split('?')[0];
    const blocked = BLOCK.some(b => ua.includes(b));

    console.log(`${blocked ? '403' : ' → '}  ${bare.padEnd(34)} ${(req.headers['user-agent'] || '(none)').slice(0, 70)}`);

    // Simulate bot protection. Note robots.txt itself stays reachable, which is
    // exactly how a real WAF behaves and is what makes the mismatch detectable.
    if (blocked && bare !== '/robots.txt') {
        return send(res, 403, 'text/html; charset=utf-8',
            '<!doctype html><html><head><title>403</title></head><body><h1>Access denied</h1></body></html>');
    }

    if (bare === '/robots.txt') {
        return NO_ROBOTS
            ? send(res, 404, 'text/plain; charset=utf-8', 'Not found')
            : send(res, 200, 'text/plain; charset=utf-8', read('robots.txt'));
    }

    if (bare === '/llms.txt') {
        if (NO_LLMS) {
            return send(res, 404, 'text/plain; charset=utf-8', 'Not found');
        }
        if (LLMS_HTML) {
            // The common false positive: catch-all routing returns 200 HTML.
            return send(res, 200, 'text/html; charset=utf-8', richPage('/llms.txt'));
        }
        return send(res, 200, 'text/plain; charset=utf-8', read('llms.txt'));
    }

    if (bare === '/llms-full.txt') {
        return LLMS_FULL
            ? send(res, 200, 'text/plain; charset=utf-8', read('llms-full.txt'))
            : send(res, 404, 'text/plain; charset=utf-8', 'Not found');
    }

    if (bare === '/sitemap.xml') {
        return send(res, 200, 'application/xml',
            '<?xml version="1.0" encoding="UTF-8"?><urlset xmlns="http://www.sitemaps.org/schemas/sitemap/0.9">' +
            `<url><loc>http://localhost:${PORT}/en/home.html</loc></url></urlset>`);
    }

    return send(res, 200, 'text/html; charset=utf-8', (JS_ONLY ? jsOnlyPage : richPage)(bare));
});

server.listen(PORT, () => {
    console.log(`\ngeo-readiness fixture server on http://localhost:${PORT}`);
    console.log(`  robots.txt   ${NO_ROBOTS ? 'ABSENT (404)' : 'served'}`);
    console.log(`  llms.txt     ${NO_LLMS ? 'ABSENT (404)' : LLMS_HTML ? 'served as HTML (false positive)' : 'served'}`);
    console.log(`  llms-full    ${LLMS_FULL ? 'served' : 'absent (404)'}`);
    console.log(`  page         ${JS_ONLY ? 'JS-ONLY (thin initial HTML)' : 'rich initial HTML'}`);
    console.log(`  blocking     ${BLOCK.length ? BLOCK.join(', ') : 'nothing'}`);
    console.log(`\nSet PUBLIC_BASE_URL=http://localhost:${PORT} in the module cfg, then run the check.\n`);
});
