/**
 * A line diff, small enough to keep in the bundle and good enough for two files
 * that are a few dozen lines long.
 *
 * Longest common subsequence, then a walk back over the table. Falls back to a
 * plain replace-everything diff above MAX_LINES so a pathological input cannot
 * lock the editor's browser.
 */
const MAX_LINES = 800;

export function diffLines(before, after) {
    // Each row carries the line it came from - `before` for a deletion or an
    // unchanged line, `after` for an addition. That is a row's identity: it is
    // what a key needs, and a position in the array is not, because the array is
    // rebuilt whenever either side changes.
    const a = (before || '').split('\n');
    const b = (after || '').split('\n');

    if (a.length > MAX_LINES || b.length > MAX_LINES) {
        return [
            ...a.map((text, n) => ({type: 'del', text, line: n + 1})),
            ...b.map((text, n) => ({type: 'add', text, line: n + 1}))
        ];
    }

    // lcs[i][j] = length of the longest common subsequence of a[i:] and b[j:]
    const lcs = Array.from({length: a.length + 1}, () => new Uint16Array(b.length + 1));
    for (let i = a.length - 1; i >= 0; i--) {
        for (let j = b.length - 1; j >= 0; j--) {
            lcs[i][j] = a[i] === b[j] ? lcs[i + 1][j + 1] + 1 : Math.max(lcs[i + 1][j], lcs[i][j + 1]);
        }
    }

    const out = [];
    let i = 0;
    let j = 0;
    while (i < a.length && j < b.length) {
        if (a[i] === b[j]) {
            out.push({type: 'ctx', text: a[i], line: i + 1});
            i++;
            j++;
        } else if (lcs[i + 1][j] >= lcs[i][j + 1]) {
            out.push({type: 'del', text: a[i], line: i + 1});
            i++;
        } else {
            out.push({type: 'add', text: b[j], line: j + 1});
            j++;
        }
    }
    while (i < a.length) {
        out.push({type: 'del', text: a[i], line: ++i});
    }
    while (j < b.length) {
        out.push({type: 'add', text: b[j], line: ++j});
    }
    return out;
}

/**
 * Drops long runs of unchanged lines so the eye lands on what actually changes.
 * Keeps `context` unchanged lines either side of every edit.
 */
export function collapse(rows, context = 2) {
    const keep = new Array(rows.length).fill(false);
    rows.forEach((r, idx) => {
        if (r.type === 'ctx') {
            return;
        }
        for (let k = Math.max(0, idx - context); k <= Math.min(rows.length - 1, idx + context); k++) {
            keep[k] = true;
        }
    });

    const out = [];
    let skipped = 0;
    rows.forEach((r, idx) => {
        if (keep[idx]) {
            if (skipped > 0) {
                out.push({type: 'gap', count: skipped, line: r.line});
                skipped = 0;
            }
            out.push(r);
        } else {
            skipped++;
        }
    });
    if (skipped > 0) {
        out.push({type: 'gap', count: skipped, line: rows.length + 1});
    }
    return out;
}

export const hasChanges = rows => rows.some(r => r.type === 'add' || r.type === 'del');
