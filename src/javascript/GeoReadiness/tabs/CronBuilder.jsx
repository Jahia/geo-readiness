import React, {useEffect, useState} from 'react';
import PropTypes from 'prop-types';
import {useTranslation} from 'react-i18next';
import {Dropdown, Typography} from '@jahia/moonstone';
import styles from './Tabs.module.css';

const NS = 'geo-readiness';

/**
 * A Quartz cron expression, built from dropdowns.
 *
 * Same shape as the one in jcustomer-sfdc-connector, because an operator should
 * not have to know cron syntax to say "every night at three", and because two
 * Jahia modules asking the same question should ask it the same way.
 *
 * Times are the server's, not the browser's: Quartz reads cron in the server
 * timezone, and a schedule that fires two hours off is the kind of bug nobody
 * reports, they just stop trusting the feature.
 */
const HOURS = Array.from({length: 24}, (_, i) => ({label: String(i).padStart(2, '0') + 'h', value: String(i)}));
const MINUTES = [0, 15, 30, 45].map(m => ({label: String(m).padStart(2, '0'), value: String(m)}));
const DAYS_OF_MONTH = Array.from({length: 28}, (_, i) => ({label: String(i + 1), value: String(i + 1)}));

const FREQ_KEYS = ['daily', 'weekly', 'monthly'];
const DAY_KEYS = [
    {key: 'monday', value: 'MON'},
    {key: 'tuesday', value: 'TUE'},
    {key: 'wednesday', value: 'WED'},
    {key: 'thursday', value: 'THU'},
    {key: 'friday', value: 'FRI'},
    {key: 'saturday', value: 'SAT'},
    {key: 'sunday', value: 'SUN'}
];

const DEFAULTS = {freq: 'daily', hour: '3', minute: '0', day: 'MON', dayOfMonth: '1'};

/**
 * Scanning a site is not a cheap job, so the shortest interval offered is a
 * day. Anyone who genuinely wants it hourly can still type the expression.
 */
export const buildCron = ({freq, hour, minute, day, dayOfMonth}) => {
    const h = hour || '3';
    const m = minute || '0';
    switch (freq) {
        case 'weekly':
            return `0 ${m} ${h} ? * ${day || 'MON'}`;
        case 'monthly':
            return `0 ${m} ${h} ${dayOfMonth || '1'} * ?`;
        case 'daily':
        default:
            return `0 ${m} ${h} * * ?`;
    }
};

const parseCron = cron => {
    if (!cron) {
        return {...DEFAULTS};
    }

    const parts = cron.trim().split(/\s+/);
    if (parts.length < 6) {
        return {...DEFAULTS};
    }

    const [, minute, hour, dom, , dow] = parts;
    if (dow !== '?' && dow !== '*') {
        return {...DEFAULTS, freq: 'weekly', hour, minute, day: dow};
    }

    if (dom !== '?' && dom !== '*') {
        return {...DEFAULTS, freq: 'monthly', hour, minute, dayOfMonth: dom};
    }

    return {...DEFAULTS, freq: 'daily', hour, minute};
};

export const CronBuilder = ({value, onChange}) => {
    const {t} = useTranslation(NS);
    const [state, setState] = useState(() => parseCron(value));

    useEffect(() => {
        setState(parseCron(value));
    }, [value]);

    const frequencies = FREQ_KEYS.map(k => ({label: t(`cron.freq.${k}`), value: k}));
    const days = DAY_KEYS.map(d => ({label: t(`cron.day.${d.key}`), value: d.value}));
    const pick = (e, item) => (item && item.value) || '';

    const update = patch => {
        const next = {...state, ...patch};
        setState(next);
        onChange(buildCron(next));
    };

    return (
        <div className={styles.cronRow}>
            <Typography variant="caption">{t('cron.every')}</Typography>
            <Dropdown
                size="small"
                value={state.freq}
                data={frequencies}
                onChange={(e, item) => update({freq: pick(e, item)})}
            />

            {state.freq === 'weekly' && (
                <>
                    <Typography variant="caption">{t('cron.on')}</Typography>
                    <Dropdown
                        size="small"
                        value={state.day}
                        data={days}
                        onChange={(e, item) => update({day: pick(e, item)})}
                    />
                </>
            )}

            {state.freq === 'monthly' && (
                <>
                    <Typography variant="caption">{t('cron.onDay')}</Typography>
                    <Dropdown
                        size="small"
                        value={state.dayOfMonth}
                        data={DAYS_OF_MONTH}
                        onChange={(e, item) => update({dayOfMonth: pick(e, item)})}
                    />
                </>
            )}

            <Typography variant="caption">{t('cron.at')}</Typography>
            <Dropdown
                size="small"
                value={state.hour}
                data={HOURS}
                onChange={(e, item) => update({hour: pick(e, item)})}
            />
            <Typography variant="caption">:</Typography>
            <Dropdown
                size="small"
                value={state.minute}
                data={MINUTES}
                onChange={(e, item) => update({minute: pick(e, item)})}
            />
            <Typography variant="caption" className={styles.cronNote}>
                {t('cron.serverTime', {cron: buildCron(state)})}
            </Typography>
        </div>
    );
};

CronBuilder.propTypes = {
    value: PropTypes.string,
    onChange: PropTypes.func.isRequired
};
