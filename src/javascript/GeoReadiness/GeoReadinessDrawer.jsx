import React, {useCallback, useEffect, useState} from 'react';
import PropTypes from 'prop-types';
import {useTranslation} from 'react-i18next';
import {Button, Close, Typography} from '@jahia/moonstone';
import {runCrawlerCheck} from './api/crawlerCheck';
import {CrawlerTab} from './tabs/CrawlerTab';
import {SiteFilesTab} from './tabs/SiteFilesTab';
import tabStyles from './tabs/Tabs.module.css';
import styles from './GeoReadinessDrawer.module.css';

const NS = 'geo-readiness';
const CACHE_SCHEMA = 2;

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
    const [tab, setTab] = useState('crawler');
    const [error, setError] = useState(null);

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
        <aside className={styles.drawer} aria-label={t('drawer.title')}>
            <header className={styles.header}>
                <div>
                    <Typography variant="heading" className={styles.title}>{t('drawer.title')}</Typography>
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

            {error && <div className={styles.error}>{error}</div>}

            <div className={styles.body}>
                {phase === 'idle' && !report && (
                    <Typography variant="body" className={styles.empty}>{t('drawer.subtitle')}</Typography>
                )}

                {report && report.published && (
                    <div className={tabStyles.tabs}>
                        <button
                            type="button"
                            className={`${tabStyles.tab} ${tab === 'crawler' ? tabStyles.tabActive : ''}`}
                            onClick={() => setTab('crawler')}
                        >
                            {t('crawler.tab')}
                            {report.blockedCount > 0 && <span className={tabStyles.tabBadge}>{report.blockedCount}</span>}
                        </button>
                        <button
                            type="button"
                            className={`${tabStyles.tab} ${tab === 'files' ? tabStyles.tabActive : ''}`}
                            onClick={() => setTab('files')}
                        >
                            {t('files.tab')}
                            {filesIssueCount(report) > 0 && (
                                <span className={tabStyles.tabBadge}>{filesIssueCount(report)}</span>
                            )}
                        </button>
                    </div>
                )}

                {report && tab === 'crawler' && <CrawlerTab report={report}/>}
                {report && report.published && tab === 'files' && <SiteFilesTab report={report}/>}
            </div>
        </aside>
    );
};

GeoReadinessDrawer.propTypes = {
    isOpen: PropTypes.bool.isRequired,
    path: PropTypes.string.isRequired,
    language: PropTypes.string.isRequired,
    onClose: PropTypes.func.isRequired
};
