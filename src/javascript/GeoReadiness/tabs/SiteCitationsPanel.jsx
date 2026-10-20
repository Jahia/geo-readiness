import React, {useEffect, useState} from 'react';
import PropTypes from 'prop-types';
import {useTranslation} from 'react-i18next';
import {Banner, Chip, Loader, Typography} from '@jahia/moonstone';
import {BarList, Histogram} from '../charts/Charts';
import {fetchSiteCitations} from '../api/citations';
import {jcontentUrl} from '../util/jcontentUrl';
import styles from './Tabs.module.css';
import own from './SiteCitationsPanel.module.css';

const NS = 'geo-readiness';

const formatDate = iso => {
    const d = new Date(iso);
    return Number.isNaN(d.getTime()) ? iso : d.toLocaleString();
};

const formatDay = day => {
    const d = new Date(`${day}T12:00:00Z`);
    return Number.isNaN(d.getTime()) ? day : d.toLocaleDateString();
};

const percent = value => (value === null || value === undefined ? '-' : `${(value * 100).toFixed(1)} %`);

/**
 * An ISO 8601 refresh interval in words, for the common shapes (P1D, P7D, P2W, PT12H); any other
 * duration is shown as sent.
 */
const intervalLabel = (interval, t) => {
    if (!interval) {
        return t('siteCitations.intervalNone');
    }

    const m = /^P(?:(\d+)([DW]))?(?:T(\d+)H)?$/.exec(interval);
    if (!m || (!m[1] && !m[3]) || (m[1] && m[3])) {
        return interval;
    }

    if (m[3]) {
        return t('siteCitations.intervalHours', {count: Number(m[3])});
    }

    return t(m[2] === 'W' ? 'siteCitations.intervalWeeks' : 'siteCitations.intervalDays', {count: Number(m[1])});
};

/** A change as a chip; null means BotRank has no comparable previous period. */
const Change = ({value, points, t}) => {
    if (value === null || value === undefined) {
        return <Chip label={t('citations.variationNone')} color="default"/>;
    }

    if (value === 0) {
        return <Chip label={t('citations.variationSame')} color="default"/>;
    }

    const shown = points ? (Math.abs(value) * 100).toFixed(1) : Math.abs(value);
    const key = points ? 'siteCitations.points' : 'siteCitations.count';
    return (
        <Chip
            label={`${value > 0 ? '+' : '-'}${t(key, {value: shown})}`}
            color={value > 0 ? 'success' : 'warning'}
        />
    );
};

Change.propTypes = {value: PropTypes.number, points: PropTypes.bool, t: PropTypes.func.isRequired};
Change.defaultProps = {value: null, points: false};

const Tile = ({label, value, change, points, t}) => (
    <div className={own.tile}>
        <span className={own.tileLabel}>{label}</span>
        <span className={own.tileValue}>{value}</span>
        <Change value={change} points={points} t={t}/>
    </div>
);

Tile.propTypes = {
    label: PropTypes.string.isRequired,
    value: PropTypes.node.isRequired,
    change: PropTypes.number,
    points: PropTypes.bool,
    t: PropTypes.func.isRequired
};
Tile.defaultProps = {change: null, points: false};

