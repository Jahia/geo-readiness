/**
 * The report as a file somebody can hand to somebody else.
 *
 * Built from the parsed shape, never from HTML, so what is exported is exactly
 * what was rendered. Headings come from the UI language through `t`, and the
 * prose is the model's, in the report language it was asked for.
 */

const AREAS = ['reachability', 'content', 'structured_data', 'freshness', 'languages', 'site_files', 'links', 'addresses'];
const PHASES = ['now', 'next', 'later'];

const joinDot = parts => parts.filter(Boolean).join(' · ');

/** Markdown for the whole report. `site` is the display name, `t` the translator. */
export function reportToMarkdown(report, {site, t}) {
    return [
        ...heading(report, site, t),
        ...summary(report, t),
        ...complianceTable(report, t),
        ...priorities(report, t),
        ...quickWins(report, t),
        ...roadmap(report, t),
        '---',
        '',
        `_${t('report.disclaimer')}_`,
        ''
    ].join('\n');
}

function heading(report, site, t) {
    const when = String(report.generatedAt || '').replace('T', ' ').replace(/\.\d+Z$/, ' UTC');
    const subtitle = joinDot([
        t('report.export.generated', {date: when}),
        report.provider && report.model ? `${report.provider} · ${report.model}` : '',
        t(`report.verdict.${report.verdict || 'partial'}`)
    ]);
    return [`# ${t('report.export.title', {site})}`, '', subtitle, ''];
}

function summary(report, t) {
    if (!report.summary) {
        return [];
    }

    return [`## ${t('report.summary')}`, '', report.summary, ''];
}

function complianceTable(report, t) {
    const rows = report.compliance || [];
    if (rows.length === 0) {
        return [];
    }

    const header = [t('report.export.area'), t('report.export.status'), t('report.export.note')];
    const body = AREAS
        .map(area => ({area, row: rows.find(c => c.area === area)}))
        .filter(entry => entry.row)
        .map(({area, row}) => tableRow([
            t(`report.area.${area}`),
            t(`report.status.${row.status}`),
            cell(row.note)
        ]));

    return [
        `## ${t('report.compliance')}`,
        '',
        tableRow(header),
        '|---|---|---|',
        ...body,
        ''
    ];
}

function priorities(report, t) {
    const rows = report.priorities || [];
    if (rows.length === 0) {
        return [];
    }

    const blocks = rows.flatMap((p, i) => {
        const severity = t(`score.severity.${p.severity}`);
        const facts = joinDot([
            `**${severity}**`,
            t(`report.area.${p.area}`),
            t('report.export.owner', {owner: t(`report.owner.${p.owner}`)}),
            t('report.export.effort', {effort: t(`report.effort.${p.effort}`)})
        ]);
        return [
            `### ${i + 1}. ${p.title}`,
            '',
            facts,
            '',
            ...prose(t('report.why'), p.why),
            ...prose(t('report.how'), p.how),
            ...pageList(p.pages, t)
        ];
    });

    return [`## ${t('report.priorities')}`, '', ...blocks];
}

function prose(label, text) {
    if (!text) {
        return [];
    }

    return [`**${label}** ${text}`, ''];
}

function pageList(pages, t) {
    if (!pages || pages.length === 0) {
        return [];
    }

    return [`**${t('report.pages')}**`, ...pages.map(path => `- \`${path}\``), ''];
}

function quickWins(report, t) {
    const wins = report.quickWins || [];
    if (wins.length === 0) {
        return [];
    }

    return [`## ${t('report.quickWins')}`, '', ...wins.map(bullet), ''];
}

function roadmap(report, t) {
    const phases = report.roadmap || {};
    const filled = PHASES.filter(phase => (phases[phase] || []).length > 0);
    if (filled.length === 0) {
        return [];
    }

    const blocks = filled.flatMap(phase => {
        const heading = t(`report.phase.${phase}`);
        return [`### ${heading}`, '', ...phases[phase].map(bullet), ''];
    });
    return [`## ${t('report.roadmap')}`, '', ...blocks];
}

const bullet = text => `- ${text}`;

const tableRow = values => `| ${values.join(' | ')} |`;

/**
 * A table cell. A pipe would end the column and a newline would end the row, so
 * both are neutralised: the pipe escaped, the line breaks folded into spaces.
 * Folded by splitting rather than by a regex, because a pattern of optional
 * whitespace either side of a newline backtracks badly on a long note.
 */
function cell(text) {
    return String(text || '')
        .replaceAll('|', String.raw`\|`)
        .split(/\r?\n/)
        .map(part => part.trim())
        .filter(Boolean)
        .join(' ');
}

/** Hands the browser a file. Object URLs are revoked once the click has fired. */
export function downloadText(filename, content, mime) {
    const blob = new Blob([content], {type: `${mime};charset=utf-8`});
    const url = URL.createObjectURL(blob);
    const a = document.createElement('a');
    a.href = url;
    a.download = filename;
    a.rel = 'noopener';
    document.body.appendChild(a);
    a.click();
    a.remove();
    setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/** geo-report-luxe-en-2026-09-14.md, so a folder of them sorts by site then by date. */
export function reportFilename(siteKey, language, report, ext) {
    const day = (report.generatedAt || new Date().toISOString()).slice(0, 10);
    return `geo-report-${siteKey}-${language}-${day}.${ext}`;
}
