import React from 'react';
import PropTypes from 'prop-types';
import {useTranslation} from 'react-i18next';
import {Banner, Chip, Loader, Typography} from '@jahia/moonstone';
import {BarList, Histogram} from '../charts/Charts';
import styles from './Tabs.module.css';

const NS = 'geo-readiness';

// An unparsable value is shown as BotRank sent it; Date never throws, it is only invalid.
const formatDate = iso => {
    const d = new Date(iso);
    return Number.isNaN(d.getTime()) ? iso : d.toLocaleString();
};

const formatDay = day => {
    const d = new Date(`${day}T12:00:00Z`);
    return Number.isNaN(d.getTime()) ? day : d.toLocaleDateString();
};

/** A variation as a chip, or nothing when BotRank has no comparable previous period. */
const Variation = ({value, t}) => {
    if (value === null || value === undefined) {
        return <Chip label={t('citations.variationNone')} color="default"/>;
    }

    if (value === 0) {
        return <Chip label={t('citations.variationSame')} color="default"/>;
    }

    return (
        <Chip
            label={t(value > 0 ? 'citations.variationUp' : 'citations.variationDown', {count: Math.abs(value)})}
            color={value > 0 ? 'success' : 'warning'}
        />
    );
};

Variation.propTypes = {value: PropTypes.number, t: PropTypes.func.isRequired};
Variation.defaultProps = {value: null};

/**
 * The variation of one row (a question, a theme, a competing page), or nothing. Unlike the page's
 * own, a missing comparison is not repeated on every row: the page already says why.
 */
const RowVariation = ({value, t}) => (typeof value === 'number' ? <Variation value={value} t={t}/> : null);

RowVariation.propTypes = {value: PropTypes.number, t: PropTypes.func.isRequired};
RowVariation.defaultProps = {value: null};

// A position as BotRank sends it (two decimals), shown with one.
const formatRank = rank => Number(rank).toLocaleString(undefined, {maximumFractionDigits: 1});

/** "ChatGPT 3, Perplexity 1": the engines of one question, most citations first. */
const enginesOf = (surfaces, t) => (surfaces || [])
    .map(s => t('citations.engineCount', {engine: t(`citations.surface.${s.aiSurface}`, s.aiSurface), count: s.citationCount}))
    .join(', ');

/**
 * GEO-4: whether AI answers cite this page, from the last BotRank sync of the site.
 *
 * Everything shown comes from BotRank, a partner system: prompt texts, tag names and URLs are
 * rendered as text, never as markup.
 */
