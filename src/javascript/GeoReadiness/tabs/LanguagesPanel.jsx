import React, {useCallback, useEffect, useState} from 'react';
import PropTypes from 'prop-types';
import {useTranslation} from 'react-i18next';
import {Banner, Chip, Loader, Separator, Typography} from '@jahia/moonstone';
import {checkLanguages} from '../api/siteScore';
import {languageLabel} from '../util/languageFlag';
import styles from './Tabs.module.css';

const NS = 'geo-readiness';
/** A gap this wide between two markets is the finding, not a rounding difference. */
const NOTABLE_SPREAD = 10;

/**
 * "English 🇬🇧" rather than "en 🇬🇧". `languageLabel` takes a display name it
 * cannot invent, and the site's own names are not in this response, so ask the
 * browser - which knows them in the reader's own language.
 */
function nameOf(code, uiLanguage) {
    try {
        const names = new Intl.DisplayNames([uiLanguage || 'en'], {type: 'language'});
        return languageLabel(code, names.of(code) || code);
    } catch (e) {
        return languageLabel(code);
    }
}

/**
 * GEO-20. Readiness one language at a time.
 *
 * A site-wide average hides the market that is failing, which is the whole
 * reason this tab exists. The one thing it must never do is print 0% for a
 * language nobody has scanned: that reads as "this market is broken" when it
 * means "nobody has looked", and the two call for opposite responses.
 */
export const LanguagesPanel = ({path, language}) => {
    const {t} = useTranslation(NS);
    const [data, setData] = useState(null);
    const [failed, setFailed] = useState(false);

    const load = useCallback(async () => {
        try {
            setData(await checkLanguages({path, language}));
        } catch (e) {
            setFailed(true);
        }
    }, [path, language]);

    useEffect(() => {
        load();
    }, [load]);

    if (!data && !failed) {
        return <Loader size="big"/>;
    }

    const rows = (data && data.rows) || [];

    return (
        <div>
            <Typography variant="subheading" className={styles.panelTitle}>
                {t('languages.title')}
            </Typography>
            <Typography variant="caption" className={styles.panelIntro}>
                {t('languages.intro')}
            </Typography>

            {failed && (
                <Banner variant="danger" title={t('languages.errorTitle')}>
                    {t('languages.error')}
                </Banner>
            )}

            {data && (
                <>
                    <Separator spacing="big" size="full"/>
                    <Typography variant="caption" className={styles.panelIntro}>
                        {t('languages.counts', {total: data.total, count: rows.length})}
                    </Typography>

                    {/*
                      * Said before the table rather than left to be inferred from
                      * a blank cell. An unmeasured language is a scan that has
                      * not been scheduled, not a market in trouble.
                      */}
                    {data.unmeasured > 0 && (
                        <Banner variant="info" title={t('languages.unmeasuredTitle')}>
                            {t('languages.unmeasured', {count: data.unmeasured})}
                        </Banner>
                    )}

                    {data.spread >= NOTABLE_SPREAD && (
                        <Banner variant="warning" title={t('languages.spreadTitle')}>
                            {t('languages.spread', {points: data.spread})}
                        </Banner>
                    )}

                    <ul className={styles.checkList}>
                        {rows.map(r => (
                            <li key={r.language} className={styles.checkItem}>
                                <span className={styles.checkText}>
                                    <Typography variant="body" className={styles.checkLabel}>
                                        {nameOf(r.language, language)}
                                    </Typography>
                                    <Typography variant="caption" className={styles.checkFix}>
                                        {t('languages.coverage', {
                                            published: r.published,
                                            total: data.total,
                                            percent: r.coverage
                                        })}
                                        {r.missing > 0 ? ` · ${t('languages.missing', {count: r.missing})}` : ''}
                                    </Typography>
                                    <span
                                        className={styles.ageBar}
                                        style={{width: `${r.coverage}%`}}
                                    />
                                </span>
                                <span className={styles.checkMeta}>
                                    {r.scored ? (
                                        <>
                                            <Typography variant="caption" className={styles.checkValue}>
                                                {t('languages.pagesScored', {count: r.pagesScored})}
                                            </Typography>
                                            <Chip
                                                label={`${r.percent}%`}
                                                color={r.percent >= 80 ? 'success' : 'warning'}
                                            />
                                        </>
                                    ) : (
                                        // Never a zero, and never a chip that
                                        // could be mistaken for a low score.
                                        <Typography variant="caption" className={styles.checkValue}>
                                            {t('languages.notMeasured')}
                                        </Typography>
                                    )}
                                </span>
                            </li>
                        ))}
                    </ul>

                    <p className={styles.explain}>{t('languages.limits')}</p>
                </>
            )}
        </div>
    );
};

LanguagesPanel.propTypes = {
    path: PropTypes.string.isRequired,
    language: PropTypes.string.isRequired
};
