import React, {useState} from 'react';
import PropTypes from 'prop-types';
import {useTranslation} from 'react-i18next';
import {Banner, Button, Chip, Loader, Typography} from '@jahia/moonstone';
import {BarList, Histogram} from '../charts/Charts';
import styles from './Tabs.module.css';
import cit from './CitationsTab.module.css';

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
const formatRank = (rank, lang) => Number(rank).toLocaleString(lang || undefined, {maximumFractionDigits: 1});

/** The theme's sub line: how many questions, and its own change when BotRank gave one. */
const tagSub = (tag, t) => {
    const parts = [t('citations.tagPrompts', {count: tag.promptCount})];
    if (typeof tag.variation === 'number') {
        parts.push(variationText(tag.variation, t));
    }

    return parts.join(' · ');
};

const variationText = (value, t) => {
    if (value === 0) {
        return t('citations.variationSame');
    }

    return t(value > 0 ? 'citations.variationUp' : 'citations.variationDown', {count: Math.abs(value)});
};

/** Host and path of a cited URL, shown apart: the host says whose page it is. Anything else stays whole. */
const URL_PARTS = /^[a-z][a-z0-9+.-]*:\/\/([^/?#]+)([^#]*)/i;
const splitUrl = url => {
    const m = URL_PARTS.exec(url || '');
    return m ? {host: m[1].replace(/^www\./i, ''), path: m[2]} : {host: url, path: ''};
};

/**
 * One question that cites the page: its text first, its weight in the page's citations as a bar,
 * then where and by which engines. The share is of this page's citations over the same 30 days.
 */
const PromptCard = ({prompt, pageTotal, t}) => {
    const share = pageTotal > 0 ? Math.min(1, prompt.citationCount / pageTotal) : 0;
    return (
        <li className={cit.card}>
            <div className={cit.cardHead}>
                <span className={cit.cardTitle}>{prompt.text}</span>
                <span className={cit.figure}>
                    <span className={cit.figureValue}>{prompt.citationCount}</span>
                    <span className={cit.figureUnit}>{t('citations.citationsUnit', {count: prompt.citationCount})}</span>
                </span>
            </div>
            <div className={cit.share} aria-hidden="true">
                <div className={cit.shareFill} style={{width: `${share * 100}%`}}/>
            </div>
            <div className={cit.meta}>
                {prompt.market && <span className={cit.badge}>{prompt.market.toUpperCase()}</span>}
                <span>{t('citations.shareOfPage', {percent: Math.round(share * 100)})}</span>
                {prompt.lastCitationDay && <span>{t('citations.lastCited', {day: formatDay(prompt.lastCitationDay)})}</span>}
                <RowVariation value={prompt.variation} t={t}/>
            </div>
            {(prompt.aiSurfaces || []).length > 0 && (
                <ul className={cit.engines} aria-label={t('citations.byEngine')}>
                    {prompt.aiSurfaces.map(s => (
                        <li key={s.aiSurface} className={cit.engine}>
                            {t(`citations.surface.${s.aiSurface}`, s.aiSurface)}
                            <span className={cit.engineCount}>{s.citationCount}</span>
                        </li>
                    ))}
                </ul>
            )}
        </li>
    );
};

PromptCard.propTypes = {prompt: PropTypes.object.isRequired, pageTotal: PropTypes.number, t: PropTypes.func.isRequired};
PromptCard.defaultProps = {pageTotal: 0};

/**
 * A page cited in the same answers: whose it is, in how many answers the two meet, in how many of
 * them it comes before this page, and which of the two sits higher among the sources on average
 * (1 is the first source). One bar, one meaning: the share of shared answers it wins.
 */
const CoCitedCard = ({page, t}) => {
    // The UI language, so a position reads 6,5 in French: the browser's own locale may differ.
    const {i18n} = useTranslation(NS);
    const lang = i18n && i18n.language;
    const {host, path} = splitUrl(page.url);
    const shared = page.sharedAnswerCount || 0;
    const ahead = Math.min(page.outrankingAnswerCount || 0, shared);
    // Status tone, always beside its own words: orange when it comes first in half the answers or more.
    const aheadTone = shared > 0 && ahead * 2 >= shared ? cit.toneBad : cit.toneGood;
    const theirs = page.averageRank;
    const ours = page.requestedPageAverageRank;
    const ranked = typeof theirs === 'number' && typeof ours === 'number';
    const oursBetter = ranked && ours <= theirs;
    return (
        <li className={cit.card}>
            <div className={cit.cardHead}>
                <span className={cit.cardTitle}>
                    <span className={cit.host}>{host}</span>
                    {path && path !== '/' && <span className={cit.path}>{path}</span>}
                </span>
                {shared > 0 && (
                    <span className={cit.figure}>
                        <span className={`${cit.figureValue} ${aheadTone}`}>
                            {ahead}<span className={cit.figureOf}>/{shared}</span>
                        </span>
                        <span className={cit.figureUnit}>{t('citations.aheadUnit')}</span>
                    </span>
                )}
            </div>
            {shared > 0 && (
                <>
                    <div className={cit.share} aria-hidden="true">
                        <div className={cit.aheadFill} style={{width: `${(ahead / shared) * 100}%`}}/>
                    </div>
                    <div className={cit.fact}>{t('citations.aheadOf', {ahead, count: shared})}</div>
                </>
            )}
            <div className={cit.meta}>
                {ranked && (
                    <span className={cit.positions}>
                        {t('citations.positionsLabel')}{' '}
                        <strong className={cit.rankValue}>{formatRank(theirs, lang)}</strong>
                        {' · '}{t('citations.positionsYours')}{' '}
                        <strong className={`${cit.rankValue} ${oursBetter ? cit.toneGood : cit.toneBad}`}>{formatRank(ours, lang)}</strong>
                    </span>
                )}
                {ranked && (
                    <Chip
                        label={oursBetter ? t('citations.youRankHigher') : t('citations.theyRankHigher')}
                        color={oursBetter ? 'success' : 'warning'}
                    />
                )}
                {!ranked && typeof theirs === 'number' && <span>{t('citations.coCitedRankOnly', {theirs: formatRank(theirs, lang)})}</span>}
            </div>
            <div className={cit.meta}>
                <span>{t('citations.coCitedTotal', {count: page.citationCount})}</span>
                <RowVariation value={page.variation} t={t}/>
            </div>
        </li>
    );
};

CoCitedCard.propTypes = {page: PropTypes.object.isRequired, t: PropTypes.func.isRequired};

/** The first few co-cited pages, the rest one click away: ten cards in a drawer is a wall. */
const CO_CITED_SHOWN = 5;
const CoCitedList = ({pages, t}) => {
    const [all, setAll] = useState(false);
    const shown = all ? pages : pages.slice(0, CO_CITED_SHOWN);
    return (
        <>
            <ul className={cit.cards}>
                {shown.map(c => <CoCitedCard key={c.url} page={c} t={t}/>)}
            </ul>
            {pages.length > CO_CITED_SHOWN && (
                <Button
                    className={cit.more}
                    variant="ghost"
                    label={all ? t('citations.showFewer') : t('citations.showAll', {count: pages.length - CO_CITED_SHOWN})}
                    onClick={() => setAll(!all)}
                />
            )}
        </>
    );
};

CoCitedList.propTypes = {pages: PropTypes.array.isRequired, t: PropTypes.func.isRequired};

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
                <section className={cit.section}>
                    <Typography variant="heading" component="h3" className={styles.sectionTitle}>{t('citations.prompts')}</Typography>
                    <ul className={cit.cards}>
                        {prompts.map(p => <PromptCard key={p.id} prompt={p} pageTotal={page.citations} t={t}/>)}
                    </ul>
                </section>
            )}

            {tags.length > 0 && (
                <section className={cit.section}>
                    <Typography variant="heading" component="h3" className={styles.sectionTitle}>{t('citations.tags')}</Typography>
                    <Typography variant="caption" className={styles.explain}>{t('citations.tagsIntro')}</Typography>
                    <BarList rows={tags.map(tag => ({
                        key: tag.id,
                        label: tag.name,
                        sublabel: tagSub(tag, t),
                        value: tag.citationCount
                    }))}
                    />
                </section>
            )}

            {coCited.length > 0 && (
                <section className={cit.section}>
                    <Typography variant="heading" component="h3" className={styles.sectionTitle}>{t('citations.coCited')}</Typography>
                    <Typography variant="caption" className={styles.explain}>{t('citations.coCitedIntro')}</Typography>
                    <CoCitedList pages={coCited} t={t}/>
                </section>
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
