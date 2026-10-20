import React, {useEffect, useState} from 'react';
import PropTypes from 'prop-types';
import {useTranslation} from 'react-i18next';
import {Banner, Chip, Loader} from '@jahia/moonstone';
import {BarList, Histogram} from '../charts/Charts';
import {fetchSiteCitations} from '../api/citations';
import {jcontentUrl} from '../util/jcontentUrl';
import {Card, Columns, Figure, Figures, Meta, Note, Panel, PanelHeader, Ring, Source} from '../dashboard/Layout';
import styles from './Tabs.module.css';
import own from './SiteCitationsPanel.module.css';

const NS = 'geo-readiness';

/** The four sub-tabs of the dashboard's AI citations group, in order. */
export const CITATION_VIEWS = ['overview', 'ranking', 'pages', 'engines'];

/**
 * One read per site and language for the four sub-tabs: switching tabs remounts the panel, and the
 * stored summary only changes with a sync. Kept for a minute, so a sync just finished shows soon.
 */
const cache = new Map();
const CACHE_MS = 60000;

function loadSite(path, language) {
    const key = `${path}|${language}`;
    const hit = cache.get(key);
    if (hit && Date.now() - hit.at < CACHE_MS) {
        return hit.promise;
    }

    const promise = fetchSiteCitations({path, language});
    cache.set(key, {at: Date.now(), promise});
    promise.catch(() => cache.delete(key));
    return promise;
}

/** Number formats in the UI language, so French reads 1,6 % and 1 473. */
const formats = lang => {
    const pct = new Intl.NumberFormat(lang, {style: 'percent', minimumFractionDigits: 1, maximumFractionDigits: 1});
    const int = new Intl.NumberFormat(lang);
    const one = new Intl.NumberFormat(lang, {minimumFractionDigits: 1, maximumFractionDigits: 1});
    return {
        pct: v => (v === null || v === undefined ? '-' : pct.format(v)),
        int: v => (v === null || v === undefined ? '-' : int.format(v)),
        points: v => one.format(Math.abs(v) * 100),
        date: iso => {
            const d = new Date(iso);
            return Number.isNaN(d.getTime()) ? iso : d.toLocaleString(lang);
        },
        day: day => {
            const d = new Date(`${day}T12:00:00Z`);
            return Number.isNaN(d.getTime()) ? day : d.toLocaleDateString(lang, {day: 'numeric', month: 'short'});
        }
    };
};

/** A rank as people write it: #12 in English, 12e and 1er in French. */
const rankLabel = (rank, t) => (rank === 1 ? t('siteCitations.rankFirst') : t('siteCitations.rankShort', {rank}));

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

/** A change as a coloured chip; null means BotRank has no comparable previous period. */
const Change = ({value, points, inverse, f, t}) => {
    if (value === null || value === undefined) {
        return <Chip label={t('citations.variationNone')} color="default"/>;
    }

    if (value === 0) {
        return <Chip label={t('citations.variationSame')} color="default"/>;
    }

    // For a rank, a smaller number is the better one.
    const better = inverse ? value < 0 : value > 0;
    const shown = points ? t('siteCitations.points', {value: f.points(value)}) : f.int(Math.abs(value));
    return <Chip label={`${value > 0 ? '+' : '-'}${shown}`} color={better ? 'success' : 'danger'}/>;
};

Change.propTypes = {
    value: PropTypes.number,
    points: PropTypes.bool,
    inverse: PropTypes.bool,
    f: PropTypes.object.isRequired,
    t: PropTypes.func.isRequired
};
Change.defaultProps = {value: null, points: false, inverse: false};

/** A small line of the share per day, for the share card; decorative, the figure is written beside it. */
const Sparkline = ({values}) => {
    const points = values.filter(v => v !== null && v !== undefined);
    if (points.length < 2) {
        return null;
    }

    const w = 140;
    const h = 36;
    const max = Math.max(...points);
    const min = Math.min(...points);
    const span = max - min || 1;
    const step = w / (points.length - 1);
    const xy = points.map((v, i) => [i * step, h - 3 - ((v - min) / span) * (h - 6)]);
    const line = xy.map(([x, y]) => `${x.toFixed(1)},${y.toFixed(1)}`).join(' ');
    const [lx, ly] = xy[xy.length - 1];
    return (
        <svg className={own.spark} viewBox={`-2 0 ${w + 4} ${h}`} width={w} height={h} aria-hidden="true" focusable="false">
            <polyline points={`0,${h} ${line} ${w},${h}`} className={own.sparkArea}/>
            <polyline points={line} className={own.sparkLine}/>
            <circle cx={lx} cy={ly} r="3" className={own.sparkDot}/>
        </svg>
    );
};

