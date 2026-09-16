import React, {useCallback, useEffect, useState} from 'react';
import PropTypes from 'prop-types';
import {useTranslation} from 'react-i18next';
import {Banner, Button, Chip, Loader, Separator, Typography} from '@jahia/moonstone';
import {fetchReportStatus, generateReport, readReport} from '../api/geoReport';
import {jcontentUrl} from '../util/jcontentUrl';
import {downloadText, reportFilename, reportToMarkdown} from '../util/exportReport';
import styles from './Tabs.module.css';

const NS = 'geo-readiness';
const AREAS = ['reachability', 'content', 'structured_data', 'freshness', 'languages', 'site_files', 'links', 'addresses'];
const STATUS_COLOR = {met: 'success', partial: 'warning', missing: 'danger'};
const SEVERITY_COLOR = {critical: 'danger', important: 'warning', advisory: 'default'};
const VERDICT_COLOR = {compliant: 'success', partial: 'warning', not_compliant: 'danger'};

/**
 * The written report: what the measurements add up to, and what to do first.
 *
 * Everything else on this dashboard is deterministic and says so. This tab is
 * the one place a model is asked for judgement, and it is only asked on a
 * click, because each answer costs money and takes a while. The answer is a
 * fixed shape the server parsed, rendered here as text: nothing the model
 * wrote is ever placed in the page as markup.
 */
export const ReportPanel = ({path, language, reportLanguage, siteKey, siteName}) => {
    const {t} = useTranslation(NS);
    const [status, setStatus] = useState(null);
    const [report, setReport] = useState(null);
    const [phase, setPhase] = useState('loading');
    const [error, setError] = useState('');

    useEffect(() => {
        let alive = true;
        (async () => {
            try {
                const [s, r] = await Promise.all([
                    fetchReportStatus({path, language}),
                    readReport({path, language})
                ]);
                if (alive) {
                    setStatus(s);
                    setReport(r.report || null);
                    setPhase('idle');
                }
            } catch (e) {
                // Either call failing means the tab cannot offer anything, so it
                // renders as unconfigured. The reason still belongs in the console.
                console.warn('geo-readiness: the report status could not be read', e);
                if (alive) {
                    setStatus({enabled: false});
                    setPhase('idle');
                }
            }
        })();
        return () => {
            alive = false;
        };
    }, [path, language]);

    const generate = useCallback(async () => {
        setPhase('generating');
        setError('');
        try {
            const r = await generateReport({path, language, reportLanguage});
            setReport(r.report);
            setPhase('idle');
        } catch (e) {
            setError(e.code === 429 ? t('report.rateLimited') : (e.message || t('report.errorGeneric')));
            setPhase('error');
        }
    }, [path, language, reportLanguage, t]);

    const exportAs = useCallback(ext => {
        if (!report) {
            return;
        }

        if (ext === 'json') {
            downloadText(reportFilename(siteKey, language, report, 'json'),
                JSON.stringify(report, null, 2), 'application/json');
        } else {
            downloadText(reportFilename(siteKey, language, report, 'md'),
                reportToMarkdown(report, {site: siteName || siteKey, t}), 'text/markdown');
        }
    }, [report, siteKey, siteName, language, t]);

    if (phase === 'loading') {
        return <Loader size="big"/>;
    }

    const generating = phase === 'generating';
    let generateLabel = t('report.generate');
    if (generating) {
        generateLabel = t('report.generating');
    } else if (report) {
        generateLabel = t('report.regenerate');
    }

    return (
        <div>
            <Typography variant="subheading" component="h2" className={styles.panelTitle}>
                {t('report.title')}
            </Typography>
            <Typography variant="caption" className={styles.panelIntro}>
                {t('report.intro')}
            </Typography>

            {status && !status.enabled && (
                <Banner variant="info" title={t('report.notConfiguredTitle')}>
                    {t('report.notConfigured')}
                </Banner>
            )}

            {status && status.enabled && (
                <>
                    <Typography variant="caption" className={styles.panelIntro}>
                        {t('report.provider', {provider: status.provider, model: status.model})}
                        {' · '}
                        {t('report.privacy')}
                    </Typography>

                    <div className={styles.actions}>
                        <Button
                            size="big"
                            color="accent"
                            isDisabled={generating}
                            label={generateLabel}
                            onClick={generate}
                        />
                        {report && !generating && (
                            <>
                                <Button size="big" label={t('report.export.markdown')} onClick={() => exportAs('md')}/>
                                <Button size="big" label={t('report.export.json')} onClick={() => exportAs('json')}/>
                            </>
                        )}
                        {generating && (
                            <Typography variant="caption">{t('report.generatingHint')}</Typography>
                        )}
                    </div>

                    {phase === 'error' && (
                        <Banner variant="danger" title={t('report.errorTitle')}>
                            {error}
                        </Banner>
                    )}

                    {!report && !generating && phase !== 'error' && (
                        <Banner variant="info" title={t('report.neverTitle')}>
                            {t('report.never')}
                        </Banner>
                    )}

                    {report && <Report report={report} language={language} reportLanguage={reportLanguage} t={t}/>}
                </>
            )}
        </div>
    );
};

ReportPanel.propTypes = {
    path: PropTypes.string.isRequired,
    language: PropTypes.string.isRequired,
    reportLanguage: PropTypes.string.isRequired,
    siteKey: PropTypes.string.isRequired,
    siteName: PropTypes.string
};

ReportPanel.defaultProps = {siteName: ''};

