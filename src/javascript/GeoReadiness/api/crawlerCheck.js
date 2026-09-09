const ENDPOINT = '/modules/geo-readiness/crawler-check';

/**
 * Ask the server whether AI crawlers can read this page.
 *
 * The work happens server side on purpose. The browser cannot set a
 * User-Agent header, and the editor's session would make the result
 * meaningless anyway: a logged-in browser is not what a crawler is.
 */
export async function runCrawlerCheck({path, language}) {
    const res = await fetch(ENDPOINT, {
        method: 'POST',
        credentials: 'same-origin',
        headers: {
            'Content-Type': 'application/json',
            'X-Requested-With': 'XMLHttpRequest'
        },
        body: JSON.stringify({path, language})
    });

    if (res.status === 429) {
        const err = new Error('rateLimit');
        err.code = 'rateLimit';
        throw err;
    }

    if (!res.ok) {
        const err = new Error('generic');
        err.code = 'generic';
        throw err;
    }

    return res.json();
}

export async function getStatus() {
    try {
        const res = await fetch(ENDPOINT, {credentials: 'same-origin'});
        return res.ok ? res.json() : {enabled: false};
    } catch (e) {
        return {enabled: false};
    }
}
