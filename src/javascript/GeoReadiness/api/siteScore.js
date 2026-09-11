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

/**
 * Ages published content against a threshold. A repository query, so it answers
 * without a scan; passing staleDays both recomputes and remembers the choice.
 */
export const checkFreshness = ({path, language, staleDays}) =>
    call({action: 'freshness', path, language, staleDays});

/** Coverage and score per language. Needs no scan: coverage is a repository read. */
export const checkLanguages = ({path, language}) => call({action: 'languages', path, language});

export const saveSchedule = ({path, language, cron, enabled, scope}) =>
    call({action: 'saveSchedule', path, language, cron, enabled, scope});
