import React, {useCallback, useEffect, useState} from 'react';
import PropTypes from 'prop-types';
import {useTranslation} from 'react-i18next';
import {Banner, Button, Chip, Field, Input, Loader, Separator, Switch, TablePagination, Typography} from '@jahia/moonstone';
import {CronBuilder} from './CronBuilder';
import {scanStatus, runScan, saveSchedule} from '../api/siteScore';
import {jcontentUrl} from '../util/jcontentUrl';
import {Meter, BarList, FailureMatrix} from '../charts/Charts';
import styles from './Tabs.module.css';

const NS = 'geo-readiness';
const POLL_MS = 4000;


/**
 * GEO-17. The whole site, scored, on a schedule.
 *
 * Shows an aggregate and the pages that failed something, never a row per page:
 * that is what is stored, and it is what the four questions here need. The
 * template column is what makes a finding fixable once rather than four hundred
 * times, which is GEO-18's whole argument.
 */
export const SiteScorePanel = ({path, language}) => {
    const {t} = useTranslation(NS);
    // Pages with findings is a matrix, and a matrix of five hundred rows is a
    // wall. Ten at a time, with the column set computed over every finding so
    // the columns do not reshuffle as you page.
    const [findingsPage, setFindingsPage] = useState(1);
    const [findingsPerPage, setFindingsPerPage] = useState(10);
    const [state, setState] = useState(null);
    const [phase, setPhase] = useState('loading');
    const [cron, setCron] = useState('');
    const [scope, setScope] = useState('');
    const [enabled, setEnabled] = useState(false);
    const [error, setError] = useState(null);

    const load = useCallback(async (keepForm) => {
        try {
            const s = await scanStatus({path, language});
            setState(s);
            if (!keepForm) {
                setCron(s.cron || '');
                setScope(s.scope || '');
                setEnabled(Boolean(s.enabled));
            }

            setPhase('ready');
            return s;
        } catch (e) {
            setError(t('score17.error'));
            setPhase('ready');
            return null;
        }
    }, [path, language, t]);

    useEffect(() => {
        load(false);
    }, [load]);

    // While a scan runs, keep asking. Reading the state is deliberately cheap.
    useEffect(() => {
        if (!state || state.run.status !== 'running') {
            return undefined;
        }

        const id = setInterval(() => load(true), POLL_MS);
        return () => clearInterval(id);
    }, [state, load]);

    const scan = useCallback(async () => {
        setPhase('scanning');
        setError(null);
        try {
            await runScan({path, language, scope});
        } catch (e) {
            setError(e.code === 429 ? t('score17.rateLimit') : t('score17.error'));
        }

        await load(true);
        setPhase('ready');
    }, [path, language, scope, load, t]);

    /**
     * The content picker the rest of jContent uses, rather than asking someone
     * to type a repository path correctly from memory.
     */
    const pickScope = useCallback(() => {
        if (!window.CE_API || !window.CE_API.openPicker) {
            return;
        }

        window.CE_API.openPicker({
            type: 'editorial',
            initialSelectedItem: scope ? [scope] : [path],
            site: (window.jahiaGWTParameters && window.jahiaGWTParameters.siteKey) || undefined,
            lang: (window.jahiaGWTParameters && window.jahiaGWTParameters.uilang) || language,
            isMultiple: false,
            setValue: selected => {
                const first = Array.isArray(selected) ? selected[0] : selected;
                if (first && first.path) {
                    setScope(first.path);
                }
            }
        });
    }, [scope, path, language]);

    const save = useCallback(async () => {
        setError(null);
        try {
            const s = await saveSchedule({path, language, cron, enabled, scope});
            if (s.error) {
                setError(t('score17.badCron'));
                return;
            }

            setState(s);
        } catch (e) {
            setError(t('score17.error'));
        }
    }, [path, language, cron, enabled, scope, t]);

    if (phase === 'loading') {
        return <Loader size="big"/>;
    }

    const run = (state && state.run) || {};
    const agg = run.aggregate || null;
    const prev = run.previous || null;
    const movement = agg && prev && typeof prev.percent === 'number' ? agg.percent - prev.percent : null;

    return (
        <div className={styles.panel}>
            <Typography variant="heading" className={styles.panelTitle}>{t('score17.title')}</Typography>
            <Typography variant="body" className={styles.panelIntro}>{t('score17.intro')}</Typography>

            {error && <Banner variant="danger" title={t('score17.errorTitle')}>{error}</Banner>}

            {run.status === 'running' && (
                <Banner variant="info" title={t('score17.runningTitle')}>
                    {t('score17.running', {done: run.pagesDone, total: run.pagesTotal})}
                </Banner>
            )}
            {run.status === 'failed' && (
                <Banner variant="danger" title={t('score17.failedTitle')}>{t('score17.failed')}</Banner>
            )}

            {agg && (
                <>
                    <Separator spacing="medium" size="full"/>
                    <div className={styles.scoreHeadline}>
                        {t('score17.headline', {percent: agg.percent})}
                        {movement !== null && movement !== 0 && (
                            <span className={styles.scoreCount}>
                                {t(movement > 0 ? 'score17.up' : 'score17.down', {n: Math.abs(movement)})}
                            </span>
                        )}
                    </div>
                    {/*
                      * The one ratio the page leads with, as a meter: the fill
                      * carries severity and the headline above carries the words,
                      * so color is never the only signal.
                      */}
                    <Meter value={agg.percent} label={t('score17.headline', {percent: agg.percent})}/>
                    <Typography variant="caption" className={styles.panelIntro}>
                        {t('score17.counts', {
                            scored: agg.scored,
                            critical: agg.criticalPages,
                            unreadable: agg.unreadable
                        })}
                    </Typography>
                    {/*
                      * The same page reads 15/17 here and 16/18 in the drawer,
                      * which looks like a bug until somebody explains it. Name
                      * the missing check rather than leaving it to be noticed.
                      */}
                    <Typography variant="caption" className={styles.explain}>
                        {t('score17.whyFewer', {
                            check: t('score.check.sameContentForCrawlers.label')
                        })}
                    </Typography>
                    {agg.truncated && (
                        <Banner variant="warning" title={t('score17.truncatedTitle')}>
                            {t('score17.truncated', {pages: agg.pages})}
                        </Banner>
                    )}
                </>
            )}

            <div className={styles.actions}>
                <Button
                    size="big"
                    color="accent"
                    isDisabled={phase === 'scanning' || run.status === 'running'}
                    label={phase === 'scanning' ? t('score17.scanning') : t('score17.runNow')}
                    onClick={scan}
                />
                {run.finishedAt && (
                    <Typography variant="caption">
                        {t('score17.lastRun', {date: new Date(run.finishedAt).toLocaleString()})}
                    </Typography>
                )}
                {state && state.nextRun && (
                    <Typography variant="caption">
                        {t('score17.nextRun', {date: new Date(state.nextRun).toLocaleString()})}
                    </Typography>
                )}
            </div>

            {agg && (agg.sections || []).length > 0 && (
                <>
                    <Separator spacing="big" size="full"/>
                    <Typography variant="subheading" className={styles.panelSub}>
                        {t('score17.bySection')}
                    </Typography>
                    <BarList
                        max={100}
                        format={v => `${v}%`}
                        tipFor={r => t('score17.pages', {count: r.pages})}
                        rows={agg.sections.map(s => ({
                            key: s.section,
                            label: s.section,
                            sublabel: t('score17.pages', {count: s.pages}),
                            value: s.percent,
                            pages: s.pages,
                            status: s.percent >= 80 ? undefined : 'warn'
                        }))}
                    />
                </>
            )}

            {agg && (agg.templates || []).length > 0 && (
                <>
                    <Separator spacing="big" size="full"/>
                    <Typography variant="subheading" className={styles.panelSub}>
                        {t('score17.byTemplate')}
                    </Typography>
                    <Typography variant="caption" className={styles.panelIntro}>
                        {t('score17.byTemplateHelp')}
                    </Typography>
                    <ul className={styles.checkList}>
                        {agg.templates.filter(tpl => tpl.template).map(tpl => (
                            <li key={tpl.template} className={styles.checkItem}>
                                <span className={styles.checkText}>
                                    <Typography variant="body" className={styles.checkLabel}>
                                        {tpl.template}
                                    </Typography>
                                    <Typography variant="caption" className={styles.checkFix}>
                                        {t('score17.pages', {count: tpl.pages})}
                                    </Typography>
                                    {(tpl.fromTemplate || []).length > 0 && (
                                        <span className={styles.agentChips}>
                                            {tpl.fromTemplate.map(f => (
                                                <Chip
                                                    key={f.check}
                                                    color="warning"
                                                    label={t(`score.check.${f.check}.label`)}
                                                />
                                            ))}
                                        </span>
                                    )}
                                    {(tpl.fromTemplate || []).length === 0 && (
                                        <Typography variant="caption" className={styles.checkFix}>
                                            {t('score17.templateClean')}
                                        </Typography>
                                    )}
                                </span>
                                <span className={styles.checkMeta}>
                                    <Chip
                                        label={t('score17.oneFix', {count: (tpl.fromTemplate || []).length})}
                                        color={(tpl.fromTemplate || []).length > 0 ? 'warning' : 'success'}
                                    />
                                </span>
                            </li>
                        ))}
                    </ul>
                </>
            )}

            {(run.failures || []).length > 0 && (
                <>
                    <Separator spacing="big" size="full"/>
                    <Typography variant="subheading" className={styles.panelSub}>
                        {t('score17.worstPages')}
                    </Typography>
                    <Typography variant="caption" className={styles.panelIntro}>
                        {t('score17.matrixHelp')}
                    </Typography>
                    {/*
                      * Columns ordered by how many pages fail them, so a check
                      * every page fails is a solid stripe at the left - which is
                      * the template roll-up, visible before anyone reads a number.
                      */}
                    <FailureMatrix
                        pages={run.failures
                            .slice((findingsPage - 1) * findingsPerPage, findingsPage * findingsPerPage)
                            .map(f => ({
                                key: f.path,
                                label: f.title || f.path,
                                sublabel: f.template ? t('score17.template', {name: f.template}) : f.path,
                                href: jcontentUrl(f.path, language) || undefined,
                                failed: f.failed || [],
                                passed: f.passed,
                                total: f.total
                            }))}
                        checks={Object.entries(
                            run.failures.reduce((acc, f) => {
                                (f.failed || []).forEach(id => {
                                    acc[id] = (acc[id] || 0) + 1;
                                });
                                return acc;
                            }, {})
                        )
                            .sort((x, y) => y[1] - x[1])
                            .map(([id, count]) => ({
                                id,
                                count,
                                label: t(`score.check.${id}.label`),
                                severity: (agg.severities || {})[id] || 'advisory'
                            }))}
                        legend={{
                            critical: t('score.severity.critical'),
                            important: t('score.severity.important'),
                            advisory: t('score.severity.advisory')
                        }}
                        passLabel={t('score17.pass')}
                        tipFor={(pg, c) => `${c.label} · ${pg.label}`}
                        columnTipFor={c => t('score17.failsOn', {count: c.count, check: c.label})}
                    />
                    {run.failures.length > findingsPerPage && (
                        <TablePagination
                            currentPage={findingsPage}
                            totalNumberOfRows={run.failures.length}
                            rowsPerPage={findingsPerPage}
                            rowsPerPageOptions={[10, 25, 50]}
                            label={{rowsPerPage: t('score17.rowsPerPage'), of: t('score17.of')}}
                            onPageChange={setFindingsPage}
                            onRowsPerPageChange={n => {
                                setFindingsPerPage(n);
                                setFindingsPage(1);
                            }}
                        />
                    )}
                </>
            )}

            <Separator spacing="big" size="full"/>

            <Typography variant="subheading" className={styles.panelSub}>{t('score17.schedule')}</Typography>
            <Typography variant="caption" className={styles.panelIntro}>{t('score17.scheduleHelp')}</Typography>

            <Field id="geoEnabled" label={t('score17.enabledLabel')} helper={t('score17.enabledHelp')}>
                <span className={styles.switchCell}>
                    <Switch checked={enabled} onChange={(e, v, checked) => setEnabled(checked)}/>
                    <Typography variant="caption">
                        {enabled ? t('score17.enabled') : t('score17.disabled')}
                    </Typography>
                </span>
            </Field>

            <Field id="geoCron" label={t('score17.cronLabel')} helper={t('score17.cronHelp')}>
                <CronBuilder value={cron} onChange={setCron}/>
            </Field>

            <Field
                id="geoScope"
                label={t('score17.scopeLabel')}
                helper={t('score17.scopeHelp')}
                buttons={<Button label={t('score17.browse')} onClick={pickScope}/>}
            >
                <Input
                    value={scope}
                    placeholder={t('score17.scopePlaceholder')}
                    onChange={e => setScope(e.target.value)}
                />
            </Field>

            <div className={styles.actions}>
                <Button size="big" variant="outlined" label={t('score17.save')} onClick={save}/>
            </div>
        </div>
    );
};

SiteScorePanel.propTypes = {
    path: PropTypes.string.isRequired,
    language: PropTypes.string.isRequired
};