Sparkline.propTypes = {values: PropTypes.arrayOf(PropTypes.number)};
Sparkline.defaultProps = {values: []};

/** What to read first, computed from the summary: one sentence each, only when it says something. */
function takeaways(domain, site, unresolved, f, t) {
    const out = [];
    const ranking = domain.ranking || [];
    const i = ranking.findIndex(r => r.requested);
    if (i > 0) {
        const above = ranking[i - 1];
        const gap = t('siteCitations.points', {value: f.points(above.share - ranking[i].share)});
        out.push({tone: 'info', text: t('siteCitations.tk.gap', {gap, domain: above.domain, rank: rankLabel(above.rank, t)})});
    } else if (i === 0 && ranking[i].rank === 1) {
        out.push({tone: 'good', text: t('siteCitations.tk.first')});
    }

    const top = site.topPages || [];
    if (domain.citations > 0 && top.length >= 3) {
        const three = top.slice(0, 3).reduce((a, p) => a + p.citations, 0);
        out.push({tone: 'info', text: t('siteCitations.tk.top3', {share: f.pct(three / domain.citations)})});
    }

    const unmatched = top.filter(p => !p.jcrPath).length;
    if (unmatched > 0) {
        out.push({tone: 'warn', text: t('siteCitations.tk.unmatched', {count: unmatched, total: top.length})});
    } else if (unresolved > 0) {
        out.push({tone: 'warn', text: t('siteCitations.tk.unresolved', {count: unresolved})});
    }

    const days = (domain.shareHistory || []).filter(d => d.share !== null);
    if (days.length > 1) {
        const best = days.reduce((a, d) => (d.share > a.share ? d : a));
        out.push({tone: 'info', text: t('siteCitations.tk.bestDay', {day: f.day(best.date), share: f.pct(best.share)})});
    }

    if (domain.variation === null) {
        out.push({tone: 'muted', text: t('siteCitations.tk.noComparison')});
    }

    return out;
}

const Overview = ({data, f, t}) => {
    const site = data.site;
    const domains = site.domains || [];
    const cited = domains.filter(d => !d.notCited);
    const notCited = domains.filter(d => d.notCited);
    const visibility = site.visibility;

    return (
        <>
            {cited.map(d => {
                const self = (d.ranking || []).find(r => r.requested);
                const tips = takeaways(d, site, data.unresolved, f, t);
                return (
                    <React.Fragment key={d.domain}>
                        <p className={own.domainLine}>
                            <span className={own.domainName}>{d.domain}</span>
                            <span className={own.domainNote}>{t('siteCitations.domainScope')}</span>
                        </p>
                        <div className={own.hero}>
                            <div className={own.rankCard}>
                                <span className={own.rankLabel}>{t('siteCitations.rankLabel')}</span>
                                <span className={own.rankBadge}>{self ? rankLabel(self.rank, t) : '-'}</span>
                                <span className={own.rankOf}>{t('siteCitations.rankOf')}</span>
                                {self && <Change value={self.rankVariation} inverse f={f} t={t}/>}
                            </div>
                            <Figures columns={3}>
                                <Figure label={t('siteCitations.share')} value={f.pct(d.share)} tone="accent">
                                    <Sparkline values={(d.shareHistory || []).map(x => x.share)}/>
                                    <Change value={d.shareVariation} points f={f} t={t}/>
                                </Figure>
                                <Figure label={t('siteCitations.citations')} value={f.int(d.citations)} sub={t('siteCitations.last30')}>
                                    <Change value={d.variation} f={f} t={t}/>
                                </Figure>
                                <Figure label={t('siteCitations.citedPages')} value={f.int(d.citedPages)} sub={t('siteCitations.citedPagesSub')}>
                                    <Change value={d.citedPagesVariation} f={f} t={t}/>
                                </Figure>
                            </Figures>
                        </div>

                        <Columns>
                            {tips.length > 0 && (
                                <Card title={t('siteCitations.takeaways')}>
                                    <ul className={own.tips}>
                                        {tips.map(x => <li key={x.text} className={own[`tip_${x.tone}`]}>{x.text}</li>)}
                                    </ul>
                                </Card>
                            )}
                            {visibility && (
                                <Card title={t('siteCitations.visibility')}>
                                    <div className={own.visibility}>
                                        <Ring value={visibility.score} text={f.pct(visibility.score)}/>
                                        <div className={own.visibilityText}>
                                            <p>{t('siteCitations.visibilityScore', {count: visibility.answers, answers: f.int(visibility.answers)})}</p>
                                            <Change value={visibility.variation} points f={f} t={t}/>
                                        </div>
                                    </div>
                                    <Note>{t('siteCitations.visibilityNote')}</Note>
                                </Card>
                            )}
                        </Columns>
                    </React.Fragment>
                );
            })}

            {notCited.map(d => (
                <Banner key={d.domain} variant="info" title={t('siteCitations.notCitedTitle', {domain: d.domain})}>
                    {t('siteCitations.notCited', {domain: d.domain})}
                </Banner>
            ))}
        </>
    );
};

