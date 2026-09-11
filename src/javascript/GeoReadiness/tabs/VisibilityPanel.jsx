import React, {useCallback, useState} from 'react';
import PropTypes from 'prop-types';
import {useTranslation} from 'react-i18next';
import {Banner, Button, Chip, Typography} from '@jahia/moonstone';
import {guestVisibility} from '../api/siteScan';
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
        <ul className={styles.checkList}>
            {list.map(f => (
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
    );

    return (
        <div className={styles.panel}>
            <Typography variant="heading" className={styles.panelTitle}>{t('visibility.title')}</Typography>
            <Typography variant="body" className={styles.panelIntro}>{t('visibility.intro')}</Typography>

            <div className={styles.actions}>
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
            </div>

            {error && <Banner variant="danger" title={t('visibility.errorTitle')}>{error}</Banner>}

            {result && result.truncated && (
                <Banner variant="warning" title={t('visibility.title')}>
                    {t('visibility.truncated', {scanned: result.scanned})}
                </Banner>
            )}

            {result && (result.findings || []).length === 0 && (
                <Banner variant="info" title={t('visibility.findingsTitle')}>
                    {t('visibility.noFindings')}
                </Banner>
            )}

            {result && (result.findings || []).length > 0 && (
                <>
                    <Typography variant="subheading" className={styles.panelSub}>
                        {t('visibility.findingsTitle')}
                    </Typography>
                    {rows(result.findings, true)}
                </>
            )}

            {result && (result.deliberate || []).length > 0 && (
                <>
                    <Typography variant="subheading" className={styles.panelSub}>
                        {t('visibility.deliberateTitle')}
                    </Typography>
                    <Typography variant="caption" className={styles.panelIntro}>
                        {t('visibility.deliberateIntro')}
                    </Typography>
                    {rows(result.deliberate, false)}
                </>
            )}
        </div>
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
