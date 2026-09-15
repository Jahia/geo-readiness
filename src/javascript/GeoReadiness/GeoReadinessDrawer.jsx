import React, {useCallback, useEffect, useRef, useState} from 'react';
import PropTypes from 'prop-types';
import {useTranslation} from 'react-i18next';
import {Badge, Banner, Button, Close, Tab, TabItem, Typography} from '@jahia/moonstone';
import {runCrawlerCheck} from './api/crawlerCheck';
import {ScoreTab} from './tabs/ScoreTab';
import {CrawlerTab} from './tabs/CrawlerTab';
import {SiteFilesTab} from './tabs/SiteFilesTab';
import {SchemaTab} from './tabs/SchemaTab';
import tabStyles from './tabs/Tabs.module.css';
import styles from './GeoReadinessDrawer.module.css';

const NS = 'geo-readiness';
const CACHE_SCHEMA = 8;

// Results are cached per page and language so reopening the drawer is instant.
// Bump CACHE_SCHEMA whenever the report shape changes, or an old cached entry
// will be restored into a UI that no longer understands it.
const cacheKey = (path, language) => `geo-readiness:${CACHE_SCHEMA}:${language}:${path}`;

function loadCached(path, language) {
    try {
        const raw = window.localStorage.getItem(cacheKey(path, language));
        if (!raw) {
            return null;
        }
        const entry = JSON.parse(raw);
        return entry && entry.schema === CACHE_SCHEMA ? entry : null;
    } catch (e) {
        return null;
    }
}

function saveCached(path, language, report) {
    try {
        window.localStorage.setItem(
            cacheKey(path, language),
            JSON.stringify({schema: CACHE_SCHEMA, timestamp: Date.now(), report})
        );
    } catch (e) {
        // localStorage unavailable or full. Caching is a convenience, not a requirement.
    }
}

/** Things worth a badge on the Site files tab: no llms.txt, or an AI bot disallowed. */
function filesIssueCount(report) {
    const files = report.siteFiles || {};
    let n = 0;
    if (!(files.llms && files.llms.present)) {
        n++;
    }
    if (files.robots && files.robots.disallowedAiBotCount) {
        n += files.robots.disallowedAiBotCount;
    }
    if (files.robots && files.robots.present === false) {
        n++;
    }
    return n;
}

