import React, {useCallback, useEffect, useState} from 'react';
import PropTypes from 'prop-types';
import {useTranslation} from 'react-i18next';
import {Banner, Chip, Loader, Separator, Typography} from '@jahia/moonstone';
import {scanStatus} from '../api/siteScore';
import {jcontentUrl} from '../util/jcontentUrl';
import {Paged} from '../util/Paged';
import styles from './Tabs.module.css';

const NS = 'geo-readiness';

/**
 * GEO-22. Published pages nothing links to.
 *
 * Its own tab for the same reason the sitemap has one: it is not a score.
 * Unlike the sitemap it cannot be refreshed on its own, because the graph is
 * built from the rendered HTML of every page - which is exactly what a site
 * scan already fetches. So this tab reports, and the Site score tab is where
 * the scan is started.
 */
export const LinksPanel = ({path, language}) => {
    const {t} = useTranslation(NS);
    const [state, setState] = useState(null);
    const [error, setError] = useState(false);

    const load = useCallback(async () => {
        try {
            setState(await scanStatus({path, language}));
        } catch (e) {
            // The user is told it failed; the reason belongs in the console.
            console.warn('geo-readiness: the link graph could not be read', e);
            setError(true);
        }
    }, [path, language]);

    useEffect(() => {
        load();
    }, [load]);

    if (!state && !error) {
        return <Loader size="big"/>;
    }

    const links = state && state.links;
    const orphans = (links && links.orphans) || [];
    const weak = (links && links.weak) || [];

    const rows = (group, key) => (
        <Paged rows={group}>
            {slice => (
        <ul className={styles.checkList}>
            {slice.map(r => (
                <li key={`${key}:${r.path}`} className={styles.checkItem}>
                    <span className={styles.checkText}>
                        {jcontentUrl(r.jcrPath, language) ? (
                            <a
                                className={styles.pageLink}
                                href={jcontentUrl(r.jcrPath, language)}
                                title={t('score17.openPage')}
                            >
                                {r.title || r.path}
                            </a>
                        ) : (
                            <Typography variant="body" className={styles.checkLabel}>
                                {r.title || r.path}
                            </Typography>
                        )}
                        <Typography variant="caption" className={styles.checkFix}>
                            {r.path}
                        </Typography>
                    </span>
                    {r.why && (
                        <span className={styles.checkMeta}>
                            <Typography variant="caption" className={styles.checkValue}>
                                {t(`links.why.${r.why}`)}
                            </Typography>
                        </span>
                    )}
                </li>
            ))}
        </ul>
            )}
        </Paged>
    );

    return (
        <div>
            <Typography variant="subheading" className={styles.panelTitle}>
                {t('links.title')}
            </Typography>
            <Typography variant="caption" className={styles.panelIntro}>
                {t('links.intro')}
            </Typography>

            {/*
              * Built by the scan, so "no scan yet" is a different statement from
              * "nothing to report" and must not be shown as a clean result.
              */}
            {!links && (
                <Banner variant="info" title={t('links.neverTitle')}>
                    {t('links.never')}
                </Banner>
            )}

            {links && (
                <>
                    <Separator spacing="big" size="full"/>
                    <Typography variant="caption" className={styles.panelIntro}>
                        {t('links.counts', {pages: links.pages, read: links.pagesRead})}
                    </Typography>

                    {orphans.length === 0 && weak.length === 0 && (
                        <Banner variant="info" title={t('links.allLinkedTitle')}>
                            {t('links.allLinked')}
                        </Banner>
                    )}

                    {orphans.length > 0 && (
                        <div className={styles.sitemapGroup}>
                            <div className={styles.sitemapGroupHead}>
                                <Typography variant="body" className={styles.checkLabel}>
                                    {t('links.kind.orphans')}
                                </Typography>
                                <Chip label={String(orphans.length)} color="danger"/>
                            </div>
                            <Typography variant="caption" className={styles.checkFix}>
                                {t('links.why.orphans')}
                            </Typography>
                            {rows(orphans, 'orphan')}
                        </div>
                    )}

                    {weak.length > 0 && (
                        <div className={styles.sitemapGroup}>
                            <div className={styles.sitemapGroupHead}>
                                <Typography variant="body" className={styles.checkLabel}>
                                    {t('links.kind.weak')}
                                </Typography>
                                <Chip label={String(weak.length)} color="warning"/>
                            </div>
                            <Typography variant="caption" className={styles.checkFix}>
                                {t('links.why.weak')}
                            </Typography>
                            {rows(weak, 'weak')}
                        </div>
                    )}

                    <p className={styles.explain}>{t('links.limits')}</p>
                </>
            )}
        </div>
    );
};

LinksPanel.propTypes = {
    path: PropTypes.string.isRequired,
    language: PropTypes.string.isRequired
};