Overview.propTypes = {data: PropTypes.object.isRequired, f: PropTypes.object.isRequired, t: PropTypes.func.isRequired};

const Ranking = ({data, f, t}) => {
    const cited = (data.site.domains || []).filter(d => !d.notCited);
    if (cited.length === 0) {
        return <Banner variant="info" title={t('siteCitations.noRankingTitle')}>{t('siteCitations.noRanking')}</Banner>;
    }

    return cited.map(d => {
        const ranking = d.ranking || [];
        const top = Math.max(...ranking.map(r => r.share || 0), 0.0001);
        const days = (d.shareHistory || []).filter(x => x.share !== null);
        const avg = days.length ? days.reduce((a, x) => a + x.share, 0) / days.length : null;
        return (
            <React.Fragment key={d.domain}>
                <Card title={t('siteCitations.leaderboard', {domain: d.domain})} intro={t('siteCitations.leaderboardIntro')}>
                    <ol className={own.board}>
                        {ranking.map(r => (
                            <li key={r.domain} className={r.requested ? own.boardSelf : own.boardRow}>
                                <span className={own.boardRank}>{r.rank}</span>
                                <span className={own.boardName}>
                                    {r.domain}
                                    {r.requested && <span className={own.youTag}>{t('siteCitations.thisSite')}</span>}
                                </span>
                                <span className={own.boardBar} aria-hidden="true">
                                    <span style={{width: `${Math.max(2, ((r.share || 0) / top) * 100)}%`}}/>
                                </span>
                                <span className={own.boardShare}>{f.pct(r.share)}</span>
                            </li>
                        ))}
                    </ol>
                </Card>

                {days.length > 0 && (
                    <Card title={t('siteCitations.shareHistory')} intro={t('siteCitations.shareHistoryIntro')}>
                        <div className={own.stats}>
                            <Figure label={t('siteCitations.statLast')} value={f.pct(days[days.length - 1].share)} tone="accent"/>
                            <Figure label={t('siteCitations.statAverage')} value={f.pct(avg)}/>
                            <Figure label={t('siteCitations.statBest')} value={f.pct(Math.max(...days.map(x => x.share)))}/>
                            <Figure label={t('siteCitations.statLow')} value={f.pct(Math.min(...days.map(x => x.share)))}/>
                        </div>
                        <Histogram
                            rows={d.shareHistory.map(x => ({key: x.date, label: '', value: x.share === null ? 0 : x.share * 100, day: x.date, share: x.share}))}
                            format={v => f.pct(v / 100)}
                            tipFor={r => (r.share === null ? `${f.day(r.day)}, ${t('siteCitations.noSource')}` : f.day(r.day))}
                            axisLeft={f.day(d.shareHistory[0].date)}
                            axisRight={f.day(d.shareHistory[d.shareHistory.length - 1].date)}
                        />
                    </Card>
                )}
                <Note>{t('siteCitations.domainNote')}</Note>
            </React.Fragment>
        );
    });
};

