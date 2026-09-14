/**
 * The report as a file somebody can hand to somebody else.
 *
 * Built from the parsed shape, never from HTML, so what is exported is exactly
 * what was rendered. Headings come from the UI language through `t`, and the
 * prose is the model's, in the report language it was asked for.
 */

const AREAS = ['reachability', 'content', 'structured_data', 'freshness', 'languages', 'site_files', 'links', 'addresses'];

const line = (parts, sep = ' ') => parts.filter(Boolean).join(sep);

/** Markdown for the whole report. `site` is the display name, `t` the translator. */
export function reportToMarkdown(report, {site, t}) {
    const out = [];
    out.push(`# ${t('report.export.title', {site})}`);
    out.push('');
    out.push(line([
        t('report.export.generated', {date: (report.generatedAt || '').replace('T', ' ').replace(/\.\d+Z$/, ' UTC')}),
        report.provider && report.model ? `${report.provider} · ${report.model}` : null,
        t(`report.verdict.${report.verdict || 'partial'}`)
    ], ' · '));
    out.push('');

    if (report.summary) {
        out.push(`## ${t('report.summary')}`);
        out.push('');
        out.push(report.summary);
        out.push('');
    }

    const compliance = report.compliance || [];
    if (compliance.length > 0) {
        out.push(`## ${t('report.compliance')}`);
        out.push('');
        out.push(`| ${t('report.export.area')} | ${t('report.export.status')} | ${t('report.export.note')} |`);
        out.push('|---|---|---|');
        AREAS.forEach(area => {
            const row = compliance.find(c => c.area === area);
            if (row) {
                out.push(`| ${t(`report.area.${area}`)} | ${t(`report.status.${row.status}`)} | ${cell(row.note)} |`);
            }
        });
        out.push('');
    }

    const priorities = report.priorities || [];
    if (priorities.length > 0) {
        out.push(`## ${t('report.priorities')}`);
        out.push('');
        priorities.forEach((p, i) => {
            out.push(`### ${i + 1}. ${p.title}`);
            out.push('');
            out.push(line([
                `**${t(`score.severity.${p.severity}`)}**`,
                t(`report.area.${p.area}`),
                t('report.export.owner', {owner: t(`report.owner.${p.owner}`)}),
                t('report.export.effort', {effort: t(`report.effort.${p.effort}`)})
            ], ' · '));
            out.push('');
            if (p.why) {
                out.push(`**${t('report.why')}** ${p.why}`);
                out.push('');
            }

            if (p.how) {
                out.push(`**${t('report.how')}** ${p.how}`);
                out.push('');
            }

            if ((p.pages || []).length > 0) {
                out.push(`**${t('report.pages')}**`);
                p.pages.forEach(path => out.push(`- \`${path}\``));
                out.push('');
            }
        });
    }

    const wins = report.quickWins || [];
    if (wins.length > 0) {
        out.push(`## ${t('report.quickWins')}`);
        out.push('');
        wins.forEach(w => out.push(`- ${w}`));
        out.push('');
    }

    const roadmap = report.roadmap || {};
    if (['now', 'next', 'later'].some(k => (roadmap[k] || []).length > 0)) {
        out.push(`## ${t('report.roadmap')}`);
        out.push('');
        ['now', 'next', 'later'].forEach(k => {
            const items = roadmap[k] || [];
            if (items.length > 0) {
                out.push(`### ${t(`report.phase.${k}`)}`);
                out.push('');
                items.forEach(x => out.push(`- ${x}`));
                out.push('');
            }
        });
    }

    out.push('---');
    out.push('');
    out.push(`_${t('report.disclaimer')}_`);
    out.push('');
    return out.join('\n');
}

/** A table cell: pipes and newlines would break the row. */
function cell(text) {
    return String(text || '').replace(/\|/g, '\\|').replace(/\s*\n\s*/g, ' ');
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
    document.body.removeChild(a);
    setTimeout(() => URL.revokeObjectURL(url), 1000);
}

/** geo-report-luxe-en-2026-09-14.md, so a folder of them sorts by site then by date. */
export function reportFilename(siteKey, language, report, ext) {
    const day = (report.generatedAt || new Date().toISOString()).slice(0, 10);
    return `geo-report-${siteKey}-${language}-${day}.${ext}`;
}
