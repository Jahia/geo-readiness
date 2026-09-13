import React, {useCallback, useEffect, useState} from 'react';
import PropTypes from 'prop-types';
import {useTranslation} from 'react-i18next';
import {Banner, Button, Chip, Loader, Separator, Typography} from '@jahia/moonstone';
import {scanStatus, checkSitemap} from '../api/siteScore';
import {jcontentUrl} from '../util/jcontentUrl';
import {Paged} from '../util/Paged';
import styles from './Tabs.module.css';

const NS = 'geo-readiness';
// Enough rows to act on, few enough that one bad group cannot bury the rest.
const GROUPS = ['redirects', 'missing', 'unknown', 'staleDate', 'noindexListed'];


/**
 * The sitemap against what the site actually publishes.
 *
 * Its own tab rather than a section of the score, because it is not a score:
 * nothing here is a check a page passes or fails, and none of it changes the
 * percentage. It also refreshes on its own - the comparison resolves entries
 * against the repository instead of fetching them, so it costs no requests and
 * has no reason to wait for a walk of every page.
 */
export const SitemapPanel = ({path, language}) => {
    const {t} = useTranslation(NS);
    const [state, setState] = useState(null);
    const [busy, setBusy] = useState(false);
    const [error, setError] = useState(null);

    const load = useCallback(async () => {
        try {
            setState(await scanStatus({path, language}));
        } catch (e) {
            setError(e.code === 429 ? 'rate' : 'failed');
        }
    }, [path, language]);

    useEffect(() => {
        load();
    }, [load]);

    const check = useCallback(async () => {
        setBusy(true);
        setError(null);
        try {
            setState(await checkSitemap({path, language}));
        } catch (e) {
            setError(e.code === 429 ? 'rate' : 'failed');
        } finally {
            setBusy(false);
        }
    }, [path, language]);

    if (!state) {
        return <Loader size="big"/>;
    }

    const sitemap = state.sitemap;
    const checkedAt = state.sitemapCheckedAt;

    return (
        <div>
            <Typography variant="subheading" className={styles.panelTitle}>
                {t('sitemap.title')}
            </Typography>
            <Typography variant="caption" className={styles.panelIntro}>
                {t('sitemap.intro')}
            </Typography>

            <div className={styles.actions}>
                <Button
                    size="big"
                    color="accent"
                    isDisabled={busy}
                    label={busy ? t('sitemap.checking') : t('sitemap.checkNow')}
                    onClick={check}
                />
                {checkedAt && (
                    <Typography variant="caption">
                        {t('sitemap.lastCheck', {date: new Date(checkedAt).toLocaleString()})}
                    </Typography>
                )}
            </div>

            {error && (
                <Banner variant="danger" title={t(`sitemap.error.${error}Title`)}>
                    {t(`sitemap.error.${error}`)}
                </Banner>
            )}

            {/*
              * Never checked is not the same as nothing to report, and showing
              * an empty list for it would read as a clean bill of health.
              */}
            {!sitemap && !error && (
                <Banner variant="info" title={t('sitemap.neverTitle')}>
                    {t('sitemap.never')}
                </Banner>
            )}

            {sitemap && (
                <>
                    <Separator spacing="big" size="full"/>
                    {sitemap.present ? (
                        <>
                            <Typography variant="caption" className={styles.panelIntro}>
                                {t('sitemap.counts', {
                                    entries: sitemap.entries,
                                    published: sitemap.published
                                })}
                            </Typography>
                            {sitemap.agrees ? (
                                <Banner variant="info" title={t('sitemap.agreesTitle')}>
                                    {t('sitemap.agrees', {entries: sitemap.entries})}
                                </Banner>
                            ) : (
                                GROUPS
                                    .map(key => ({key, rows: sitemap[key] || []}))
                                    .filter(g => g.rows.length > 0)
                                    .map(g => (
                                        <div key={g.key} className={styles.sitemapGroup}>
                                            <div className={styles.sitemapGroupHead}>
                                                <Typography variant="body" className={styles.checkLabel}>
                                                    {t(`sitemap.kind.${g.key}`)}
                                                </Typography>
                                                <Chip label={String(g.rows.length)} color="warning"/>
                                            </div>
                                            <Typography variant="caption" className={styles.checkFix}>
                                                {t(`sitemap.why.${g.key}`)}
                                            </Typography>
                                            <Paged rows={g.rows}>
                                                {slice => (
                                            <ul className={styles.checkList}>
                                                {slice.map(r => (
                                                    <li key={`${g.key}:${r.path}`} className={styles.checkItem}>
                                                        <span className={styles.checkText}>
                                                            {/*
                                                              * An unknown entry resolves to nothing,
                                                              * so there is no node to open: its URL
                                                              * is the only fact there is.
                                                              */}
                                                            {r.jcrPath && jcontentUrl(r.jcrPath, language) ? (
                                                                <a
                                                                    className={styles.pageLink}
                                                                    href={jcontentUrl(r.jcrPath, language)}
                                                                    title={t('score17.openPage')}
                                                                >
                                                                    {r.title || r.path}
                                                                </a>
                                                            ) : (
                                                                <Typography
                                                                    variant="body"
                                                                    className={styles.checkLabel}
                                                                >
                                                                    {r.title || r.path}
                                                                </Typography>
                                                            )}
                                                            <Typography
                                                                variant="caption"
                                                                className={styles.checkFix}
                                                            >
                                                                {r.path}
                                                            </Typography>
                                                        </span>
                                                        {r.detail && (
                                                            <span className={styles.checkMeta}>
                                                                <Typography
                                                                    variant="caption"
                                                                    className={styles.checkValue}
                                                                >
                                                                    {r.detail}
                                                                </Typography>
                                                            </span>
                                                        )}
                                                    </li>
                                                ))}
                                            </ul>
                                                )}
                                            </Paged>
                                        </div>
                                    ))
                            )}
                        </>
                    ) : (
                        /*
                         * Three different situations, three different things to
                         * do about them: nothing serves that address, something
                         * does but it is not a sitemap, or it could not be
                         * reached to find out. One shared "no sitemap" message
                         * would send people to fix the wrong thing.
                         */
                        <Banner
                            variant={sitemap.reason === 'unreachable' ? 'danger' : 'warning'}
                            title={t(`sitemap.absent.${sitemap.reason || 'none'}Title`)}
                        >
                            {t(`sitemap.absent.${sitemap.reason || 'none'}`, {
                                url: sitemap.url,
                                status: sitemap.status === null ? '-' : sitemap.status
                            })}
                        </Banner>
                    )}
                    {/* How the comparison works, so only worth saying when one happened. */}
                    {sitemap.present && <p className={styles.explain}>{t('sitemap.limits')}</p>}
                </>
            )}
        </div>
    );
};

SitemapPanel.propTypes = {
    path: PropTypes.string.isRequired,
    language: PropTypes.string.isRequired
};