Ranking.propTypes = {data: PropTypes.object.isRequired, f: PropTypes.object.isRequired, t: PropTypes.func.isRequired};

const Pages = ({data, language, f, t}) => {
    const site = data.site;
    const top = site.topPages || [];
    const hosts = site.hosts || [];
    const most = Math.max(1, ...top.map(p => p.citations));
    const base = globalThis.contextJsParameters?.contextPath || '';
    // A change on every row says nothing when BotRank has no comparable period for any of them.
    const anyChange = top.some(p => p.variation !== null && p.variation !== undefined);

    return (
        <>
            <Card title={t('siteCitations.topPages')} intro={t('siteCitations.topPagesIntro', {count: top.length})}>
                {top.length === 0 ? (
                    <Note>{t('siteCitations.noTopPages')}</Note>
                ) : (
                    <ol className={own.pages}>
                        {top.map(p => {
                            const href = p.jcrPath ? jcontentUrl(p.jcrPath, p.language || language) : null;
                            return (
                                <li key={p.url} className={own.page}>
                                    <span className={own.pageRank}>{p.rank}</span>
                                    <span className={own.pageMain}>
                                        <span className={own.pageTitle}>
                                            {href ? (
                                                <a className={styles.pageLink} href={`${base}${href}`}>{p.title || p.jcrPath}</a>
                                            ) : (
                                                <span className={own.unmatched}>{t('siteCitations.unmatched')}</span>
                                            )}
                                        </span>
                                        <span className={own.pageUrl}>{p.url}</span>
                                        <span className={own.pageBar} aria-hidden="true"><span style={{width: `${(p.citations / most) * 100}%`}}/></span>
                                    </span>
                                    <span className={own.pageFigures}>
                                        <strong>{f.int(p.citations)}</strong>
                                        {anyChange && <Change value={p.variation} f={f} t={t}/>}
                                    </span>
                                </li>
                            );
                        })}
                    </ol>
                )}
                {!anyChange && top.length > 0 && <Note>{t('siteCitations.tk.noComparison')}</Note>}
                {data.unresolved > 0 && <Note>{t('siteCitations.unresolved', {count: data.unresolved})}</Note>}
            </Card>

            {hosts.length > 0 && (
                <Card title={t('siteCitations.hosts')} intro={t('siteCitations.hostsNote')}>
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
                                    <td>{f.int(h.citations)}</td>
                                    <td>{f.int(h.citedPages)}</td>
                                    <td>{f.pct(h.share)}</td>
                                </tr>
                            ))}
                        </tbody>
                    </table>
                </Card>
            )}
        </>
    );
};

Pages.propTypes = {
    data: PropTypes.object.isRequired,
    language: PropTypes.string.isRequired,
    f: PropTypes.object.isRequired,
    t: PropTypes.func.isRequired
};

const Engines = ({data, f, t}) => {
    const visibility = data.site.visibility;
    const engines = data.site.engines || [];
    const markets = (visibility && visibility.byMarket) || [];
    const label = key => t(`citations.surface.${key}`, key);
    const bars = rows => <BarList rows={rows} max={100} format={v => f.pct(v / 100)}/>;

    return (
        <>
            {visibility && (
                <Columns>
                    <Card title={t('siteCitations.byEngine')} intro={t('siteCitations.visibilityNote')}>
                        {bars((visibility.byEngine || []).map(e => ({key: e.key, label: label(e.key), value: (e.score || 0) * 100})))}
                    </Card>
                    {/* One market is the project's score again: a card of its own would be one bar. */}
                    {markets.length > 1 && (
                        <Card title={t('siteCitations.byMarket')}>
                            {bars(markets.map(m => ({key: m.key, label: m.key.toUpperCase(), value: (m.score || 0) * 100})))}
                        </Card>
                    )}
                </Columns>
            )}
            {markets.length === 1 && <Note>{t('siteCitations.oneMarket', {market: markets[0].key.toUpperCase(), score: f.pct(markets[0].score)})}</Note>}
            {engines.length > 0 && (
                <Card title={t('siteCitations.engines')} intro={t('siteCitations.enginesIntro')}>
                    <ul className={own.engineList}>
                        {engines.map(e => (
                            <li key={e.aiSurface} className={own.engine}>
                                <span className={own.engineName}>{label(e.aiSurface)}</span>
                                <span className={e.refreshInterval ? own.engineEvery : own.engineManual}>{intervalLabel(e.refreshInterval, t)}</span>
                            </li>
                        ))}
                    </ul>
                </Card>
            )}
        </>
    );
};

