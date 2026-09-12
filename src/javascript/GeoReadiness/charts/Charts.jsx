import React, {useCallback, useState} from 'react';
import PropTypes from 'prop-types';
import styles from './Charts.module.css';

/**
 * The few chart forms this module needs, in plain HTML and CSS.
 *
 * No library. A charting dependency inside a Module Federation bundle is weight
 * every jContent page pays for, plus a second theme to keep in step with
 * Moonstone; the forms here are five bars and a meter, which CSS draws better
 * than a canvas anyway. Colors come from Moonstone's own tokens and were run
 * through a colorblind-safety validator on Moonstone's light surface: the dark
 * accent and purple pass as a pair, the plain accent fails contrast and is not
 * used for marks, and gray is a track rather than a series.
 *
 * Two rules every form obeys. Text is never colored with the series hue - a mark
 * beside it carries identity. And a missing value is never drawn as zero: an
 * unmeasured thing renders as text saying so, because a zero-length bar reads as
 * "bad" when it means "nobody looked".
 */

/** One tooltip per chart, positioned at the hovered mark. Enhances, never gates. */
function useTip() {
    const [tip, setTip] = useState(null);
    const show = useCallback((e, text) => {
        // Never build a selector from a CSS-module class: the production hash
        // is base64 and can end in "=", which is not a valid selector and made
        // this throw before any tooltip existed. A data attribute is stable.
        const host = e.currentTarget.closest('[data-geo-chart]');
        const box = host ? host.getBoundingClientRect() : {left: 0, top: 0};
        const rect = e.currentTarget.getBoundingClientRect();
        setTip({
            text,
            x: rect.left - box.left + rect.width / 2,
            y: rect.top - box.top
        });
    }, []);
    const hide = useCallback(() => setTip(null), []);
    const node = tip ? (
        <div className={styles.tip} style={{left: tip.x, top: tip.y}} role="status">
            {tip.text}
        </div>
    ) : null;
    return {show, hide, node};
}

/** Value-first tooltip copy: the reader has the label and wants the number. */
const tipText = (value, label) => (
    <>
        <strong>{value}</strong>
        {label ? <span className={styles.tipLabel}>{label}</span> : null}
    </>
);

/**
 * A single ratio against its limit. The fill carries severity and the track is
 * a lighter step of the same ramp, so state reads across the whole bar.
 */
export const Meter = ({value, max, label, good, warn}) => {
    const pct = Math.max(0, Math.min(100, (value / max) * 100));
    const tone = value >= good ? styles.fillGood : (value >= warn ? styles.fillWarn : styles.fillBad);
    return (
        <div className={`${styles.chart} ${styles.meter}`} data-geo-chart="" role="img" aria-label={label}>
            <div className={styles.meterTrack}>
                <div className={`${styles.meterFill} ${tone}`} style={{width: `${pct}%`}}/>
            </div>
        </div>
    );
};

Meter.propTypes = {
    value: PropTypes.number.isRequired,
    max: PropTypes.number,
    label: PropTypes.string,
    good: PropTypes.number,
    warn: PropTypes.number
};

Meter.defaultProps = {max: 100, label: undefined, good: 80, warn: 60};

/**
 * Horizontal bars, one series, magnitude low to high. Value at the tip, thin
 * mark, rounded data-end square at the baseline. `status` swaps the fill for a
 * status color - which always ships with the row's own label, never alone.
 */
export const BarList = ({rows, max, format, tipFor}) => {
    const {show, hide, node} = useTip();
    const top = Math.max(1, max || Math.max(...rows.map(r => r.value)));
    return (
        <div className={styles.chart} data-geo-chart="">
            <ul className={styles.rows}>
                {rows.map(r => (
                    <li key={r.key} className={styles.row}>
                        <div className={styles.rowHead}>
                            <span className={styles.rowLabel}>{r.label}</span>
                            {r.sublabel && <span className={styles.rowSub}>{r.sublabel}</span>}
                        </div>
                        <div
                            className={styles.track}
                            tabIndex={0}
                            onMouseEnter={e => show(e, tipText(format(r.value), tipFor ? tipFor(r) : r.label))}
                            onFocus={e => show(e, tipText(format(r.value), tipFor ? tipFor(r) : r.label))}
                            onMouseLeave={hide}
                            onBlur={hide}
                        >
                            <div
                                className={`${styles.bar} ${r.status === 'warn' ? styles.fillWarn : ''} ${r.status === 'good' ? styles.fillGood : ''}`}
                                style={{width: `${(r.value / top) * 100}%`}}
                            />
                            <span className={styles.tipValue}>{format(r.value)}</span>
                        </div>
                    </li>
                ))}
            </ul>
            {node}
        </div>
    );
};

