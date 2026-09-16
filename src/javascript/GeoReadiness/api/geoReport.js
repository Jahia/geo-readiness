const ENDPOINT = '/modules/geo-readiness/report';

/**
 * The written report. The prompt, the digest and the key all live on the
 * server; this client only asks, reads back, and renders the fixed shape.
 */

/**
 * Whether a provider is configured, and which one.
 *
 * The site and language are sent although the answer is the same for every
 * site: they are what the server resolves to decide whether this caller may be
 * told. The endpoint used to answer any logged-in account (JAHIA-SEC-432), so
 * these are not optional - omitting them is a 400, and holding no `publish` on
 * the named site is a 403.
 *
 * @param {{path: string, language: string}} where the site to answer for.
 */
export async function fetchReportStatus({path, language}) {
    const query = `?path=${encodeURIComponent(path)}&language=${encodeURIComponent(language)}`;
    const res = await fetch(ENDPOINT + query, {credentials: 'same-origin'});
    if (!res.ok) {
        const err = new Error('report');
        err.code = res.status;
        throw err;
    }

    return res.json();
}

async function post(body) {
    const res = await fetch(ENDPOINT, {
        method: 'POST',
        credentials: 'same-origin',
        headers: {
            'Content-Type': 'application/json',
            'X-Requested-With': 'XMLHttpRequest'
        },
        body: JSON.stringify(body)
    });
    if (!res.ok) {
        // A refusal carries {"error": "..."}; anything else (a proxy page, an
        // empty body) leaves the status as the only thing worth reporting.
        const detail = await res.json().then(body => body.error || '', () => '');

        const err = new Error(detail || 'report');
        err.code = res.status;
        throw err;
    }

    return res.json();
}

/** The stored report for this site and language, or {report: null}. */
export function readReport({path, language}) {
    return post({action: 'read', path, language});
}

/** Asks the provider. Slow, metered, and paid for: only ever on a click. */
export function generateReport({path, language, reportLanguage}) {
    return post({action: 'generate', path, language, reportLanguage});
}
