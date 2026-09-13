import React, {useCallback, useEffect, useState} from 'react';
import PropTypes from 'prop-types';
import {useTranslation} from 'react-i18next';
import {Banner, Dropdown, Loader, Separator, Typography} from '@jahia/moonstone';
import {scanStatus, checkFreshness} from '../api/siteScore';
import {BarList, Histogram} from '../charts/Charts';
import {jcontentUrl} from '../util/jcontentUrl';
import {Paged} from '../util/Paged';
import styles from './Tabs.module.css';

const NS = 'geo-readiness';
const THRESHOLDS = [90, 180, 365, 730];

/**
 * The age buckets in time order, oldest first, so the histogram reads left to
 * right the way a timeline does. `from` is the youngest age the bucket can
 * hold, which is what decides whether the whole bucket is past the threshold.
 */
const BUCKETS = [
    {key: 'older', from: 365},
    {key: 'year', from: 180},
    {key: 'halfYear', from: 90},
    {key: 'quarter', from: 30},
    {key: 'month', from: 0}
];

/**
 * GEO-24. How old the published content is, and where nothing has moved.
 *
 * Two questions, one mark each. The histogram answers "when was any of this
 * last touched", over a time axis. The group bars answer "how long since
 * anything here changed at all", which is the number the threshold is applied
 * to, so a flagged group and a long bar say the same thing.
 *
 * An earlier version drew a range per group, from newest to oldest with the
 * median marked. It encoded spread, which is not a question anybody asked, and
 * on most groups newest and oldest are the same date so the range collapsed to
 * a dot. Length now means one thing: how long the group has been sitting still.
 */
