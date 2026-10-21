import React, {useCallback, useEffect, useState} from 'react';
import PropTypes from 'prop-types';
import {useTranslation} from 'react-i18next';
import {Banner, Chip, Loader, Typography} from '@jahia/moonstone';
import {scanStatus} from '../api/siteScore';
import {jcontentUrl} from '../util/jcontentUrl';
import {Paged} from '../util/Paged';
import {Card, Figure, Figures, Note, Panel, PanelHeader} from '../dashboard/Layout';
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
/** A group's figure: green when empty, red for addresses in an unserved language, amber otherwise. */
const groupTone = g => {
    if (g.rows.length === 0) {
        return 'good';
    }

    return g.key === 'unresolvable' ? 'bad' : 'warn';
};

/** No vanity URL, vanity URLs that all agree, or one figure per group of findings. */
const outcome = (vanity, groups, t) => {
    if (vanity.total === 0) {
        return (
            <Banner variant="info" title={t('vanity.noneTitle')}>
                {t('vanity.none')}
            </Banner>
        );
    }

    if (vanity.agrees) {
        return (
            <Banner variant="info" title={t('vanity.agreesTitle')}>
                {t('vanity.agrees', {total: vanity.total})}
            </Banner>
        );
    }

    return (
        <Figures>
            {groups.map(g => (
                <Figure key={g.key} label={t(`vanity.figure.${g.key}`)} value={g.rows.length} tone={groupTone(g)}/>
            ))}
        </Figures>
    );
};

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
    // Problem groups are listed only when the site uses vanity URLs and they do not all agree.
    const groups = vanity && vanity.total !== 0 && !vanity.agrees ?
        GROUPS.map(key => ({key, rows: vanity[key] || []})) :
        [];

    return (
        <Panel>
            <PanelHeader title={t('vanity.title')} intro={t('vanity.intro')}/>

            {!vanity && (
                <Banner variant="info" title={t('vanity.neverTitle')}>
                    {t('vanity.never')}
                </Banner>
            )}

            {vanity && (
                <Card>
                    {/*
                      * No vanity URLs at all is not the same as no problems, and
                      * a clean bill of health would be misleading for a site that
                      * simply does not use them.
                      */}
                    {outcome(vanity, groups, t)}
                    <Note>
                        {t('vanity.counts', {
                            total: vanity.total,
                            languages: (vanity.languages || []).join(', ')
                        })}
                    </Note>
                </Card>
            )}

            {groups
                .filter(g => g.rows.length > 0)
                .map(g => (
                    <Card
                        key={g.key}
                        title={t(`vanity.kind.${g.key}`)}
                        intro={t(`vanity.why.${g.key}`)}
                        aside={(
                            <Chip
                                label={String(g.rows.length)}
                                color={g.key === 'unresolvable' ? 'danger' : 'warning'}
                            />
                        )}
                    >
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
                    </Card>
                ))}

            {vanity && <Note>{t('vanity.limits')}</Note>}
        </Panel>
    );
};

VanityPanel.propTypes = {
    path: PropTypes.string.isRequired,
    language: PropTypes.string.isRequired
};