BarList.propTypes = {
    rows: PropTypes.arrayOf(PropTypes.shape({
        key: PropTypes.string.isRequired,
        label: PropTypes.node.isRequired,
        sublabel: PropTypes.node,
        value: PropTypes.number.isRequired,
        status: PropTypes.oneOf(['good', 'warn'])
    })).isRequired,
    max: PropTypes.number,
    format: PropTypes.func,
    tipFor: PropTypes.func
};

BarList.defaultProps = {max: undefined, format: v => String(v), tipFor: undefined};

/**
 * Part-to-whole in one bar. Segments touch through a 2px surface gap, never a
 * stroke, and the legend is always present because there are several series.
 * The remainder segment is the track color: it is what is left, not a series.
 */
export const StackedBar = ({segments, total, format, restLabel}) => {
    const {show, hide, node} = useTip();
    const sum = segments.reduce((a, s) => a + s.value, 0);
    const rest = Math.max(0, (total || sum) - sum);
    const all = total ? [...segments, {key: '__rest', label: null, value: rest, rest: true}] : segments;
    const denom = Math.max(1, total || sum);
    return (
        <div className={styles.chart} data-geo-chart="">
            <div className={styles.stack} role="img">
                {all.filter(s => s.value > 0).map(s => (
                    <div
                        key={s.key}
                        tabIndex={s.rest ? -1 : 0}
                        className={`${styles.segment} ${s.rest ? styles.segmentRest : ''} ${s.tone ? styles[s.tone] : ''}`}
                        style={{flexGrow: s.value, flexBasis: 0}}
                        onMouseEnter={s.rest ? undefined : e => show(e, tipText(format(s.value), s.label))}
                        onFocus={s.rest ? undefined : e => show(e, tipText(format(s.value), s.label))}
                        onMouseLeave={hide}
                        onBlur={hide}
                    >
                        {s.value / denom >= 0.12 && !s.rest && (
                            <span className={styles.inBar}>{format(s.value)}</span>
                        )}
                    </div>
                ))}
            </div>
            <ul className={styles.legend}>
                {segments.map(s => (
                    <li key={s.key} className={styles.legendItem}>
                        <span className={`${styles.swatch} ${s.tone ? styles[s.tone] : ''}`}/>
                        <span>{s.label}</span>
                        <span className={styles.legendValue}>{format(s.value)}</span>
                    </li>
                ))}
                {total ? (
                    <li className={styles.legendItem}>
                        <span className={`${styles.swatch} ${styles.segmentRest}`}/>
                        <span>{restLabel}</span>
                        <span className={styles.legendValue}>{format(rest)}</span>
                    </li>
                ) : null}
            </ul>
            {node}
        </div>
    );
};

StackedBar.propTypes = {
    segments: PropTypes.arrayOf(PropTypes.shape({
        key: PropTypes.string.isRequired,
        label: PropTypes.node,
        value: PropTypes.number.isRequired,
        tone: PropTypes.oneOf(['series1', 'series2', 'series3'])
    })).isRequired,
    total: PropTypes.number,
    format: PropTypes.func,
    restLabel: PropTypes.node
};

StackedBar.defaultProps = {total: undefined, format: v => String(v), restLabel: ''};

/**
 * A range per row: from the newest item to the oldest, with the median as a
 * marker. One hue. Where a group is flagged, the fill is the warning color and
 * the row carries the text saying why, so the color is never the only signal.
 */