export const FreshnessPanel = ({path, language}) => {
    const {t} = useTranslation(NS);
    const [state, setState] = useState(null);
    const [busy, setBusy] = useState(false);

    const load = useCallback(async staleDays => {
        setBusy(true);
        try {
            setState(staleDays ?
                await checkFreshness({path, language, staleDays}) :
                await scanStatus({path, language}));
        } catch (e) {
            setState(s => s || {});
        } finally {
            setBusy(false);
        }
    }, [path, language]);

    useEffect(() => {
        load(null);
    }, [load]);

    if (!state) {
        return <Loader size="big"/>;
    }

    const f = state.freshness;
    const threshold = (f && f.staleDays) || state.staleDays || 365;
    const counts = f ? Object.fromEntries((f.distribution || []).map(b => [b.label, b.count])) : {};

    // One scale across both breakdowns, so a bar's length means the same thing
    // whether the group is a type or a section.
    const longest = f ?
        Math.max(1, ...[...(f.byType || []), ...(f.bySection || [])].map(g => g.newest)) :
        1;

    // Worst first: the group nobody has touched in longest is the one to act on.
    const groups = (rows, key) => (
        <BarList
            max={longest}
            format={d => age(d, t)}
            tipFor={r => t('freshness.untouched', {age: age(r.value, t)})}
            rows={[...(rows || [])]
                .sort((a, b) => b.newest - a.newest)
                .map(g => ({
                    key: `${key}:${g.name}`,
                    label: g.name,
                    sublabel: t('freshness.items', {count: g.count}),
                    value: g.newest,
                    status: g.stale ? 'warn' : undefined
                }))}
        />
    );

    return (
        <div>
            <Typography variant="subheading" className={styles.panelTitle}>
                {t('freshness.title')}
            </Typography>
            <Typography variant="caption" className={styles.panelIntro}>
                {t('freshness.intro')}
            </Typography>

            <div className={styles.cronRow}>
                <Typography variant="caption">{t('freshness.thresholdLabel')}</Typography>
                <Dropdown
                    size="small"
                    isDisabled={busy}
                    value={String(threshold)}
                    data={THRESHOLDS.map(d => ({
                        label: t(`freshness.threshold.${d}`),
                        value: String(d)
                    }))}
                    onChange={(e, item) => {
                        const v = (item && item.value) || '';
                        if (v) {
                            load(Number(v));
                        }
                    }}
                />
                {busy && <Typography variant="caption">{t('freshness.measuring')}</Typography>}
            </div>

            {!f && (
                <Banner variant="info" title={t('freshness.neverTitle')}>
                    {t('freshness.never')}
                </Banner>
            )}

            {f && (
                <>
                    <Separator spacing="big" size="full"/>
                    <Typography variant="subheading" className={styles.panelSub}>
                        {t('freshness.whenTitle')}
                    </Typography>
                    <Typography variant="caption" className={styles.panelIntro}>
                        {t('freshness.counts', {total: f.total, undated: f.undated})}
                    </Typography>

                    {/*
                      * The shape is the answer: whether the site's mass sits on
                      * the left, where nothing has been touched in a long time.
                      * A bucket entirely past the threshold carries the warning
                      * tone, so the chosen line shows up in the picture.
                      */}
                    <Histogram
                        axisLeft={t('freshness.axisOlder')}
                        axisRight={t('freshness.axisRecent')}
                        format={v => String(v)}
                        tipFor={r => t(`freshness.bucket.${r.key}`)}
                        rows={BUCKETS.map(b => ({
                            key: b.key,
                            label: t(`freshness.bucketShort.${b.key}`),
                            value: counts[b.key] || 0,
                            status: b.from >= threshold ? 'warn' : undefined
                        }))}
                    />

                    <Separator spacing="big" size="full"/>
                    <Typography variant="subheading" className={styles.panelSub}>
                        {t('freshness.byType')}
                    </Typography>
                    <Typography variant="caption" className={styles.panelIntro}>
                        {t('freshness.byTypeHelp')}
                    </Typography>
                    {groups(f.byType, 'type')}

                    <Separator spacing="big" size="full"/>
                    <Typography variant="subheading" className={styles.panelSub}>
                        {t('freshness.bySection')}
                    </Typography>
                    <Typography variant="caption" className={styles.panelIntro}>
                        {t('freshness.bySectionHelp')}
                    </Typography>
                    {groups(f.bySection, 'section')}

                    <Separator spacing="big" size="full"/>
                    <Typography variant="subheading" className={styles.panelSub}>
                        {t('freshness.allTitle')}
                    </Typography>
                    <Typography variant="caption" className={styles.panelIntro}>
                        {t('freshness.allHelp')}
                    </Typography>

                    {/*
                      * The groups above say where to look; this says which page.
                      * Oldest first, so the list opens on the work rather than on
                      * whatever the repository happened to return first.
                      */}
                    <Paged rows={f.items || []}>
                        {slice => (
                            <ul className={styles.checkList}>
                                {slice.map(r => (
                                    <li key={r.jcrPath} className={styles.checkItem}>
                                        <span className={styles.checkText}>
                                            {jcontentUrl(r.jcrPath, language) ? (
                                                <a
                                                    className={styles.pageLink}
                                                    href={jcontentUrl(r.jcrPath, language)}
                                                    title={t('score17.openPage')}
                                                >
                                                    {r.title}
                                                </a>
                                            ) : (
                                                <Typography variant="body" className={styles.checkLabel}>
                                                    {r.title}
                                                </Typography>
                                            )}
                                            <Typography variant="caption" className={styles.checkFix}>
                                                {`${r.type} · ${r.section} · ${r.path}`}
                                            </Typography>
                                        </span>
                                        <span className={styles.checkMeta}>
                                            <Typography
                                                variant="caption"
                                                className={r.stale ? styles.checkStale : styles.checkValue}
                                            >
                                                {r.days === null ?
                                                    t('freshness.noDate') :
                                                    age(r.days, t)}
                                            </Typography>
                                            <Typography variant="caption" className={styles.checkFix}>
                                                {r.modifiedOn || ''}
                                            </Typography>
                                        </span>
                                    </li>
                                ))}
                            </ul>
                        )}
                    </Paged>

                    {f.itemsTruncated && (
                        <Typography variant="caption" className={styles.panelIntro}>
                            {t('freshness.truncated', {count: (f.items || []).length})}
                        </Typography>
                    )}

                    <p className={styles.explain}>{t('freshness.limits')}</p>
                </>
            )}
        </div>
    );
};

/** Days read as months once past a couple, which is how people discuss this. */
function age(days, t) {
    if (days < 60) {
        return t('freshness.days', {count: days});
    }

    return t('freshness.months', {count: Math.round(days / 30)});
}

FreshnessPanel.propTypes = {
    path: PropTypes.string.isRequired,
    language: PropTypes.string.isRequired
};
