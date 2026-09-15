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

/**
 * One tooltip per chart, positioned at the hovered mark.
 *
 * Hidden from assistive technology on purpose. Nothing lives only in the
 * tooltip: every value it shows is also text in the page, so announcing it
 * again on a pointer event that a screen reader never sends is noise.
 */
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
        <div className={styles.tip} style={{left: tip.x, top: tip.y}} aria-hidden="true">
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
export const Meter = ({value, max, good, warn}) => {
    const pct = Math.max(0, Math.min(100, (value / max) * 100));
    let tone = styles.fillBad;
    if (value >= good) {
        tone = styles.fillGood;
    } else if (value >= warn) {
        tone = styles.fillWarn;
    }

    /*
     * Decorative, and says so. The caller prints the same sentence as text
     * directly above this bar - "82% of checks pass across the site" - so a
     * name here would be that sentence announced twice, and a meter role would
     * be the same number a third time.
     */
    return (
        <div className={`${styles.chart} ${styles.meter}`} data-geo-chart="" aria-hidden="true">
            <div className={styles.meterTrack}>
                <div className={`${styles.meterFill} ${tone}`} style={{width: `${pct}%`}}/>
            </div>
        </div>
    );
};

Meter.propTypes = {
    value: PropTypes.number.isRequired,
    max: PropTypes.number,
    good: PropTypes.number,
    warn: PropTypes.number
};

Meter.defaultProps = {max: 100, good: 80, warn: 60};

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
                        {/*
                          * role="none" - the ARIA spelling of "this box is a
                          * drawing surface, not a thing in its own right". The
                          * number inside it keeps its own semantics and stays
                          * readable. The tooltip is a pointer convenience over
                          * the whole width, which is why the handlers sit here
                          * rather than on the bar.
                          */}
                        <div
                            className={styles.track}
                            role="none"
                            onMouseEnter={e => show(e, tipText(format(r.value), tipFor ? tipFor(r) : r.label))}
                            onMouseLeave={hide}
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

BarList.defaultProps = {max: undefined, format: String, tipFor: undefined};

/**
 * Counts over a time axis, read left to right from oldest to most recent.
 *
 * The shape is the answer: a site whose mass sits on the left has not been
 * touched in a long time. Height is the only thing encoded, so a bar means one
 * thing. Buckets past the threshold carry the warning tone, which is how the
 * line the editor chose becomes visible in the picture rather than a caption.
 */
export const Histogram = ({rows, format, tipFor, axisLeft, axisRight}) => {
    const {show, hide, node} = useTip();
    const top = Math.max(1, ...rows.map(r => r.value));
    return (
        <div className={styles.chart} data-geo-chart="">
            <div className={styles.histogram}>
                {rows.map(r => (
                    <div
                        key={r.key}
                        className={styles.histColumn}
                        role="none"
                        onMouseEnter={e => show(e, tipText(format(r.value), tipFor ? tipFor(r) : r.label))}
                        onMouseLeave={hide}
                    >
                        <span className={styles.histValue}>{r.value > 0 ? format(r.value) : ''}</span>
                        <div className={styles.histTrack}>
                            {r.value > 0 && (
                                <div
                                    className={`${styles.histFill} ${r.status === 'warn' ? styles.fillWarn : ''}`}
                                    style={{height: `${(r.value / top) * 100}%`}}
                                />
                            )}
                        </div>
                        <span className={styles.histLabel}>{r.label}</span>
                    </div>
                ))}
            </div>
            <div className={styles.axis}>
                <span>{axisLeft}</span>
                <span>{axisRight}</span>
            </div>
            {node}
        </div>
    );
};

Histogram.propTypes = {
    rows: PropTypes.arrayOf(PropTypes.shape({
        key: PropTypes.string.isRequired,
        label: PropTypes.node.isRequired,
        value: PropTypes.number.isRequired,
        status: PropTypes.oneOf(['warn'])
    })).isRequired,
    format: PropTypes.func,
    tipFor: PropTypes.func,
    axisLeft: PropTypes.node,
    axisRight: PropTypes.node
};

