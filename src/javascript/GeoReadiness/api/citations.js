// Prefixed once with the context path, so the module also works when Jahia is not served at the root.
const endpoint = () => `${globalThis.contextJsParameters?.contextPath || ''}/modules/geo-readiness/citations`;

function fail(res, body) {
    const err = new Error(body && body.error ? body.error : 'citations');
    err.code = res.status;
    err.type = body && body.type;
    return err;
}

/** What the last sync stored for one page. Never calls BotRank. */
export async function fetchPageCitations({path, language}) {
    const q = new URLSearchParams({path, language});
    const res = await fetch(`${endpoint()}?${q}`, {
        credentials: 'same-origin',
        headers: {'X-Requested-With': 'XMLHttpRequest'}
    });
    const body = await res.json().catch(() => null);
    if (!res.ok) {
        throw fail(res, body);
    }

    return body;
}

/** A settings action: status, projects, save, syncNow, map, unmap. Requires site-admin. */
export async function citationSettings(action, payload) {
    const res = await fetch(endpoint(), {
        method: 'POST',
        credentials: 'same-origin',
        headers: {'Content-Type': 'application/json', 'X-Requested-With': 'XMLHttpRequest'},
        body: JSON.stringify({action, ...payload})
    });
    const body = await res.json().catch(() => null);
    if (!res.ok) {
        throw fail(res, body);
    }

    return body;
}