/** The parsed shape, section by section. Text nodes only. */
const Report = ({report, language, reportLanguage, t}) => {
    const when = (report.generatedAt || '').replace('T', ' ').replace(/\.\d+Z$/, ' UTC');
    const compliance = report.compliance || [];
    const priorities = report.priorities || [];
    const wins = report.quickWins || [];
    const roadmap = report.roadmap || {};

    return (
        <>
            <Separator spacing="big" size="full"/>

            <div className={styles.reportMeta}>
                <Chip
                    label={t(`report.verdict.${report.verdict || 'partial'}`)}
                    color={VERDICT_COLOR[report.verdict] || 'warning'}
                />
                <Typography variant="caption" className={styles.checkFix}>
                    {t('report.generatedAt', {date: when})} · {report.provider} · {report.model}
                </Typography>
            </div>

            {report.reportLanguage && report.reportLanguage !== reportLanguage && (
                <Banner variant="info" title={t('report.otherLanguageTitle')}>
                    {t('report.otherLanguage', {have: report.reportLanguage, want: reportLanguage})}
                </Banner>
            )}
            {report.truncated && (
                <Banner variant="warning" title={t('report.truncatedTitle')}>
                    {t('report.truncated')}
                </Banner>
            )}

            {report.summary && (
                <p className={styles.reportSummary}>{report.summary}</p>
            )}

            <Typography variant="subheading" component="h3" className={styles.panelSub}>
                {t('report.compliance')}
            </Typography>
            <ul className={styles.checkList}>
                {AREAS.map(area => {
                    const row = compliance.find(c => c.area === area);
                    if (!row) {
                        return null;
                    }

                    return (
                        <li key={area} className={styles.checkItem}>
                            <span className={styles.checkText}>
                                <Typography variant="body" className={styles.checkLabel}>
                                    {t(`report.area.${area}`)}
                                </Typography>
                                <Typography variant="caption" className={styles.checkFix}>
                                    {row.note}
                                </Typography>
                            </span>
                            <span className={styles.checkMeta}>
                                <Chip label={t(`report.status.${row.status}`)} color={STATUS_COLOR[row.status] || 'warning'}/>
                            </span>
                        </li>
                    );
                })}
            </ul>

            <Separator spacing="big" size="full"/>
            <Typography variant="subheading" component="h3" className={styles.panelSub}>
                {t('report.priorities')}
            </Typography>
            {priorities.length === 0 && (
                <Typography variant="caption" className={styles.panelIntro}>{t('report.none')}</Typography>
            )}
            <ol className={styles.priorityList}>
                {priorities.map((p, i) => (
                    <li key={`${i}:${p.title}`} className={styles.priority}>
                        <div className={styles.priorityHead}>
                            <Chip label={t(`score.severity.${p.severity}`)} color={SEVERITY_COLOR[p.severity] || 'default'}/>
                            <Typography variant="body" className={styles.priorityTitle}>{p.title}</Typography>
                        </div>
                        <Typography variant="caption" className={styles.checkFix}>
                            {t(`report.area.${p.area}`)} · {t(`report.owner.${p.owner}`)} · {t(`report.effort.${p.effort}`)}
                        </Typography>
                        {p.why && (
                            <p className={styles.priorityText}>
                                <strong>{t('report.why')}</strong> {p.why}
                            </p>
                        )}
                        {p.how && (
                            <p className={styles.priorityText}>
                                <strong>{t('report.how')}</strong> {p.how}
                            </p>
                        )}
                        {(p.pages || []).length > 0 && (
                            <ul className={styles.priorityPages}>
                                {p.pages.map(pg => (
                                    <li key={pg}>
                                        {jcontentUrl(pg, language) ? (
                                            <a className={styles.pageLink} href={jcontentUrl(pg, language)}>{pg}</a>
                                        ) : (
                                            <span>{pg}</span>
                                        )}
                                    </li>
                                ))}
                            </ul>
                        )}
                    </li>
                ))}
            </ol>

            {wins.length > 0 && (
                <>
                    <Separator spacing="big" size="full"/>
                    <Typography variant="subheading" component="h3" className={styles.panelSub}>
                        {t('report.quickWins')}
                    </Typography>
                    <ul className={styles.facts}>
                        {wins.map(w => <li key={w}>{w}</li>)}
                    </ul>
                </>
            )}

            {['now', 'next', 'later'].some(k => (roadmap[k] || []).length > 0) && (
                <>
                    <Separator spacing="big" size="full"/>
                    <Typography variant="subheading" component="h3" className={styles.panelSub}>
                        {t('report.roadmap')}
                    </Typography>
                    <div className={styles.roadmap}>
                        {['now', 'next', 'later'].map(k => (
                            <div key={k} className={styles.roadmapCol}>
                                <Typography variant="body" className={styles.checkLabel}>{t(`report.phase.${k}`)}</Typography>
                                <ul className={styles.facts}>
                                    {(roadmap[k] || []).map(x => <li key={x}>{x}</li>)}
                                </ul>
                            </div>
                        ))}
                    </div>
                </>
            )}

            <p className={styles.explain}>
                {report.usage && report.usage.inputTokens >= 0 ?
                    `${t('report.usage', {input: report.usage.inputTokens, output: report.usage.outputTokens})} · ` :
                    ''}
                {t('report.disclaimer')}
            </p>
        </>
    );
};

Report.propTypes = {
    report: PropTypes.object.isRequired,
    language: PropTypes.string.isRequired,
    reportLanguage: PropTypes.string.isRequired,
    t: PropTypes.func.isRequired
};
