const ENDPOINT = '/modules/geo-readiness/site-files';

async function call(body) {
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
        const err = new Error('siteFiles');
        err.code = res.status;
        throw err;
    }
    return res.json();
}

/** Generates the file and returns it next to what is stored. Writes nothing. */
export const previewLlms = ({path, language}) => call({action: 'previewLlms', path, language});

/** Writes exactly `content`. Only ever called after the editor has seen it. */
export const applyLlms = ({path, language, content}) => call({action: 'applyLlms', path, language, content});

/** Merges the decisions server-side and returns current + proposed. Writes nothing. */
export const previewRobots = ({path, language, decisions}) =>
    call({action: 'previewRobots', path, language, decisions});

/** Writes exactly `content`. Only ever called after the editor has seen the diff. */
export const applyRobots = ({path, language, content}) =>
    call({action: 'applyRobots', path, language, content});
