const ENDPOINT = '/modules/geo-readiness/report';

/**
 * The written report. The prompt, the digest and the key all live on the
 * server; this client only asks, reads back, and renders the fixed shape.
 */
export async function fetchReportStatus() {
    const res = await fetch(ENDPOINT, {credentials: 'same-origin'});
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