Histogram.defaultProps = {format: String, tipFor: undefined, axisLeft: '', axisRight: ''};

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
            {/*
              * No role on the stack. It was an image with no name, which hides
              * every segment inside it; the legend below is the text version,
              * and it carries every label and every value.
              */}
            <div className={styles.stack}>
                {all.filter(s => s.value > 0).map(s => (
                    <div
                        key={s.key}
                        aria-hidden="true"
                        className={`${styles.segment} ${s.rest ? styles.segmentRest : ''} ${s.tone ? styles[s.tone] : ''}`}
                        style={{flexGrow: s.value, flexBasis: 0}}
                        onMouseEnter={s.rest ? undefined : e => show(e, tipText(format(s.value), s.label))}
                        onMouseLeave={hide}
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

StackedBar.defaultProps = {total: undefined, format: String, restLabel: ''};

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
                                    role="none"
                                    onMouseEnter={has ? e => show(e, tipText(format(v), `${s.label} · ${r.tipLabel || ''}`)) : undefined}
                                    onMouseLeave={hide}
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

/**
 * Pages down, failing checks across, one cell per pair.
 *
 * A real table, with real row and column headers, and every cell carrying the
 * word its colour stands for. That is the accessible form of this chart: the
 * grid IS a table of page against check, so nothing has to be invented for it.
 *
 * A list of "15/17" chips says how many things are wrong on each page. It
 * cannot say *which* things, or that two of them are wrong on every page - and
 * that second fact is the whole point of the template roll-up: a column that is
 * solid top to bottom is a template, not thirteen authors. Columns are ordered
 * by how many pages fail them, so those stripes sit at the left.
 *
 * Cells are colored by severity, which is status - so each color always ships
 * with its word in the legend and in the tooltip, never alone. A pass is the
 * track color: it is what is left, not a series.
 */
export const FailureMatrix = ({pages, checks, legend, passLabel, tipFor, columnTipFor}) => {
    const {show, hide, node} = useTip();
    const TONES = {critical: styles.fillBad, important: styles.fillWarn};
    const tone = sev => TONES[sev] || styles.fillAdvisory;
    return (
        <div className={styles.chart} data-geo-chart="">
            <div className={styles.matrixScroll}>
                <table className={styles.matrix}>
                    <thead>
                        <tr>
                            <th className={styles.matrixCorner}/>
                            {checks.map(c => (
                                <th
                                    key={c.id}
                                    scope="col"
                                    className={styles.matrixCol}
                                    onMouseEnter={e => show(e, tipText(c.count, columnTipFor(c)))}
                                    onMouseLeave={hide}
                                >
                                    <span className={styles.matrixColLabel}>{c.label}</span>
                                </th>
                            ))}
                            <th className={styles.matrixTotal}/>
                        </tr>
                    </thead>
                    <tbody>
                        {pages.map(pg => (
                            <tr key={pg.key}>
                                <th scope="row" className={styles.matrixRow}>
                                    {pg.href ? (
                                        <a className={styles.matrixLink} href={pg.href}>{pg.label}</a>
                                    ) : (
                                        <span>{pg.label}</span>
                                    )}
                                    {pg.sublabel && <span className={styles.matrixSub}>{pg.sublabel}</span>}
                                </th>
                                {checks.map(c => {
                                    const failed = pg.failed.includes(c.id);
                                    return (
                                        <td key={c.id} className={styles.matrixCell}>
                                            {/*
                                              * The word the colour stands for. Without it every
                                              * cell is an empty square, and a reader who cannot
                                              * see the fill gets a table of blanks - when which
                                              * check fails on which page is the entire point.
                                              */}
                                            <span className={styles.srOnly}>
                                                {failed ? legend[c.severity] : passLabel}
                                            </span>
                                            <span
                                                aria-hidden="true"
                                                className={`${styles.cell} ${failed ? tone(c.severity) : styles.cellPass}`}
                                                onMouseEnter={e => show(e, tipText(failed ? legend[c.severity] : passLabel, tipFor(pg, c)))}
                                                onMouseLeave={hide}
                                            />
                                        </td>
                                    );
                                })}
                                <td className={styles.matrixTotal}>
                                    <span className={styles.tipValue}>{pg.passed}/{pg.total}</span>
                                </td>
                            </tr>
                        ))}
                    </tbody>
                </table>
            </div>
            <ul className={styles.legend}>
                {['critical', 'important', 'advisory'].map(sev => (
                    <li key={sev} className={styles.legendItem}>
                        <span className={`${styles.swatch} ${tone(sev)}`}/>
                        <span>{legend[sev]}</span>
                    </li>
                ))}
                <li className={styles.legendItem}>
                    <span className={`${styles.swatch} ${styles.cellPass}`}/>
                    <span>{passLabel}</span>
                </li>
            </ul>
            {node}
        </div>
    );
};

FailureMatrix.propTypes = {
    pages: PropTypes.arrayOf(PropTypes.shape({
        key: PropTypes.string.isRequired,
        label: PropTypes.node.isRequired,
        sublabel: PropTypes.node,
        href: PropTypes.string,
        failed: PropTypes.arrayOf(PropTypes.string).isRequired,
        passed: PropTypes.number.isRequired,
        total: PropTypes.number.isRequired
    })).isRequired,
    checks: PropTypes.arrayOf(PropTypes.shape({
        id: PropTypes.string.isRequired,
        label: PropTypes.node.isRequired,
        severity: PropTypes.string,
        count: PropTypes.number.isRequired
    })).isRequired,
    legend: PropTypes.object.isRequired,
    passLabel: PropTypes.node.isRequired,
    tipFor: PropTypes.func.isRequired,
    columnTipFor: PropTypes.func.isRequired
};
