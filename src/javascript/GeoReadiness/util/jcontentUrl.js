/**
 * jContent's own address for a node, so a finding is one click from the thing it
 * is about. `/sites/<key>/home/buy` becomes `/jahia/jcontent/<key>/<lang>/pages/home/buy`.
 *
 * Content under `/contents` lives in a different jContent section to pages, and
 * sending a content node to the pages section produces a link that resolves to
 * nothing. On a site whose articles are `jmix:mainResource` content rather than
 * pages, that is most of what the reports link to, so the distinction is not an
 * edge case.
 */
export function jcontentUrl(path, language) {
    const m = /^\/sites\/([^/]+)(\/.*)?$/.exec(path || '');
    if (!m) {
        return null;
    }

    const relative = (m[2] || '').replace(/^\/+/, '');
    const section = relative === 'contents' || relative.startsWith('contents/') ?
        'content-folders' :
        'pages';

    return `/jahia/jcontent/${m[1]}/${language}/${section}${m[2] || ''}`;
}
