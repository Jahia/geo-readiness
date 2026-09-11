const ENDPOINT = '/modules/geo-readiness/site-scan';

async function call(body) {
    const res = await fetch(ENDPOINT, {
        method: 'POST',
        credentials: 'same-origin',
        headers: {'Content-Type': 'application/json', 'X-Requested-With': 'XMLHttpRequest'},
        body: JSON.stringify(body)
    });
    if (!res.ok) {
        const err = new Error('siteScore');
        err.code = res.status;
        throw err;
    }

    return res.json();
}

/** Cheap and not rate limited: this is what the dashboard polls while a scan runs. */
export const scanStatus = ({path, language}) => call({action: 'scanStatus', path, language});

/** One fetch per published page. Long by nature on a large site. */
export const runScan = ({path, language, scope}) => call({action: 'runScan', path, language, scope});

/**
 * Resolves sitemap entries against the repository, so it costs no outbound
 * requests and does not need a site scan. Stores what it finds, which is what
 * the page drawer then reports.
 */
export const checkSitemap = ({path, language}) => call({action: 'sitemap', path, language});

export const saveSchedule = ({path, language, cron, enabled, scope}) =>
    call({action: 'saveSchedule', path, language, cron, enabled, scope});