export const RangeList = ({rows, max, format, tipFor}) => {
    const {show, hide, node} = useTip();
    const top = Math.max(1, max || Math.max(...rows.map(r => r.to)));
    const pct = v => `${Math.max(0, Math.min(100, (v / top) * 100))}%`;
    return (
        <div className={styles.chart} data-geo-chart="">
            <ul className={styles.rows}>
                {rows.map(r => (
                    <li key={r.key} className={styles.row}>
                        <div className={styles.rowHead}>
                            <span className={styles.rowLabel}>{r.label}</span>
                            {r.sublabel && <span className={styles.rowSub}>{r.sublabel}</span>}
                            {r.flag && <span className={styles.flag}>{r.flag}</span>}
                        </div>
                        <div
                            className={styles.track}
                            tabIndex={0}
                            onMouseEnter={e => show(e, tipFor(r))}
                            onFocus={e => show(e, tipFor(r))}
                            onMouseLeave={hide}
                            onBlur={hide}
                        >
                            <div
                                className={`${styles.range} ${r.stale ? styles.fillWarn : ''}`}
                                style={{left: pct(r.from), width: `calc(${pct(r.to)} - ${pct(r.from)})`}}
                            />
                            <span
                                className={`${styles.marker} ${r.stale ? styles.fillWarn : ''}`}
                                style={{left: pct(r.marker)}}
                            />
                        </div>
                    </li>
                ))}
            </ul>
            <div className={styles.axis}>
                <span>{format(0)}</span>
                <span>{format(Math.round(top / 2))}</span>
                <span>{format(top)}</span>
            </div>
            {node}
        </div>
    );
};

RangeList.propTypes = {
    rows: PropTypes.arrayOf(PropTypes.shape({
        key: PropTypes.string.isRequired,
        label: PropTypes.node.isRequired,
        sublabel: PropTypes.node,
        flag: PropTypes.node,
        from: PropTypes.number.isRequired,
        to: PropTypes.number.isRequired,
        marker: PropTypes.number.isRequired,
        stale: PropTypes.bool
    })).isRequired,
    max: PropTypes.number,
    format: PropTypes.func,
    tipFor: PropTypes.func.isRequired
};

RangeList.defaultProps = {max: undefined, format: v => String(v)};

/**
 * Two series per category, on one shared 0-100 axis. For the comparison story
 * the second series can be absent, and absent renders as text - never as an
 * empty bar, which would read as zero.
 */
export const PairedBars = ({rows, series, format, missingLabel}) => {
    const {show, hide, node} = useTip();
    return (
        <div className={styles.chart} data-geo-chart="">
            <ul className={styles.legend}>
                {series.map(s => (
                    <li key={s.key} className={styles.legendItem}>
                        <span className={`${styles.swatch} ${styles[s.tone]}`}/>
                        <span>{s.label}</span>
                    </li>
                ))}
            </ul>
            <ul className={styles.rows}>
                {rows.map(r => (
                    <li key={r.key} className={styles.row}>
                        <div className={styles.rowHead}>
                            <span className={styles.rowLabel}>{r.label}</span>
                            {r.sublabel && <span className={styles.rowSub}>{r.sublabel}</span>}
                        </div>
                        {series.map(s => {
                            const v = r.values[s.key];
                            const has = typeof v === 'number';
                            return (
                                <div
                                    key={s.key}
                                    className={`${styles.track} ${styles.trackThin}`}
                                    tabIndex={has ? 0 : -1}
                                    onMouseEnter={has ? e => show(e, tipText(format(v), `${s.label} · ${r.tipLabel || ''}`)) : undefined}
                                    onFocus={has ? e => show(e, tipText(format(v), `${s.label} · ${r.tipLabel || ''}`)) : undefined}
                                    onMouseLeave={hide}
                                    onBlur={hide}
                                >
                                    {has ? (
                                        <>
                                            <div className={`${styles.bar} ${styles[s.tone]}`} style={{width: `${v}%`}}/>
                                            <span className={styles.tipValue}>{format(v)}</span>
                                        </>
                                    ) : (
                                        <span className={styles.absent}>{missingLabel}</span>
                                    )}
                                </div>
                            );
                        })}
                    </li>
                ))}
            </ul>
            {node}
        </div>
    );
};

PairedBars.propTypes = {
    rows: PropTypes.arrayOf(PropTypes.shape({
        key: PropTypes.string.isRequired,
        label: PropTypes.node.isRequired,
        sublabel: PropTypes.node,
        tipLabel: PropTypes.string,
        values: PropTypes.object.isRequired
    })).isRequired,
    series: PropTypes.arrayOf(PropTypes.shape({
        key: PropTypes.string.isRequired,
        label: PropTypes.node.isRequired,
        tone: PropTypes.oneOf(['series1', 'series2']).isRequired
    })).isRequired,
    format: PropTypes.func,
    missingLabel: PropTypes.node
};

PairedBars.defaultProps = {format: v => `${v}%`, missingLabel: '—'};