export const CitationsTab = ({data, failed}) => {
    const {t} = useTranslation(NS);

    if (failed) {
        return <Banner variant="danger" title={t('citations.loadErrorTitle')}>{t('citations.loadError')}</Banner>;
    }

    if (!data) {
        return <Loader size="big"/>;
    }

    const page = data.page;
    const asOf = (
        <Typography variant="caption" className={styles.explain}>
            {data.latestAnswerAt ? t('citations.asOf', {date: formatDate(data.latestAnswerAt)}) : t('citations.asOfUnknown')}
            {data.syncedAt ? ` ${t('citations.syncedAt', {date: formatDate(data.syncedAt)})}` : ''}
            {data.status === 'running' ? ` ${t('citations.running')}` : ''}
        </Typography>
    );

    if (!data.syncedAt) {
        return <Banner variant="info" title={t('citations.notSyncedTitle')}>{t('citations.notSynced')}</Banner>;
    }

    if (!data.published) {
        return (
            <div>
                {asOf}
                <Banner variant="info" title={t('citations.notPublishedTitle')}>{t('citations.notPublished')}</Banner>
            </div>
        );
    }

    if (!page) {
        return (
            <div>
                {asOf}
                <Banner variant="info" title={t('citations.neverTitle')}>{t('citations.never')}</Banner>
            </div>
        );
    }

    if (!page.recent) {
        return (
            <div>
                {asOf}
                <Banner variant="info" title={t('citations.notRecentTitle')}>
                    {t('citations.notRecent', {count: page.everCitations})}
                </Banner>
            </div>
        );
    }

    const surfaces = page.aiSurfaces || [];
    const history = page.history || [];
    const prompts = page.prompts || [];
    const tags = page.tags || [];
    const coCited = page.coCited || [];

    return (
        <div>
            {asOf}
            <div className={`${styles.verdict} ${styles.good}`}>
                <Typography variant="subheading" component="p">
                    {t('citations.verdict', {count: page.citations})}
                </Typography>
                <Variation value={page.variation} t={t}/>
            </div>
            <ul className={styles.facts}>
                {page.rank && <li>{t('citations.rank', {rank: page.rank, of: page.rankOf})}</li>}
                {page.everCitations > page.citations && <li>{t('citations.everCitations', {count: page.everCitations})}</li>}
                {(page.urls || []).length > 1 && <li>{t('citations.severalUrls', {count: page.urls.length})}</li>}
                {page.via === 'mapping' && <li>{t('citations.mapped')}</li>}
                {page.variation === null && page.previousRunDays === 0 && <li>{t('citations.variationWithheld')}</li>}
            </ul>
            {page.detailsMissing && (
                <Banner variant="warning" title={t('citations.detailsMissingTitle')}>{t('citations.detailsMissing')}</Banner>
            )}

            {surfaces.length > 0 && (
                <>
                    <Typography variant="heading" component="h3" className={styles.sectionTitle}>{t('citations.engines')}</Typography>
                    <BarList rows={surfaces.map(s => ({key: s.aiSurface, label: t(`citations.surface.${s.aiSurface}`, s.aiSurface), value: s.citationCount}))}/>
                </>
            )}

            {history.length > 0 && (
                <>
                    <Typography variant="heading" component="h3" className={styles.sectionTitle}>{t('citations.history')}</Typography>
                    <Histogram
                        rows={history.map(d => ({key: d.date, label: '', value: d.citationCount, day: d.date}))}
                        format={String}
                        tipFor={r => formatDay(r.day)}
                        axisLeft={formatDay(history[0].date)}
                        axisRight={formatDay(history[history.length - 1].date)}
                    />
                    <Typography variant="caption" className={styles.explain}>{t('citations.historyNote')}</Typography>
                </>
            )}

            {prompts.length > 0 && (
                <>
                    <Typography variant="heading" component="h3" className={styles.sectionTitle}>{t('citations.prompts')}</Typography>
                    <ul className={styles.checkList}>
                        {prompts.map(p => (
                            <li key={p.id} className={styles.checkItem}>
                                <div className={styles.checkText}>
                                    <span className={styles.checkLabel}>{p.text}</span>
                                    <span className={styles.checkMeta}>
                                        {t('citations.promptMeta', {
                                            count: p.citationCount,
                                            market: (p.market || '').toUpperCase(),
                                            day: formatDay(p.lastCitationDay)
                                        })}
                                    </span>
                                    {(p.aiSurfaces || []).length > 0 && (
                                        <span className={styles.checkMeta}>
                                            {t('citations.promptEngines', {engines: enginesOf(p.aiSurfaces, t)})}
                                        </span>
                                    )}
                                </div>
                                <RowVariation value={p.variation} t={t}/>
                            </li>
                        ))}
                    </ul>
                </>
            )}

            {tags.length > 0 && (
                <>
                    <Typography variant="heading" component="h3" className={styles.sectionTitle}>{t('citations.tags')}</Typography>
                    <Typography variant="caption" className={styles.explain}>{t('citations.tagsIntro')}</Typography>
                    <ul className={styles.facts}>
                        {tags.map(tag => (
                            <li key={tag.id}>
                                {t('citations.tagMeta', {name: tag.name, prompts: tag.promptCount, count: tag.citationCount})}
                                {' '}
                                <RowVariation value={tag.variation} t={t}/>
                            </li>
                        ))}
                    </ul>
                </>
            )}

            {coCited.length > 0 && (
                <>
                    <Typography variant="heading" component="h3" className={styles.sectionTitle}>{t('citations.coCited')}</Typography>
                    <Typography variant="caption" className={styles.explain}>{t('citations.coCitedIntro')}</Typography>
                    <ul className={styles.checkList}>
                        {coCited.map(c => (
                            <li key={c.url} className={styles.checkItem}>
                                <div className={styles.checkText}>
                                    <span className={styles.checkLabel}>{c.url}</span>
                                    <span className={styles.checkMeta}>
                                        {t('citations.coCitedMeta', {shared: c.sharedAnswerCount, ahead: c.outrankingAnswerCount})}
                                    </span>
                                    {typeof c.averageRank === 'number' && (
                                        <span className={styles.checkMeta}>
                                            {typeof c.requestedPageAverageRank === 'number'
                                                ? t('citations.coCitedRanks', {theirs: formatRank(c.averageRank), ours: formatRank(c.requestedPageAverageRank)})
                                                : t('citations.coCitedRankOnly', {theirs: formatRank(c.averageRank)})}
                                        </span>
                                    )}
                                    <span className={styles.checkMeta}>
                                        {t('citations.coCitedTotal', {count: c.citationCount})}
                                    </span>
                                </div>
                                <RowVariation value={c.variation} t={t}/>
                            </li>
                        ))}
                    </ul>
                </>
            )}

            <Typography variant="caption" className={styles.explain}>{t('citations.source')}</Typography>
        </div>
    );
};

CitationsTab.propTypes = {
    data: PropTypes.object,
    failed: PropTypes.bool
};

CitationsTab.defaultProps = {data: null, failed: false};
