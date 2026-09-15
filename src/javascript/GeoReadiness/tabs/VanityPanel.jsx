import React, {useCallback, useEffect, useState} from 'react';
import PropTypes from 'prop-types';
import {useTranslation} from 'react-i18next';
import {Banner, Chip, Loader, Separator, Typography} from '@jahia/moonstone';
import {scanStatus} from '../api/siteScore';
import {jcontentUrl} from '../util/jcontentUrl';
import {Paged} from '../util/Paged';
import styles from './Tabs.module.css';

const NS = 'geo-readiness';
const GROUPS = ['unresolvable', 'duplicates', 'canonical'];

/**
 * GEO-25. Content reachable at more than one address.
 *
 * Its own tab, like the sitemap and the link graph: none of this is a check a
 * page passes or fails, and none of it moves the percentage. Almost all of it
 * is a repository query - Jahia owns the vanity URL service - so the only part
 * that needs a scan is the canonical tag, which is a fact about rendered output.
 */
export const VanityPanel = ({path, language}) => {
    const {t} = useTranslation(NS);
    const [state, setState] = useState(null);
    const [error, setError] = useState(false);

    const load = useCallback(async () => {
        try {
            setState(await scanStatus({path, language}));
        } catch (e) {
            setError(true);
        }
    }, [path, language]);

    useEffect(() => {
        load();
    }, [load]);

    if (!state && !error) {
        return <Loader size="big"/>;
    }

    const vanity = state && state.vanity;

    return (
        <div>
            <Typography variant="subheading" component="h2" className={styles.panelTitle}>
                {t('vanity.title')}
            </Typography>
            <Typography variant="caption" className={styles.panelIntro}>
                {t('vanity.intro')}
            </Typography>

            {!vanity && (
                <Banner variant="info" title={t('vanity.neverTitle')}>
                    {t('vanity.never')}
                </Banner>
            )}

            {vanity && (
                <>
                    <Separator spacing="big" size="full"/>
                    <Typography variant="caption" className={styles.panelIntro}>
                        {t('vanity.counts', {
                            total: vanity.total,
                            languages: (vanity.languages || []).join(', ')
                        })}
                    </Typography>

                    {/*
                      * No vanity URLs at all is not the same as no problems, and
                      * a clean bill of health would be misleading for a site that
                      * simply does not use them.
                      */}
                    {vanity.total === 0 ? (
                        <Banner variant="info" title={t('vanity.noneTitle')}>
                            {t('vanity.none')}
                        </Banner>
                    ) : vanity.agrees ? (
                        <Banner variant="info" title={t('vanity.agreesTitle')}>
                            {t('vanity.agrees', {total: vanity.total})}
                        </Banner>
                    ) : (
                        GROUPS
                            .map(key => ({key, rows: vanity[key] || []}))
                            .filter(g => g.rows.length > 0)
                            .map(g => (
                                <div key={g.key} className={styles.sitemapGroup}>
                                    <div className={styles.sitemapGroupHead}>
                                        <Typography variant="body" className={styles.checkLabel}>
                                            {t(`vanity.kind.${g.key}`)}
                                        </Typography>
                                        <Chip
                                            label={String(g.rows.length)}
                                            color={g.key === 'unresolvable' ? 'danger' : 'warning'}
                                        />
                                    </div>
                                    <Typography variant="caption" className={styles.checkFix}>
                                        {t(`vanity.why.${g.key}`)}
                                    </Typography>
                                    <Paged rows={g.rows}>
                                        {slice => (
                                    <ul className={styles.checkList}>
                                        {slice.map(r => (
                                            <li key={`${g.key}:${r.url}`} className={styles.checkItem}>
                                                <span className={styles.checkText}>
                                                    {jcontentUrl(r.jcrPath, language) ? (
                                                        <a
                                                            className={styles.pageLink}
                                                            href={jcontentUrl(r.jcrPath, language)}
                                                            title={t('score17.openPage')}
                                                        >
                                                            {r.title || r.url}
                                                        </a>
                                                    ) : (
                                                        <Typography variant="body" className={styles.checkLabel}>
                                                            {r.title || r.url}
                                                        </Typography>
                                                    )}
                                                    <Typography variant="caption" className={styles.checkFix}>
                                                        {r.detail || r.url}
                                                    </Typography>
                                                </span>
                                                <span className={styles.checkMeta}>
                                                    <Typography variant="caption" className={styles.checkValue}>
                                                        {r.why ? t(`vanity.tag.${r.why}`) : r.language}
                                                    </Typography>
                                                </span>
                                            </li>
                                        ))}
                                    </ul>
                                        )}
                                    </Paged>
                                </div>
                            ))
                    )}
                    <p className={styles.explain}>{t('vanity.limits')}</p>
                </>
            )}
        </div>
    );
};

VanityPanel.propTypes = {
    path: PropTypes.string.isRequired,
    language: PropTypes.string.isRequired
};