Engines.propTypes = {data: PropTypes.object.isRequired, f: PropTypes.object.isRequired, t: PropTypes.func.isRequired};

/**
 * Screen B: how AI answers cite the whole site, from the last BotRank sync, in four views (overview,
 * ranking, pages, engines) that the dashboard shows as sub-tabs. Laid out with the dashboard's
 * shared layout (dashboard/Layout.jsx).
 *
 * Totals, share and rank cover the registrable domain, as BotRank counts them; the hosts table splits
 * citations per host from the cited pages list. Everything shown comes from BotRank and is rendered
 * as text.
 */
export const SiteCitationsPanel = ({path, language, view}) => {
    const {t, i18n} = useTranslation(NS);
    const [data, setData] = useState(null);
    const [failed, setFailed] = useState(false);
    const f = formats(i18n.language || language);

    useEffect(() => {
        let alive = true;
        setFailed(false);
        loadSite(path, language)
            .then(d => alive && setData(d))
            .catch(() => alive && setFailed(true));
        return () => {
            alive = false;
        };
    }, [path, language]);

    const header = <PanelHeader title={t(`siteCitations.view.${view}`)} intro={t(`siteCitations.viewIntro.${view}`)}/>;

    if (failed) {
        return <Panel>{header}<Banner variant="danger" title={t('citations.loadErrorTitle')}>{t('citations.loadError')}</Banner></Panel>;
    }

    if (!data) {
        return <Loader size="big"/>;
    }

    if (!data.configured) {
        return (
            <Panel>
                {header}
                <Banner variant="info" title={t('siteCitations.notConfiguredTitle')}>
                    {t(data.canConfigure ? 'siteCitations.notConfiguredAdmin' : 'siteCitations.notConfigured')}
                </Banner>
            </Panel>
        );
    }

    if (!data.syncedAt || !data.site) {
        return <Panel>{header}<Banner variant="info" title={t('citations.notSyncedTitle')}>{t('siteCitations.notSynced')}</Banner></Panel>;
    }

    const period = data.site.period;
    return (
        <Panel>
            <PanelHeader title={t(`siteCitations.view.${view}`)} intro={t(`siteCitations.viewIntro.${view}`)}>
                <Meta items={[
                    {key: 'asOf', text: data.latestAnswerAt ? t('siteCitations.metaAsOf', {date: f.date(data.latestAnswerAt)}) : t('citations.asOfUnknown')},
                    period && {key: 'period', text: t('siteCitations.metaPeriod', {start: f.day(period.start), end: f.day(period.end)})},
                    {key: 'synced', text: t('siteCitations.metaSynced', {date: f.date(data.syncedAt)})},
                    data.status === 'running' && {key: 'running', text: t('citations.running'), tone: 'warn'}
                ]}/>
            </PanelHeader>
            {data.site.failures > 0 && (
                <Banner variant="warning" title={t('siteCitations.partialTitle')}>{t('siteCitations.partial', {count: data.site.failures})}</Banner>
            )}

            {view === 'overview' && <Overview data={data} f={f} t={t}/>}
            {view === 'ranking' && <Ranking data={data} f={f} t={t}/>}
            {view === 'pages' && <Pages data={data} language={language} f={f} t={t}/>}
            {view === 'engines' && <Engines data={data} f={f} t={t}/>}

            <Source>{t('siteCitations.source')}</Source>
        </Panel>
    );
};

SiteCitationsPanel.propTypes = {
    path: PropTypes.string.isRequired,
    language: PropTypes.string.isRequired,
    view: PropTypes.oneOf(CITATION_VIEWS)
};

SiteCitationsPanel.defaultProps = {view: 'overview'};
