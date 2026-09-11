import React, {useCallback, useEffect, useState} from 'react';
import PropTypes from 'prop-types';
import {useTranslation} from 'react-i18next';
import {Banner, Chip, Dropdown, Loader, Separator, Typography} from '@jahia/moonstone';
import {scanStatus, checkFreshness} from '../api/siteScore';
import styles from './Tabs.module.css';

const NS = 'geo-readiness';
const BUCKETS = ['month', 'quarter', 'halfYear', 'year', 'older'];
const THRESHOLDS = [90, 180, 365, 730];

/**
 * GEO-24. How old the published content is, and where nothing has moved.
 *
 * Its own tab, and refreshable on its own: ageing what is published is a
 * repository question, so it answers immediately and never needs a site scan.
 * Changing the threshold recomputes rather than filtering a stored answer, and
 * the choice is remembered so the scheduled run measures against the same line.
 */
export const FreshnessPanel = ({path, language}) => {
    const {t} = useTranslation(NS);
    const [state, setState] = useState(null);
    const [busy, setBusy] = useState(false);

    const load = useCallback(async (staleDays) => {
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
    const widest = Math.max(1, ...Object.values(counts));

    const groups = (rows, key) => (
        <ul className={styles.checkList}>
            {(rows || []).map(g => (
                <li key={`${key}:${g.name}`} className={styles.checkItem}>
                    <span className={styles.checkText}>
                        <Typography variant="body" className={styles.checkLabel}>{g.name}</Typography>
                        <Typography variant="caption" className={styles.checkFix}>
                            {t('freshness.group', {
                                count: g.count,
                                newest: months(g.newest, t),
                                median: months(g.median, t)
                            })}
                        </Typography>
                    </span>
                    <span className={styles.checkMeta}>
                        {g.stale && <Chip label={t('freshness.stale')} color="warning"/>}
                    </span>
                </li>
            ))}
        </ul>
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
                    <Typography variant="caption" className={styles.panelIntro}>
                        {t('freshness.counts', {total: f.total, undated: f.undated})}
                    </Typography>

                    {/*
                      * A plain bar per bucket. The shape is the point - whether
                      * the site is mostly recent or mostly a year old - and a
                      * chart library for five numbers would be a dependency and
                      * a theme to maintain.
                      */}
                    <ul className={styles.checkList}>
                        {BUCKETS.map(b => (
                            <li key={b} className={styles.checkItem}>
                                <span className={styles.checkText}>
                                    <Typography variant="body" className={styles.checkLabel}>
                                        {t(`freshness.bucket.${b}`)}
                                    </Typography>
                                    <span
                                        className={styles.ageBar}
                                        style={{width: `${Math.round(((counts[b] || 0) / widest) * 100)}%`}}
                                    />
                                </span>
                                <span className={styles.checkMeta}>
                                    <Typography variant="caption" className={styles.checkValue}>
                                        {counts[b] || 0}
                                    </Typography>
                                </span>
                            </li>
                        ))}
                    </ul>

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

                    <p className={styles.explain}>{t('freshness.limits')}</p>
                </>
            )}
        </div>
    );
};

/** Days read as months once past a couple, which is how people discuss this. */
function months(days, t) {
    if (days < 60) {
        return t('freshness.days', {count: days});
    }

    return t('freshness.months', {count: Math.round(days / 30)});
}

FreshnessPanel.propTypes = {
    path: PropTypes.string.isRequired,
    language: PropTypes.string.isRequired
};
