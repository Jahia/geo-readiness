#!/usr/bin/env node
/*
 * A stand-in for BotRank's public API v1, for the e2e suite and local runs. Zero dependencies.
 *
 *   node test-fixtures/botrank-stub.js [--port 9091]
 *
 * Serves the eight GET routes under /public/v1 with fixed data shaped like the real answers
 * (reference read on 2026-10-15). It checks what the real API checks: the bearer key (brk_test),
 * then the Project-Id header (p1), and answers refusals as RFC 9457 problem documents.
 *
 * Data: one project; the site's host is stub-site.example. Two cited URLs:
 *   /legacy/offer     cited 12 times over 30 days, variation +3, with a run day in the previous window
 *   /legacy/old-guide cited only before the last 30 days (all-time list only)
 * Any other URL has never been cited.
 */
'use strict';

const http = require('http');

const KEY = 'brk_test';
const PROJECT = 'p1';
const HOST = 'stub-site.example';
const OFFER = `https://${HOST}/legacy/offer`;
const OLD = `https://www.${HOST}/legacy/old-guide/`;

const port = (() => {
    const i = process.argv.indexOf('--port');
    return i > 0 ? Number(process.argv[i + 1]) : 9091;
})();

const day = offset => {
    const d = new Date();
    d.setUTCDate(d.getUTCDate() - offset);
    return d.toISOString().slice(0, 10);
};

const problem = (res, status, type, title) => {
    res.writeHead(status, {'Content-Type': 'application/problem+json'});
    res.end(JSON.stringify({type: `https://api.botrank.ai/public/v1/problems/${type}`, title, status, detail: title}));
};

const ok = (res, body) => {
    res.writeHead(200, {'Content-Type': 'application/json; charset=utf-8'});
    res.end(JSON.stringify(body));
};

const isOffer = url => typeof url === 'string' && url.replace(/^https?:\/\/(www\.)?/, '').replace(/\/$/, '') === `${HOST}/legacy/offer`;

const routes = {
    '/public/v1/projects': () => ({projects: [{id: PROJECT, name: 'Stub project'}]}),
    '/public/v1/ai-visibility/data-freshness': () => ({latestCountedAnswerAt: `${day(0)}T04:00:00.000Z`, refreshing: false}),
    '/public/v1/ai-visibility/pages': q => {
        const pages = [{url: OFFER, rank: 1, citationCount: 12, citationCountVariation: 3}];
        if (q.get('period') === 'all_time') {
            pages.push({url: OLD, rank: 2, citationCount: 4, citationCountVariation: null});
        }
        return {domain: HOST, pages, nextCursor: null};
    },
    '/public/v1/ai-visibility/pages/citation-total': q => (isOffer(q.get('url'))
        ? {citationCount: 12, citationCountVariation: 3, aiSurfaces: [{aiSurface: 'chatgpt', citationCount: 9}, {aiSurface: 'perplexity', citationCount: 3}]}
        : {citationCount: 0, citationCountVariation: null, aiSurfaces: []}),
    '/public/v1/ai-visibility/pages/citation-history': q => ({
        days: isOffer(q.get('url'))
            ? [{date: day(40), citationCount: 2}, {date: day(5), citationCount: 7}, {date: day(1), citationCount: 5}]
            : [{date: day(40), citationCount: 0}, {date: day(5), citationCount: 0}, {date: day(1), citationCount: 0}]
    }),
    '/public/v1/ai-visibility/pages/prompts': q => ({
        prompts: isOffer(q.get('url')) ? [{
            id: '00000000-0000-4000-8000-000000000001',
            text: 'Which <b>offer</b> fits a first home?',
            market: 'fr',
            citationCount: 12,
            citationCountVariation: 3,
            aiSurfaces: [{aiSurface: 'chatgpt', citationCount: 9}],
            lastCitationDay: day(1)
        }] : []
    }),
    '/public/v1/ai-visibility/pages/tags': q => ({
        tags: isOffer(q.get('url')) ? [{id: '00000000-0000-4000-8000-000000000002', name: 'Home insurance', promptCount: 1, citationCount: 12, citationCountVariation: 3}] : []
    }),
    '/public/v1/ai-visibility/pages/co-cited': q => ({
        coCitedPages: isOffer(q.get('url')) ? [{
            url: 'https://competitor.example/best-offer',
            sharedAnswerCount: 8,
            citationCount: 20,
            citationCountVariation: 1,
            averageRank: 2.5,
            requestedPageAverageRank: 4,
            outrankingAnswerCount: 6
        }] : []
    })
};

http.createServer((req, res) => {
    const url = new URL(req.url, `http://localhost:${port}`);
    if (req.method !== 'GET') {
        return problem(res, 404, 'not-found', 'Not found');
    }
    if (req.headers.authorization !== `Bearer ${KEY}`) {
        return problem(res, 401, 'invalid-api-key', 'Invalid API key');
    }
    const route = routes[url.pathname];
    if (!route) {
        return problem(res, 404, 'not-found', 'Not found');
    }
    if (url.pathname !== '/public/v1/projects' && req.headers['project-id'] !== PROJECT) {
        return req.headers['project-id']
            ? problem(res, 404, 'unknown-project', 'Unknown project')
            : problem(res, 400, 'project-required', 'Project required');
    }
    return ok(res, route(url.searchParams));
}).listen(port, () => {
    console.log(`BotRank stub on http://localhost:${port}/public/v1 (key ${KEY}, project ${PROJECT}, host ${HOST})`);
});