export const GeoReadinessDrawer = ({isOpen, path, language, onClose}) => {
    const {t} = useTranslation(NS);
    const [report, setReport] = useState(null);
    const [ranAt, setRanAt] = useState(null);
    const [phase, setPhase] = useState('idle');
    const [tab, setTab] = useState('score');
    const [error, setError] = useState(null);
    const drawerRef = useRef(null);
    const previouslyFocused = useRef(null);

    // A portal into document.body has no natural place in the page's own
    // focus order. Without this, opening the drawer leaves keyboard focus on
    // the trigger underneath - now visually hidden behind a fixed, 820px
    // panel - and closing it drops focus back to <body>, forgetting where
    // the user came from.
    useEffect(() => {
        if (isOpen) {
            previouslyFocused.current = document.activeElement;
            if (drawerRef.current) {
                drawerRef.current.focus();
            }
        } else if (previouslyFocused.current && typeof previouslyFocused.current.focus === 'function') {
            previouslyFocused.current.focus();
            previouslyFocused.current = null;
        }
    }, [isOpen]);

    // Escape to close, and a minimal Tab trap: neither existed, so keyboard
    // focus could walk out of the drawer into the page it now covers.
    const onKeyDown = useCallback(e => {
        if (e.key === 'Escape') {
            onClose();
            return;
        }

        if (e.key !== 'Tab' || !drawerRef.current) {
            return;
        }

        const focusable = drawerRef.current.querySelectorAll(
            'a[href], button:not([disabled]), input:not([disabled]), textarea:not([disabled]), select:not([disabled]), [tabindex]:not([tabindex="-1"])'
        );
        if (focusable.length === 0) {
            return;
        }

        const first = focusable[0];
        const last = focusable[focusable.length - 1];
        if (e.shiftKey && document.activeElement === first) {
            e.preventDefault();
            last.focus();
        } else if (!e.shiftKey && document.activeElement === last) {
            e.preventDefault();
            first.focus();
        }
    }, [onClose]);

    useEffect(() => {
        if (!isOpen) {
            return;
        }
        const cached = loadCached(path, language);
        if (cached) {
            setReport(cached.report);
            setRanAt(cached.timestamp);
            setPhase('done');
        } else {
            setReport(null);
            setRanAt(null);
            setPhase('idle');
        }
        setError(null);
    }, [isOpen, path, language]);

    const run = useCallback(async () => {
        setPhase('running');
        setError(null);
        try {
            const result = await runCrawlerCheck({path, language});
            setReport(result);
            setRanAt(Date.now());
            saveCached(path, language, result);
            setPhase('done');
        } catch (e) {
            setError(e.code === 'rateLimit' ? t('error.rateLimit') : t('error.generic'));
            setPhase('idle');
        }
    }, [path, language, t]);

    if (!isOpen) {
        return null;
    }

    return (
        // A real <dialog> rather than an <aside role="dialog">. Rendered with
        // the `open` attribute and NOT via showModal(): showModal() promotes the
        // node to the browser's top layer, which would take it out of
        // jContent's stacking context and break the panel's placement. With
        // `open` it stays in normal flow and keeps its own positioning, while
        // the element itself carries the dialog semantics.
        //
        // aria-modal and the focus trap stay explicit for the same reason -
        // they are ours to enforce, not the browser's, when the dialog is
        // non-modal.
        <dialog
            ref={drawerRef}
            open
            className={styles.drawer}
            aria-label={t('drawer.title')}
            aria-modal="true"
            tabIndex={-1}
            onKeyDown={onKeyDown}
        >
            <header className={styles.header}>
                <div>
                    <Typography variant="heading" component="h2" className={styles.title}>{t('drawer.title')}</Typography>
                    <Typography variant="caption" className={styles.subtitle}>{t('drawer.subtitle')}</Typography>
                </div>
                <Button variant="ghost" icon={<Close/>} label={t('drawer.close')} onClick={onClose}/>
            </header>

            <div className={styles.toolbar}>
                <Button
                    size="big"
                    color="accent"
                    isDisabled={phase === 'running'}
                    label={phase === 'running' ? t('drawer.running') : (report ? t('drawer.rerun') : t('drawer.run'))}
                    onClick={run}
                />
                {ranAt && (
                    <Typography variant="caption" className={styles.meta}>
                        {t('drawer.lastRun', {date: new Date(ranAt).toLocaleString()})}
                    </Typography>
                )}
            </div>

            {/* role="alert" carries an implicit assertive live region, so a failed check is announced without a second attribute. */}
            {error && <div className={styles.error} role="alert">{error}</div>}

            <div className={styles.body}>
                {phase === 'idle' && !report && (
                    <Typography variant="body" className={styles.empty}>{t('drawer.subtitle')}</Typography>
                )}

                {/*
                  * Absolute facts about the page, above the tabs rather than
                  * inside one: they frame every number below them, and the
                  * drawer does not open on the tab that used to hold them.
                  */}
                {/*
                  * Its own wording, not the score check's. A check label is
                  * phrased as the passing condition because it sits beside a
                  * pass mark; as a failure banner that reads as the opposite of
                  * what happened.
                  */}
                {report && report.visibility && report.visibility.guestReadable === false && (
                    <Banner variant="danger" title={t('visibility.gatedTitle')}>
                        {t('visibility.gated')}
                    </Banner>
                )}

                {report && report.visibility && report.visibility.noindex && (
                    <Banner variant="info" title={t('visibility.noindexTitle')}>
                        {t('visibility.noindex')}
                    </Banner>
                )}

                {report && report.sitemap && report.sitemap.missing && (
                    <Banner variant="warning" title={t('sitemap.notListedTitle')}>
                        {t('sitemap.notListed')}
                    </Banner>
                )}

                {/*
                  * Listed and noindex is two of our own files contradicting each
                  * other, and the contradiction is only visible per page.
                  */}
                {report && report.sitemap && report.sitemap.noindexListed && (
                    <Banner variant="warning" title={t('sitemap.noindexListedTitle')}>
                        {t('sitemap.noindexListedPage')}
                    </Banner>
                )}

                {/*
                  * GEO-22. Nothing pointing here is the loudest version of this;
                  * only the menu pointing here is the quieter one. Both are read
                  * from the last site scan, which is the only thing that sees
                  * the whole graph.
                  */}
                {report && report.links && report.links.content === 0 && report.links.nav === 0 && (
                    <Banner variant="warning" title={t('links.orphanTitle')}>
                        {t('links.orphanPage')}
                    </Banner>
                )}

                {report && report.links && report.links.content === 0 && report.links.nav > 0 && (
                    <Banner variant="info" title={t('links.navOnlyTitle')}>
                        {t('links.navOnlyPage', {count: report.links.nav})}
                    </Banner>
                )}

                {/*
                  * GEO-25. One banner for whichever conflict applies: the three
                  * kinds are mutually exclusive per page, and the detail names
                  * the addresses so the fix is obvious.
                  */}
                {/*
                  * GEO-20. Directly actionable while editing: the author in
                  * front of this page is the person who would write the missing
                  * translation.
                  */}
                {report && report.languages && (report.languages.missing || []).length > 0 && (
                    <Banner variant="info" title={t('languages.pageMissingTitle')}>
                        {t('languages.pageMissing', {
                            languages: report.languages.missing.join(', ')
                        })}
                    </Banner>
                )}

                {report && report.languages && (report.languages.notPublished || []).length > 0 && (
                    <Banner variant="warning" title={t('languages.pageUnpublishedTitle')}>
                        {t('languages.pageUnpublished', {
                            languages: report.languages.notPublished.join(', ')
                        })}
                    </Banner>
                )}

                {report && report.vanity && (
                    <Banner
                        variant={report.vanity.kind === 'unresolvable' ? 'warning' : 'info'}
                        title={t(`vanity.page.${report.vanity.why || report.vanity.kind}Title`)}
                    >
                        {t(`vanity.page.${report.vanity.why || report.vanity.kind}`, {
                            detail: report.vanity.detail
                        })}
                    </Banner>
                )}

                {report && report.sitemap && report.sitemap.staleDetail && (
                    <Banner variant="info" title={t('sitemap.staleTitle')}>
                        {t('sitemap.stalePage', {detail: report.sitemap.staleDetail})}
                    </Banner>
                )}

                {report && report.published && (
                    <Tab className={tabStyles.tabs}>
                        {[
                            {id: 'score', label: t('score.tab'), count: report.score ? report.score.criticalFailed : 0},
                            {id: 'crawler', label: t('crawler.tab'), count: report.blockedCount},
                            {id: 'files', label: t('files.tab'), count: filesIssueCount(report)},
                            {
                                id: 'schema',
                                label: t('schema.tab'),
                                count: report.schema && report.schema.mapped && !report.schema.valid ? 1 : 0
                            }
                        ].map(item => (
                            <TabItem
                                key={item.id}
                                id={`geo-drawer-tab-${item.id}`}
                                aria-controls={`geo-drawer-panel-${item.id}`}
                                label={item.label}
                                isSelected={tab === item.id}
                                icon={item.count > 0 ? <Badge label={String(item.count)} color="warning"/> : undefined}
                                onClick={() => setTab(item.id)}
                            />
                        ))}
                    </Tab>
                )}

                {/*
                  * An unpublished page has no public URL, so there is nothing to
                  * fetch and every tab below is gated on `published`. The tab bar
                  * is gated too, which used to leave the drawer completely blank:
                  * `tab` starts at 'score', ScoreTab was suppressed, and the one
                  * component that explains why - CrawlerTab - was unreachable
                  * because the bar that switches to it had just been hidden. The
                  * editor ran a check, waited, and got an empty panel.
                  *
                  * Say it here instead, outside the tab system, so the answer does
                  * not depend on which tab happens to be selected.
                  */}
                {report && !report.published && (
                    <Banner variant="info" title={t('crawler.verdict.unpublishedTitle')}>
                        {t('crawler.verdict.unpublished')} {t('error.notPublished')}
                    </Banner>
                )}

                {report && report.published && (
                    <div
                        role="tabpanel"
                        id={`geo-drawer-panel-${tab}`}
                        aria-labelledby={`geo-drawer-tab-${tab}`}
                        tabIndex={0}
                    >
                        {tab === 'score' && <ScoreTab report={report}/>}
                        {tab === 'crawler' && <CrawlerTab report={report}/>}
                        {tab === 'files' && <SiteFilesTab report={report}/>}
                        {tab === 'schema' && <SchemaTab report={report}/>}
                    </div>
                )}
            </div>
        </dialog>
    );
};

GeoReadinessDrawer.propTypes = {
    isOpen: PropTypes.bool.isRequired,
    path: PropTypes.string.isRequired,
    language: PropTypes.string.isRequired,
    onClose: PropTypes.func.isRequired
};
