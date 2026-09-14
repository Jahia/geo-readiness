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
        let detail = '';
        try {
            detail = (await res.json()).error || '';
        } catch (e) {
            // the body was not JSON; the status is the message
        }

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
