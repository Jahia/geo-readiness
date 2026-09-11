const ENDPOINT = '/modules/geo-readiness/site-scan';

/**
 * GEO-19. Every published page of the site, read again as `guest`.
 * Read-only: this endpoint never writes.
 */
export async function guestVisibility({path, language}) {
    const res = await fetch(ENDPOINT, {
        method: 'POST',
        credentials: 'same-origin',
        headers: {
            'Content-Type': 'application/json',
            'X-Requested-With': 'XMLHttpRequest'
        },
        body: JSON.stringify({action: 'guestVisibility', path, language})
    });
    if (!res.ok) {
        const err = new Error('siteScan');
        err.code = res.status;
        throw err;
    }

    return res.json();
}
