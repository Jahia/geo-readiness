import React, {useCallback, useEffect, useState} from 'react';
import PropTypes from 'prop-types';
import {useTranslation} from 'react-i18next';
import {Banner, Dropdown, Loader, Separator, Typography} from '@jahia/moonstone';
import {scanStatus, checkFreshness} from '../api/siteScore';
import {BarList, RangeList} from '../charts/Charts';
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

    // Shared x-axis across both breakdowns, so a bar's length means the same
    // thing whether the group is a type or a section.
    const oldest = f ? Math.max(1, ...[...(f.byType || []), ...(f.bySection || [])].map(g => g.oldest)) : 1;

    const groups = (rows, key) => (
        <RangeList
            max={oldest}
            format={d => months(d, t)}
            tipFor={r => t('freshness.rangeTip', {
                newest: months(r.from, t),
                median: months(r.marker, t),
                oldest: months(r.to, t)
            })}
            rows={(rows || []).map(g => ({
                key: `${key}:${g.name}`,
                label: g.name,
                sublabel: t('freshness.items', {count: g.count}),
                flag: g.stale ? t('freshness.stale') : null,
                from: g.newest,
                to: g.oldest,
                marker: g.median,
                stale: g.stale
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
                    <Typography variant="caption" className={styles.panelIntro}>
                        {t('freshness.counts', {total: f.total, undated: f.undated})}
                    </Typography>

                    {/*
                      * A histogram, one hue. The shape is the point: whether the
                      * site is mostly recent or mostly a year old.
                      */}
                    <BarList
                        format={v => String(v)}
                        tipFor={r => t('freshness.items', {count: r.value})}
                        rows={BUCKETS.map(b => ({
                            key: b,
                            label: t(`freshness.bucket.${b}`),
                            value: counts[b] || 0
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
