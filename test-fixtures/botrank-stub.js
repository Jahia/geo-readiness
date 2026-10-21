#!/usr/bin/env node
/*
 * A stand-in for BotRank's public API v1, for the e2e suite and local runs. Zero dependencies.
 *
 *   node test-fixtures/botrank-stub.js [--port 9091]
 *
 * Serves the GET routes the connector calls under /public/v1 with fixed data shaped like the real
 * answers (reference read on 2026-10-15; site routes, project and visibility score read on
 * 2026-10-20). It checks what the real API checks: the bearer key (brk_test),
 * then the Project-Id header (p1), and answers refusals as RFC 9457 problem documents.
 *
 * Data: one project; the site's host is stub-site.example. Two cited URLs:
 *   /legacy/offer     cited 12 times over 30 days, variation +3, with a run day in the previous window
 *   /legacy/old-guide cited only before the last 30 days (all-time list only)
 * Any other URL has never been cited. The site routes answer for stub-site.example (or www.), which
 * ranks second behind competitor.example; any other domain answers site-not-cited.
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

const SHARE_DAYS = [5, 1, 0].map(offset => ({date: day(offset), shareOfCitations: offset === 0 ? null : 0.04}));

const routes = {
    '/public/v1/projects': () => ({projects: [{id: PROJECT, name: 'Stub project'}]}),
    '/public/v1/projects/{projectId}': () => ({
        id: PROJECT,
        name: 'Stub project',
        aiSurfaces: [{aiSurface: 'chatgpt', refreshInterval: 'P1D'}, {aiSurface: 'perplexity', refreshInterval: 'P7D'}]
    }),
    '/public/v1/ai-visibility/sites/{domain}/citation-total': () => ({
        domain: HOST,
        citationCount: 12,
        citationCountVariation: 3,
        shareOfCitations: 0.04,
        shareOfCitationsVariation: 0.01,
        citedPageCount: 1,
        citedPageCountVariation: 0
    }),
    '/public/v1/ai-visibility/sites/{domain}/citation-share-history': () => ({domain: HOST, days: SHARE_DAYS}),
    '/public/v1/ai-visibility/sites/{domain}/ranking': () => ({
        ranking: [
            {domain: 'competitor.example', isRequestedSite: false, rank: 1, rankVariation: null, shareOfCitations: 0.09, shareOfCitationsVariation: null},
            {domain: HOST, isRequestedSite: true, rank: 2, rankVariation: 1, shareOfCitations: 0.04, shareOfCitationsVariation: 0.01}
        ]
    }),
    '/public/v1/ai-visibility/visibility-score': () => ({
        visibilityScore: 0.5,
        visibilityScoreVariation: null,
        analysedAnswerCount: 40,
        aiSurfaces: [{aiSurface: 'chatgpt', visibilityScore: 0.6, visibilityScoreVariation: null, analysedAnswerCount: 20}],
        markets: [{market: 'fr', visibilityScore: 0.5, visibilityScoreVariation: null, analysedAnswerCount: 40}]
    }),
    '/public/v1/ai-visibility/visibility-score/history': () => ({days: [{date: day(1), visibilityScore: 0.5}]}),
    '/public/v1/ai-visibility/data-freshness': () => ({latestCountedAnswerAt: `${day(0)}T04:00:00.000Z`, refreshing: false}),
    '/public/v1/ai-visibility/sites/{domain}/pages': q => {
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
    // The domain and the project id are path segments: route on the template, as the real API does.
    const site = /^\/public\/v1\/ai-visibility\/sites\/([^/]+)\/[a-z-]+$/.exec(url.pathname);
    const template = url.pathname
        .replace(/^(\/public\/v1\/ai-visibility\/sites\/)[^/]+\//, '$1{domain}/')
        .replace(/^(\/public\/v1\/projects\/)[^/]+$/, '$1{projectId}');
    const route = routes[template];
    if (!route) {
        return problem(res, 404, 'not-found', 'Not found');
    }
    if (site && decodeURIComponent(site[1]).replace(/^www\./, '') !== HOST) {
        return problem(res, 404, 'site-not-cited', 'Site not cited');
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