/** The figures of one registrable domain: totals, rank among cited sites, share per day. */
const Domain = ({domain, t}) => {
    if (domain.notCited) {
        return (
            <Banner variant="info" title={t('siteCitations.notCitedTitle', {domain: domain.domain})}>
                {t('siteCitations.notCited', {domain: domain.domain})}
            </Banner>
        );
    }

    const ranking = domain.ranking || [];
    const self = ranking.find(r => r.requested);
    const history = domain.shareHistory || [];

    return (
        <section className={own.domain} aria-label={domain.domain}>
            <Typography variant="heading" component="h3" className={styles.sectionTitle}>
                {t('siteCitations.domainTitle', {domain: domain.domain})}
            </Typography>
            <div className={own.tiles}>
                <Tile label={t('siteCitations.citations')} value={domain.citations} change={domain.variation} t={t}/>
                <Tile label={t('siteCitations.share')} value={percent(domain.share)} change={domain.shareVariation} points t={t}/>
                <Tile label={t('siteCitations.citedPages')} value={domain.citedPages} change={domain.citedPagesVariation} t={t}/>
            </div>

            {self && (
                <>
                    <Typography variant="heading" component="h4" className={styles.sectionTitle}>
                        {t('siteCitations.rank', {rank: self.rank})}
                    </Typography>
                    <ol className={own.ranking}>
                        {ranking.map(r => (
                            <li key={r.domain} value={r.rank} className={r.requested ? own.requested : undefined}>
                                <span className={own.rankDomain}>{r.domain}</span>
                                <span className={own.rankShare}>{percent(r.share)}</span>
                            </li>
                        ))}
                    </ol>
                </>
            )}

            {history.length > 0 && (
                <>
                    <Typography variant="heading" component="h4" className={styles.sectionTitle}>{t('siteCitations.shareHistory')}</Typography>
                    <Histogram
                        rows={history.map(d => ({key: d.date, label: '', value: d.share === null ? 0 : d.share * 100, day: d.date, share: d.share}))}
                        format={v => `${v.toFixed(1)} %`}
                        tipFor={r => (r.share === null ? `${formatDay(r.day)}, ${t('siteCitations.noSource')}` : formatDay(r.day))}
                        axisLeft={formatDay(history[0].date)}
                        axisRight={formatDay(history[history.length - 1].date)}
                    />
                </>
            )}
        </section>
    );
};

Domain.propTypes = {domain: PropTypes.object.isRequired, t: PropTypes.func.isRequired};

/**
 * Screen B: how AI answers cite the whole site, from the last BotRank sync.
 *
 * Totals, share and rank cover the registrable domain, as BotRank counts them; the hosts table splits
 * citations per host from the cited pages list. Everything shown comes from BotRank and is rendered
 * as text.
 */
