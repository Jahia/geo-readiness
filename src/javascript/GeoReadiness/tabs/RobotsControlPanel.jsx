import React, {useCallback, useEffect, useState} from 'react';
import PropTypes from 'prop-types';
import {useTranslation} from 'react-i18next';
import {
    Banner, Button, Chip, Loader, Switch, Table, TableBody, TableBodyCell,
    TableHead, TableHeadCell, TableRow, Typography
} from '@jahia/moonstone';
import {previewRobots, applyRobots} from '../api/siteFiles';
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

            <Table className={styles.mTable}>
                <TableHead>
                    <TableRow>
                        <TableHeadCell>{t('crawler.agent')}</TableHeadCell>
                        <TableHeadCell width="140px">{t('files.robots.declared')}</TableHeadCell>
                        <TableHeadCell width="160px">{t('files.robots.control.stance')}</TableHeadCell>
                    </TableRow>
                </TableHead>
                <TableBody>
                    {(preview.agents || []).map(a => {
                        const value = decisions[a.token] || a.current;
                        const moved = value !== baseline[a.token];
                        return (
                            <TableRow key={a.token} isHighlighted={moved}>
                                <TableBodyCell>{a.name}</TableBodyCell>
                                <TableBodyCell>
                                    <Chip
                                        label={a.named ? t('files.robots.byName') : t('files.robots.byWildcard')}
                                        color={a.named ? 'accent' : 'default'}
                                    />
                                </TableBodyCell>
                                <TableBodyCell>
                                    <span className={styles.switchCell}>
                                        <Switch
                                            checked={value === 'allow'}
                                            isDisabled={phase === 'applying'}
                                            onChange={(e, v, checked) => choose(a.token, checked ? 'allow' : 'block')}
                                        />
                                        <Typography variant="caption">
                                            {value === 'allow' ?
                                                t('files.robots.control.allow') :
                                                t('files.robots.control.block')}
                                        </Typography>
                                    </span>
                                </TableBodyCell>
                            </TableRow>
                        );
                    })}
                </TableBody>
            </Table>

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
