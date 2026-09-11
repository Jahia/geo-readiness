import React, {useCallback, useEffect, useState} from 'react';
import PropTypes from 'prop-types';
import {useTranslation} from 'react-i18next';
import {Banner, Button, Separator, Textarea, Typography} from '@jahia/moonstone';
import {previewLlms, applyLlms} from '../api/siteFiles';
import {scanStatus} from '../api/siteScore';
import {DiffView} from './DiffView';
import styles from './Tabs.module.css';

const NS = 'geo-readiness';

/**
 * Generate, review, then write. The apply button is deliberately two-step and
 * sends back the exact text shown on screen, so an editor can hand-edit the
 * generated file and keep their edits. Nothing here writes on its own.
 */
export const LlmsGeneratorPanel = ({path, language}) => {
    const {t} = useTranslation(NS);
    const [preview, setPreview] = useState(null);
    const [text, setText] = useState('');
    const [phase, setPhase] = useState('idle');
    const [confirming, setConfirming] = useState(false);
    const [error, setError] = useState(null);
    // Whether the file being served still matches what generating would produce.
    // Read from the last scan, which compares the two for free.
    const [freshness, setFreshness] = useState(null);

    useEffect(() => {
        let live = true;
        scanStatus({path, language})
            .then(s => live && setFreshness(s.llms))
            .catch(() => {});
        return () => {
            live = false;
        };
    }, [path, language]);

    const generate = useCallback(async () => {
        setPhase('running');
        setError(null);
        setConfirming(false);
        try {
            const p = await previewLlms({path, language});
            setPreview(p);
            setText(p.generated || '');
            setPhase('ready');
        } catch (e) {
            setError(t('files.llms.generate.error'));
            setPhase('idle');
        }
    }, [path, language, t]);

    const apply = useCallback(async () => {
        if (!confirming) {
            setConfirming(true);
            return;
        }

        setPhase('applying');
        try {
            await applyLlms({path, language, content: text});
            setPhase('applied');
            setConfirming(false);
        } catch (e) {
            setError(t('files.llms.generate.error'));
            setPhase('ready');
            setConfirming(false);
        }
    }, [confirming, path, language, text, t]);

    const stats = preview ? countOf(preview.generated) : null;
    const dirty = preview && text !== (preview.current || '');

    return (
        <div className={styles.panel}>
            <Typography variant="heading" className={styles.panelTitle}>
                {t('files.llms.generate.title')}
            </Typography>
            <Typography variant="body" className={styles.panelIntro}>
                {t('files.llms.generate.intro')}
            </Typography>

            {/*
              * A stale llms.txt fails silently: nothing errors, an assistant is
              * just handed a map of a site that has moved on. This is the only
              * place that says so, and the button to fix it is right below.
              */}
            {freshness && freshness.outdated && (
                <Banner variant="warning" title={t('files.llms.stale.title')}>
                    {t('files.llms.stale.summary', {
                        stale: (freshness.stale || []).length,
                        missing: (freshness.missing || []).length
                    })}
                    <ul className={styles.checkList}>
                        {(freshness.stale || []).slice(0, 5).map(r => (
                            <li key={`s:${r.path}`} className={styles.checkItem}>
                                <span className={styles.checkText}>
                                    <Typography variant="caption" className={styles.checkFix}>
                                        {r.path} · {t(`files.llms.stale.why.${r.why}`)}
                                    </Typography>
                                </span>
                            </li>
                        ))}
                        {(freshness.missing || []).slice(0, 5).map(r => (
                            <li key={`m:${r.path}`} className={styles.checkItem}>
                                <span className={styles.checkText}>
                                    <Typography variant="caption" className={styles.checkFix}>
                                        {r.path} · {t('files.llms.stale.why.notListed')}
                                    </Typography>
                                </span>
                            </li>
                        ))}
                    </ul>
                </Banner>
            )}

            {freshness && freshness.present && !freshness.outdated && (
                <Banner variant="info" title={t('files.llms.fresh.title')}>
                    {t('files.llms.fresh.summary', {count: freshness.listed})}
                </Banner>
            )}

            <div className={styles.actions}>
                <Button
                    size="big"
                    variant="outlined"
                    isDisabled={phase === 'running' || phase === 'applying'}
                    label={phase === 'running' ?
                        t('files.llms.generate.running') :
                        t('files.llms.generate.button')}
                    onClick={generate}
                />
                {preview && stats && (
                    <Typography variant="caption">{t('files.llms.generate.stats', stats)}</Typography>
                )}
            </div>

            {error && <Banner variant="danger" title={t('files.llms.generate.errorTitle')}>{error}</Banner>}

            {preview && (
                <>
                    {!preview.mixinPresent && (
                        <Banner variant="info" title={t('files.llms.generate.noMixinTitle')}>
                            {t('files.llms.generate.noMixin')}
                        </Banner>
                    )}
                    {preview.mixinPresent && preview.currentIsPlaceholder && (
                        <Banner variant="warning" title={t('files.llms.generate.placeholderTitle')}>
                            {t('files.llms.generate.placeholder')}
                        </Banner>
                    )}
                    {phase === 'applied' && (
                        <Banner variant="info" title={t('files.llms.generate.appliedTitle')}>
                            {t('files.llms.generate.applied')}
                        </Banner>
                    )}

                    {phase !== 'applied' && (
                        <>
                            <Typography variant="subheading" className={styles.panelSub}>
                                {t('files.llms.generate.generated')}
                            </Typography>
                            <Textarea
                                isResizable
                                id="geoReadinessLlmsText"
                                className={styles.genText}
                                value={text}
                                spellCheck="false"
                                onChange={e => {
                                    setText(e.target.value);
                                    setConfirming(false);
                                }}
                            />

                            <Separator spacing="big" size="full"/>
                            <Typography variant="subheading" className={styles.panelSub}>
                                {t('files.llms.generate.changes')}
                            </Typography>
                            <DiffView before={preview.current || ''} after={text}/>

                            <div className={styles.actions}>
                                <Button
                                    size="big"
                                    color={confirming ? 'danger' : 'accent'}
                                    isDisabled={!preview.canWrite || !dirty || phase === 'applying'}
                                    label={confirming ?
                                        t('files.llms.generate.confirm') :
                                        t('files.llms.generate.apply')}
                                    onClick={apply}
                                />
                                {!preview.canWrite && (
                                    <Typography variant="caption">
                                        {t('files.llms.generate.cannotWrite')}
                                    </Typography>
                                )}
                            </div>
                        </>
                    )}

                    <Typography variant="caption" className={styles.panelRule}>
                        {t('files.llms.generate.rule')}
                    </Typography>
                </>
            )}
        </div>
    );
};

/** Counted from the generated text so the figure always matches what is shown. */
function countOf(md) {
    if (!md) {
        return {links: 0, sections: 0};
    }

    return {
        links: (md.match(/^- \[/gm) || []).length,
        sections: (md.match(/^## /gm) || []).length
    };
}

LlmsGeneratorPanel.propTypes = {
    path: PropTypes.string.isRequired,
    language: PropTypes.string.isRequired
};
