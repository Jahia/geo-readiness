import React, {useCallback, useEffect, useState} from 'react';
import PropTypes from 'prop-types';
import {useTranslation} from 'react-i18next';
import {Banner, Button, Chip, Loader, Switch, Typography} from '@jahia/moonstone';
import {previewRobots, applyRobots} from '../api/siteFiles';
import {runCrawlerCheck} from '../api/crawlerCheck';
import {DiffView} from './DiffView';
import styles from './Tabs.module.css';

const NS = 'geo-readiness';

/**
 * Per-crawler allow or block, merged into the site's existing robots.txt.
 *
 * The merge runs on the server, and this panel re-asks for it on every change
 * rather than reproducing the rules in JavaScript. One implementation, so the
 * diff on screen is always the file that would actually be written.
 */
export const RobotsControlPanel = ({path, language}) => {
    const {t} = useTranslation(NS);
    const [preview, setPreview] = useState(null);
    const [decisions, setDecisions] = useState({});
    const [baseline, setBaseline] = useState({});
    const [phase, setPhase] = useState('idle');
    const [confirming, setConfirming] = useState(false);
    const [error, setError] = useState(null);
    const [access, setAccess] = useState(null);
    const [accessPhase, setAccessPhase] = useState('idle');

    const load = useCallback(async (wanted, first) => {
        setPhase('loading');
        setError(null);
        try {
            const p = await previewRobots({path, language, decisions: wanted || {}});
            setPreview(p);
            if (first) {
                const seed = {};
                (p.agents || []).forEach(a => {
                    seed[a.token] = a.current;
                });
                setDecisions(seed);
                setBaseline(seed);
            }

            setPhase('ready');
        } catch (e) {
            setError(t('files.robots.control.error'));
            setPhase('idle');
        }
    }, [path, language, t]);

    useEffect(() => {
        load({}, true);
    }, [load]);


    const choose = useCallback((token, value) => {
        const next = {...decisions, [token]: value};
        setDecisions(next);
        setConfirming(false);
        // Only send what the editor actually moved. Sending every agent would
        // name all fifteen in the file, which is not what they asked for.
        const changed = {};
        Object.keys(next).forEach(k => {
            if (next[k] !== baseline[k]) {
                changed[k] = next[k];
            }
        });
        load(changed, false);
    }, [decisions, baseline, load]);

    /**
     * What the server actually does, as opposed to what robots.txt says it
     * should. Run against the site's home page as a representative sample,
     * through the same endpoint the drawer uses so there is one implementation.
     */
    const checkAccess = useCallback(async () => {
        if (!preview || !preview.homePath) {
            return;
        }

        setAccessPhase('running');
        try {
            const r = await runCrawlerCheck({path: preview.homePath, language});
            const byName = {};
            (r.agents || []).forEach(a => {
                byName[a.name] = a;
            });
            setAccess({url: r.url, byName});
            setAccessPhase('done');
        } catch (e) {
            setAccessPhase('failed');
        }
    }, [preview, language]);

    // Run once when the panel opens. The headline answer is "can crawlers read
    // this site", and making someone press a button for it buries the point.
    useEffect(() => {
        if (preview && preview.homePath && accessPhase === 'idle') {
            checkAccess();
        }
    }, [preview, accessPhase, checkAccess]);

    const apply = useCallback(async () => {
        if (!confirming) {
            setConfirming(true);
            return;
        }

        setPhase('applying');
        try {
            await applyRobots({path, language, content: preview.proposed});
            setPhase('applied');
            setConfirming(false);
        } catch (e) {
            setError(t('files.robots.control.error'));
            setPhase('ready');
            setConfirming(false);
        }
    }, [confirming, path, language, preview, t]);

    if (!preview) {
        return error ?
            <Banner variant="danger" title={t('files.robots.control.errorTitle')}>{error}</Banner> :
            <Loader size="big"/>;
    }

    return (
        <div className={styles.panel}>
            <Typography variant="heading" className={styles.panelTitle}>
                {t('files.robots.control.title')}
            </Typography>
            <Typography variant="body" className={styles.panelIntro}>
                {t('files.robots.control.intro')}
            </Typography>

            {error && <Banner variant="danger" title={t('files.robots.control.errorTitle')}>{error}</Banner>}
            {!preview.mixinPresent && (
                <Banner variant="info" title={t('files.robots.control.noMixinTitle')}>
                    {t('files.robots.control.noMixin')}
                </Banner>
            )}
            {preview.currentIsPlaceholder && (
                <Banner variant="warning" title={t('files.robots.control.placeholderTitle')}>
                    {t('files.robots.control.placeholder')}
                </Banner>
            )}

            {/*
              * A list, not a Table. A Moonstone Switch is a 38x20 box whose two
              * children are absolutely positioned, so it collapses to nothing
              * inside the Typography that TableCell wraps its children in: the
              * stance column rendered empty. Interactive controls do not belong
              * in those cells.
              */}
            <div className={styles.actions}>
                <Button
                    size="default"
                    variant="outlined"
                    isDisabled={accessPhase === 'running' || !preview.homePath}
                    label={accessPhase === 'running' ?
                        t('files.robots.control.checkingAccess') :
                        (access ? t('files.robots.control.recheckAccess') : t('files.robots.control.checkAccess'))}
                    onClick={checkAccess}
                />
                <Typography variant="caption">
                    {access ?
                        t('files.robots.control.accessTested', {url: access.url}) :
                        t('files.robots.control.accessExplain')}
                </Typography>
            </div>

            {access && (() => {
                const bots = (preview.agents || []).map(a => access.byName[a.name]).filter(Boolean);
                const refused = bots.filter(b => b.status !== 200).length;
                const mismatched = bots.filter(b => b.mismatch).length;
                const tone = refused > 0 ? 'danger' : (mismatched > 0 ? 'warning' : 'info');
                const title = refused > 0 ?
                    t('files.robots.control.verdictRefused', {count: refused}) :
                    t('files.robots.control.verdictServed', {count: bots.length});
                return (
                    <Banner variant={tone} title={title}>
                        {mismatched > 0 ?
                            t('files.robots.control.verdictMismatch', {count: mismatched}) :
                            t('files.robots.control.verdictAgree')}
                    </Banner>
                );
            })()}

            <ul className={styles.checkList}>
                {(preview.agents || []).map(a => {
                    const value = decisions[a.token] || a.current;
                    const moved = value !== baseline[a.token];
                    const live = access && access.byName[a.name];
                    return (
                        <li key={a.token} className={styles.checkItem}>
                            <span className={styles.checkText}>
                                <Typography variant="body" className={styles.checkLabel}>{a.name}</Typography>
                                <span className={styles.agentChips}>
                                    <Chip
                                        label={a.named ?
                                            t('files.robots.byName') :
                                            t('files.robots.byWildcard')}
                                        color={a.named ? 'accent' : 'default'}
                                    />
                                    {live && (
                                        <Chip
                                            label={live.status === 200 ?
                                                t('files.robots.control.served', {ms: live.ms}) :
                                                t('files.robots.control.refused', {status: live.status === null ? '—' : live.status})}
                                            color={live.status === 200 ? 'success' : 'danger'}
                                        />
                                    )}
                                    {live && live.mismatch && (
                                        <Chip label={t(`crawler.mismatch.${live.mismatch}`)} color="warning"/>
                                    )}
                                </span>
                            </span>
                            <span className={styles.checkMeta}>
                                <Typography variant="caption">
                                    {value === 'allow' ?
                                        t('files.robots.control.allow') :
                                        t('files.robots.control.block')}
                                </Typography>
                                <span className={moved ? styles.switchMoved : undefined}>
                                    <Switch
                                        checked={value === 'allow'}
                                        isDisabled={phase === 'applying'}
                                        onChange={(e, v, checked) => choose(a.token, checked ? 'allow' : 'block')}
                                    />
                                </span>
                            </span>
                        </li>
                    );
                })}
            </ul>

            {phase === 'applied' ? (
                <Banner variant="info" title={t('files.robots.control.appliedTitle')}>
                    {t('files.robots.control.applied')}
                </Banner>
            ) : (
                <>
                    <Typography variant="subheading" className={styles.panelSub}>
                        {t('files.robots.control.changes')}
                    </Typography>
                    <DiffView before={preview.current} after={preview.proposed}/>
                    <div className={styles.actions}>
                        <Button
                            size="big"
                            color={confirming ? 'danger' : 'accent'}
                            isDisabled={!preview.canWrite || !preview.changed || phase === 'applying'}
                            label={confirming ?
                                t('files.robots.control.confirm') :
                                t('files.robots.control.apply')}
                            onClick={apply}
                        />
                        {!preview.canWrite && (
                            <Typography variant="caption">{t('files.robots.control.cannotWrite')}</Typography>
                        )}
                    </div>
                </>
            )}

            <Typography variant="caption" className={styles.panelRule}>
                {t('files.robots.control.rule')}
            </Typography>
        </div>
    );
};

RobotsControlPanel.propTypes = {
    path: PropTypes.string.isRequired,
    language: PropTypes.string.isRequired
};
