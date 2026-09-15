import React from 'react';
import PropTypes from 'prop-types';
import {useTranslation} from 'react-i18next';
import {Typography} from '@jahia/moonstone';
import {diffLines, collapse, hasChanges} from '../util/diffLines';
import styles from './Tabs.module.css';

const NS = 'geo-readiness';

/** What marks a row, for readers who cannot tell the two colours apart. */
const SIGN = {add: '+', del: '-'};

/** A row's identity is where it came from, not where it sits in this array. */
const keyOf = r => `${r.type}:${r.line}`;

/**
 * Shows what an apply would change, before it changes it. Removed lines and
 * added lines are marked with a sign as well as a colour, so the diff still
 * reads for someone who cannot tell the two colours apart.
 */
export const DiffView = ({before, after}) => {
    const {t} = useTranslation(NS);
    const rows = collapse(diffLines(before, after));

    if (!hasChanges(rows)) {
        return <Typography variant="caption" className={styles.explain}>{t('diff.identical')}</Typography>;
    }

    const added = rows.filter(r => r.type === 'add').length;
    const removed = rows.filter(r => r.type === 'del').length;

    return (
        <div>
            <Typography variant="caption" className={styles.diffSummary}>{t('diff.summary', {added, removed})}</Typography>
            <pre className={styles.diff}>
                {rows.map(r => {
                    if (r.type === 'gap') {
                        return (
                            <span key={keyOf(r)} className={styles.diffGap}>
                                {t('diff.unchanged', {count: r.count})}{'\n'}
                            </span>
                        );
                    }

                    return (
                        <span key={keyOf(r)} className={styles['diff' + r.type]}>
                            {SIGN[r.type] || ' '} {r.text}{'\n'}
                        </span>
                    );
                })}
            </pre>
        </div>
    );
};

DiffView.propTypes = {
    before: PropTypes.string,
    after: PropTypes.string
};