export const SiteCitationsPanel = ({path, language}) => {
    const {t} = useTranslation(NS);
    const [data, setData] = useState(null);
    const [failed, setFailed] = useState(false);

    useEffect(() => {
        let alive = true;
        setData(null);
        setFailed(false);
        fetchSiteCitations({path, language})
            .then(d => alive && setData(d))
            .catch(() => alive && setFailed(true));
        return () => {
            alive = false;
        };
    }, [path, language]);

    const header = (
        <>
            <Typography variant="subheading" component="h2" className={styles.panelTitle}>{t('siteCitations.title')}</Typography>
            <Typography variant="caption" className={styles.panelIntro}>{t('siteCitations.intro')}</Typography>
        </>
    );

    if (failed) {
        return <div>{header}<Banner variant="danger" title={t('citations.loadErrorTitle')}>{t('citations.loadError')}</Banner></div>;
    }

    if (!data) {
        return <Loader size="big"/>;
    }

    if (!data.configured) {
        return (
            <div>
                {header}
                <Banner variant="info" title={t('siteCitations.notConfiguredTitle')}>
                    {t(data.canConfigure ? 'siteCitations.notConfiguredAdmin' : 'siteCitations.notConfigured')}
                </Banner>
            </div>
        );
    }

    if (!data.syncedAt || !data.site) {
        return <div>{header}<Banner variant="info" title={t('citations.notSyncedTitle')}>{t('siteCitations.notSynced')}</Banner></div>;
    }

    const site = data.site;
    const domains = site.domains || [];
    const hosts = site.hosts || [];
    const top = site.topPages || [];
    const visibility = site.visibility;
    const engines = site.engines || [];
    const splitHosts = hosts.length > 1 || hosts.some(h => h.host !== h.domain);

    return (
        <div>
            {header}
            <Typography variant="caption" className={styles.explain}>
                {data.latestAnswerAt ? t('citations.asOf', {date: formatDate(data.latestAnswerAt)}) : t('citations.asOfUnknown')}
                {` ${t('citations.syncedAt', {date: formatDate(data.syncedAt)})}`}
                {site.period ? ` ${t('siteCitations.period', {start: formatDay(site.period.start), end: formatDay(site.period.end)})}` : ''}
                {data.status === 'running' ? ` ${t('citations.running')}` : ''}
            </Typography>
            {site.failures > 0 && (
                <Banner variant="warning" title={t('siteCitations.partialTitle')}>{t('siteCitations.partial', {count: site.failures})}</Banner>
            )}

            {domains.map(d => <Domain key={d.domain} domain={d} t={t}/>)}
            {domains.some(d => !d.notCited) && (
                <Typography variant="caption" className={styles.explain}>{t('siteCitations.domainNote')}</Typography>
            )}

            {splitHosts && (
                <>
                    <Typography variant="heading" component="h3" className={styles.sectionTitle}>{t('siteCitations.hosts')}</Typography>
                    <table className={own.table}>
                        <thead>
                            <tr>
                                <th scope="col">{t('siteCitations.host')}</th>
                                <th scope="col">{t('siteCitations.citations')}</th>
                                <th scope="col">{t('siteCitations.citedPages')}</th>
                                <th scope="col">{t('siteCitations.approxShare')}</th>
                            </tr>
                        </thead>
                        <tbody>
                            {hosts.map(h => (
                                <tr key={h.host}>
                                    <th scope="row">{h.host}</th>
                                    <td>{h.citations}</td>
                                    <td>{h.citedPages}</td>
                                    <td>{percent(h.share)}</td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                    <Typography variant="caption" className={styles.explain}>{t('siteCitations.hostsNote')}</Typography>
                </>
            )}

            <Typography variant="heading" component="h3" className={styles.sectionTitle}>{t('siteCitations.topPages')}</Typography>
            {top.length === 0 ? (
                <Typography variant="caption" className={styles.explain}>{t('siteCitations.noTopPages')}</Typography>
            ) : (
                <ol className={own.top}>
                    {top.map(p => {
                        const href = p.jcrPath ? jcontentUrl(p.jcrPath, p.language || language) : null;
                        const base = globalThis.contextJsParameters?.contextPath || '';
                        return (
                            <li key={p.url} value={p.rank} className={own.topItem}>
                                <span className={own.topName}>
                                    {href ? <a className={styles.pageLink} href={`${base}${href}`}>{p.title || p.jcrPath}</a> : <span>{t('siteCitations.unmatched')}</span>}
                                    <span className={own.topUrl}>{p.url}</span>
                                </span>
                                <span className={own.topFigures}>
                                    {t('siteCitations.count', {value: p.citations})}
                                    <Change value={p.variation} t={t}/>
                                </span>
                            </li>
                        );
                    })}
                </ol>
            )}
            {data.unresolved > 0 && (
                <Typography variant="caption" className={styles.explain}>{t('siteCitations.unresolved', {count: data.unresolved})}</Typography>
            )}

            {visibility && (
                <>
                    <Typography variant="heading" component="h3" className={styles.sectionTitle}>{t('siteCitations.visibility')}</Typography>
                    <div className={own.tiles}>
                        <Tile
                            label={t('siteCitations.visibilityScore', {count: visibility.answers})}
                            value={percent(visibility.score)}
                            change={visibility.variation}
                            points
                            t={t}
                        />
                    </div>
                    {(visibility.byEngine || []).length > 0 && (
                        <BarList
                            rows={visibility.byEngine.map(e => ({key: e.key, label: t(`citations.surface.${e.key}`, e.key), value: (e.score || 0) * 100}))}
                            max={100}
                            format={v => `${v.toFixed(1)} %`}
                        />
                    )}
                    <Typography variant="caption" className={styles.explain}>{t('siteCitations.visibilityNote')}</Typography>
                </>
            )}

            {engines.length > 0 && (
                <>
                    <Typography variant="heading" component="h3" className={styles.sectionTitle}>{t('siteCitations.engines')}</Typography>
                    <ul className={styles.facts}>
                        {engines.map(e => (
                            <li key={e.aiSurface}>
                                {t('siteCitations.engine', {engine: t(`citations.surface.${e.aiSurface}`, e.aiSurface), interval: intervalLabel(e.refreshInterval, t)})}
                            </li>
                        ))}
                    </ul>
                </>
            )}

            <Typography variant="caption" className={styles.explain}>{t('siteCitations.source')}</Typography>
        </div>
    );
};

SiteCitationsPanel.propTypes = {
    path: PropTypes.string.isRequired,
    language: PropTypes.string.isRequired
};
