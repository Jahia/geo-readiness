import React, {useCallback, useState} from 'react';
import PropTypes from 'prop-types';
import {useTranslation} from 'react-i18next';
import {Banner, Button, Chip, Typography} from '@jahia/moonstone';
import {guestVisibility} from '../api/siteScan';
import {Paged} from '../util/Paged';
import {Actions, Card, Figure, Figures, Panel, PanelHeader} from '../dashboard/Layout';
import styles from './Tabs.module.css';

const NS = 'geo-readiness';

/**
 * GEO-19. Published pages no crawler can ever read.
 *
 * Findings and deliberately closed branches are shown apart on purpose. A
 * members area is not a defect, and a panel that reports one as a problem is a
 * panel people learn to ignore.
 */
export const VisibilityPanel = ({path, language}) => {
    const {t} = useTranslation(NS);
    const [result, setResult] = useState(null);
    const [phase, setPhase] = useState('idle');
    const [error, setError] = useState(null);

    const scan = useCallback(async () => {
        setPhase('running');
        setError(null);
        try {
            setResult(await guestVisibility({path, language}));
            setPhase('done');
        } catch (e) {
            setError(t('visibility.error'));
            setPhase('idle');
        }
    }, [path, language, t]);

    const rows = (list, showKind) => (
        <Paged rows={list}>
            {slice => (
        <ul className={styles.checkList}>
            {slice.map(f => (
                <li key={f.path} className={styles.checkItem}>
                    <span className={styles.checkText}>
                        <Typography variant="body" className={styles.checkLabel}>{f.title}</Typography>
                        <Typography variant="caption" className={styles.checkFix}>{f.path}</Typography>
                    </span>
                    {showKind && (
                        <span className={styles.checkMeta}>
                            <Chip
                                label={t(`visibility.kind.${f.kind}`, {date: formatDate(f.expiredOn)})}
                                color={f.kind === 'isolatedRestriction' ? 'warning' : 'default'}
                            />
                        </span>
                    )}
                </li>
            ))}
        </ul>
            )}
        </Paged>
    );

    const findings = (result && result.findings) || [];
    const deliberate = (result && result.deliberate) || [];

    return (
        <Panel>
            <PanelHeader title={t('visibility.title')} intro={t('visibility.intro')}/>

            {error && <Banner variant="danger" title={t('visibility.errorTitle')}>{error}</Banner>}

            {result && result.truncated && (
                <Banner variant="warning" title={t('visibility.title')}>
                    {t('visibility.truncated', {scanned: result.scanned})}
                </Banner>
            )}

            {result && findings.length === 0 && (
                <Banner variant="info" title={t('visibility.findingsTitle')}>
                    {t('visibility.noFindings')}
                </Banner>
            )}

            <Card>
                {result && (
                    <Figures>
                        <Figure
                            label={t('visibility.findingsTitle')}
                            value={findings.length}
                            tone={findings.length === 0 ? 'good' : 'warn'}
                        />
                        <Figure label={t('visibility.deliberateTitle')} value={deliberate.length}/>
                    </Figures>
                )}
                <Actions>
                    <Button
                        size="big"
                        variant="outlined"
                        isDisabled={phase === 'running'}
                        label={phase === 'running' ?
                            t('visibility.scanning') :
                            (result ? t('visibility.rescan') : t('visibility.scan'))}
                        onClick={scan}
                    />
                    {result && (
                        <Typography variant="caption">
                            {t('visibility.summary', {readable: result.guestReadable, scanned: result.scanned})}
                        </Typography>
                    )}
                </Actions>
            </Card>

            {findings.length > 0 && (
                <Card title={t('visibility.findingsTitle')}>
                    {rows(findings, true)}
                </Card>
            )}

            {deliberate.length > 0 && (
                <Card title={t('visibility.deliberateTitle')} intro={t('visibility.deliberateIntro')}>
                    {rows(deliberate, true)}
                </Card>
            )}
        </Panel>
    );
};

/** The server sends an ISO instant; show the day, which is all anyone needs. */
function formatDate(iso) {
    if (!iso) {
        return '';
    }

    try {
        return new Date(iso).toLocaleDateString();
    } catch (e) {
        return iso;
    }
}

VisibilityPanel.propTypes = {
    path: PropTypes.string.isRequired,
    language: PropTypes.string.isRequired
};
