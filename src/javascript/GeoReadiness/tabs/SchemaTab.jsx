import React, {useCallback, useState} from 'react';
import PropTypes from 'prop-types';
import {useTranslation} from 'react-i18next';
import {Banner, Button, Chip, Typography} from '@jahia/moonstone';
import styles from './Tabs.module.css';

const NS = 'geo-readiness';

/**
 * GEO-23, for one page: the JSON-LD its content type produces.
 *
 * Shown and copied, never written. Structured data that contradicts the page is
 * worse than none, so a person reads it before it ships - which is also why the
 * gaps are listed beside it rather than quietly filled from somewhere plausible.
 */
export const SchemaTab = ({report}) => {
    const {t} = useTranslation(NS);
    const [copied, setCopied] = useState(false);
    const schema = report.schema;

    const snippet = schema && schema.jsonLd ?
        `<script type="application/ld+json">\n${JSON.stringify(schema.jsonLd, null, 2)}\n</script>` :
        '';

    const copy = useCallback(() => {
        // The clipboard API is unavailable over plain http on some hosts, so the
        // snippet below stays selectable as the fallback rather than the only way.
        try {
            navigator.clipboard.writeText(snippet);
            setCopied(true);
            setTimeout(() => setCopied(false), 2000);
        } catch (e) {
            setCopied(false);
        }
    }, [snippet]);

    if (!schema) {
        return <p className={styles.explain}>{t('schema.none')}</p>;
    }

    if (!schema.mapped) {
        return (
            <div>
                <Banner variant="info" title={t('schema.unmappedTitle')}>
                    {t('schema.unmapped', {nodeType: schema.nodeType})}
                </Banner>
                <p className={styles.explain}>{t('schema.unmappedHelp')}</p>
            </div>
        );
    }

    return (
        <div>
            <div className={`${styles.verdict} ${schema.valid ? styles.good : styles.warn}`}>
                <span className={styles.scoreHeadline}>
                    {t('schema.headline', {type: schema.schemaType})}
                </span>
                <span className={styles.scoreCount}>
                    {schema.valid ?
                        t('schema.complete') :
                        t('schema.incomplete', {count: (schema.missing || []).length})}
                </span>
            </div>

            {/*
              * The one thing that makes structured data worse than none. Said
              * first, and loudly, because the snippet below is otherwise
              * perfectly valid and looks fine.
              */}
            {schema.conflict && (
                <Banner variant="warning" title={t('schema.conflictTitle')}>
                    {t('schema.conflict', {
                        property: schema.conflict.property,
                        generated: schema.conflict.generated,
                        page: schema.conflict.page
                    })}
                </Banner>
            )}

            {(schema.missing || []).length > 0 && (
                <Banner variant="warning" title={t('schema.missingTitle')}>
                    {t('schema.missing', {properties: schema.missing.join(', ')})}
                </Banner>
            )}

            <div className={styles.actions}>
                <Button
                    size="big"
                    color="accent"
                    label={copied ? t('schema.copied') : t('schema.copy')}
                    onClick={copy}
                />
            </div>

            <pre className={styles.snippet}>{snippet}</pre>

            <Typography variant="subheading" component="h3" className={styles.panelSub}>
                {t('schema.sources')}
            </Typography>
            <ul className={styles.checkList}>
                {(schema.sourced || []).map(s => (
                    <li key={s.property} className={styles.checkItem}>
                        <span className={styles.checkText}>
                            <Typography variant="body" className={styles.checkLabel}>{s.property}</Typography>
                        </span>
                        <span className={styles.checkMeta}>
                            <Chip label={s.from} color="default"/>
                        </span>
                    </li>
                ))}
            </ul>

            <p className={styles.explain}>{t('schema.limits')}</p>
        </div>
    );
};

SchemaTab.propTypes = {
    report: PropTypes.object.isRequired
};
