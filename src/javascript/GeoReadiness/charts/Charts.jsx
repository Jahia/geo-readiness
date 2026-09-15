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
    // <output> rather than a div with role="status": the element carries that
    // role implicitly, so the live region is the element itself.
    //
    // Always mounted, never remounted. A status node that is CREATED already
    // holding its text is a single DOM mutation a screen reader has no
    // "before" to compare against, so it is frequently never announced.
    // Keeping one node alive and only ever changing its text and visibility is
    // what makes the live region actually fire.
    const node = (
        <output
            className={styles.tip}
            style={tip ? {left: tip.x, top: tip.y} : {display: 'none'}}
        >
            {tip ? tip.text : ''}
        </output>
    );
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
                        {/*
                          * Not focusable and not named. The <li> around it
                          * already reads as "label, sublabel, value" - the
                          * value span below is real text, not a graphic - so a
                          * screen reader gets the row from the list itself.
                          * Making the track a tab stop as well added one
                          * redundant stop per row and named it with a string
                          * the list had already said.
                          */}
                        <div
                            className={styles.track}
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

BarList.defaultProps = {max: undefined, format: v => String(v), tipFor: undefined};

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
                        onMouseEnter={e => show(e, tipText(format(r.value), tipFor ? tipFor(r) : r.label))}
                        onMouseLeave={hide}
                    >
                        {/*
                          * A zero column draws no bar and shows no number (see
                          * the file comment on why zero is never a bare bar),
                          * so its count is carried as text only a screen reader
                          * reads. The number is in the DOM either way, which is
                          * what makes the column readable without being a tab
                          * stop.
                          */}
                        <span className={r.value > 0 ? styles.histValue : styles.srOnly}>{format(r.value)}</span>
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
export const StackedBar = ({segments, total, format, restLabel, label}) => {
    const {show, hide, node} = useTip();
    const sum = segments.reduce((a, s) => a + s.value, 0);
    const rest = Math.max(0, (total || sum) - sum);
    const all = total ? [...segments, {key: '__rest', label: null, value: rest, rest: true}] : segments;
    const denom = Math.max(1, total || sum);
    return (
        <div className={styles.chart} data-geo-chart="">
            {/*
              * Hidden from assistive tech entirely, because the legend below is
              * not a key to this picture - it is the same numbers as text, every
              * segment with its label and its value, the remainder included.
              * Exposing both would read the figures out twice; exposing the bar
              * instead of the legend would lose the labels under 12%, which are
              * the ones the bar has no room to print.
              */}
            <div className={styles.stack} aria-hidden="true">
                {all.filter(s => s.value > 0).map(s => (
                    <div
                        key={s.key}
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
    restLabel: PropTypes.node,
    label: PropTypes.string
};

StackedBar.defaultProps = {total: undefined, format: String, restLabel: '', label: undefined};

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
                                    onMouseEnter={has ? e => show(e, tipText(format(v), `${s.label} · ${r.tipLabel || ''}`)) : undefined}
                                    onMouseLeave={hide}
                                >
                                    {/*
                                      * Which series this bar belongs to is shown
                                      * by its colour and by the legend above,
                                      * neither of which a screen reader reading
                                      * the row can use. Without this the row is
                                      * two bare numbers.
                                      */}
                                    <span className={styles.srOnly}>{s.label}: </span>
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
    const tone = sev => (sev === 'critical' ? styles.fillBad : (sev === 'important' ? styles.fillWarn : styles.fillAdvisory));
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
                                        <td
                                            key={c.id}
                                            className={styles.matrixCell}
                                            onMouseEnter={e => show(e, tipText(failed ? legend[c.severity] : passLabel, tipFor(pg, c)))}
                                            onMouseLeave={hide}
                                        >
                                            {/*
                                              * Severity was carried by background colour alone: a
                                              * blank span whose only signal was a hue, made a tab
                                              * stop and named with an aria-label. On a full scan
                                              * that is a page times a check of tab stops - upwards
                                              * of three hundred - to read a grid this table already
                                              * describes.
                                              *
                                              * The word goes IN the cell instead, hidden visually.
                                              * The surrounding <table> has real scope="col" and
                                              * scope="row" headers, so a screen reader reading this
                                              * cell announces the page and the check with it and
                                              * navigates the grid with its own table commands.
                                              */}
                                            <span
                                                aria-hidden="true"
                                                className={`${styles.cell} ${failed ? tone(c.severity) : styles.cellPass}`}
                                            />
                                            <span className={styles.srOnly}>
                                                {failed ? legend[c.severity] : passLabel}
                                            </span>
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
