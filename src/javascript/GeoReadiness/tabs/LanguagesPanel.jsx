import React, {useCallback, useEffect, useState} from 'react';
import PropTypes from 'prop-types';
import {useTranslation} from 'react-i18next';
import {Banner, Loader} from '@jahia/moonstone';
import {checkLanguages} from '../api/siteScore';
import {languageLabel} from '../util/languageFlag';
import {PairedBars} from '../charts/Charts';
import {Card, Figure, Figures, Note, Panel, PanelHeader} from '../dashboard/Layout';

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
        <Panel>
            <PanelHeader title={t('languages.title')} intro={t('languages.intro')}/>

            {failed && (
                <Banner variant="danger" title={t('languages.errorTitle')}>
                    {t('languages.error')}
                </Banner>
            )}

            {data && (
                <>
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

                    <Card intro={t('languages.counts', {total: data.total, count: rows.length})}>
                        <Figures>
                            <Figure label={t('languages.figure.published')} value={data.total} tone="accent"/>
                            <Figure label={t('languages.figure.declared')} value={rows.length}/>
                            <Figure
                                label={t('languages.figure.unmeasured')}
                                value={data.unmeasured || 0}
                                tone={data.unmeasured > 0 ? 'warn' : 'good'}
                            />
                            {typeof data.spread === 'number' && (
                                <Figure
                                    label={t('languages.figure.spread')}
                                    value={t('languages.figure.points', {points: data.spread})}
                                    tone={data.spread >= NOTABLE_SPREAD ? 'warn' : 'good'}
                                />
                            )}
                        </Figures>
                        {/*
                          * Coverage and score side by side on one 0-100 axis - the
                          * comparison the story is about. An unmeasured language
                          * renders as words, never as an empty bar.
                          */}
                        <PairedBars
                            missingLabel={t('languages.notMeasured')}
                            series={[
                                {key: 'coverage', label: t('languages.series.coverage'), tone: 'series1'},
                                {key: 'score', label: t('languages.series.score'), tone: 'series2'}
                            ]}
                            rows={rows.map(r => ({
                                key: r.language,
                                label: nameOf(r.language, language),
                                tipLabel: nameOf(r.language, language),
                                sublabel: [
                                    t('languages.coverage', {published: r.published, total: data.total, percent: r.coverage}),
                                    r.missing > 0 ? t('languages.missing', {count: r.missing}) : null,
                                    r.scored ? t('languages.pagesScored', {count: r.pagesScored}) : null
                                ].filter(Boolean).join(' · '),
                                values: {coverage: r.coverage, score: r.scored ? r.percent : undefined}
                            }))}
                        />

                        <Note>{t('languages.limits')}</Note>
                    </Card>
                </>
            )}
        </Panel>
    );
};

LanguagesPanel.propTypes = {
    path: PropTypes.string.isRequired,
    language: PropTypes.string.isRequired
};
