import React from 'react';
import PropTypes from 'prop-types';
import css from './Layout.module.css';

/*
 * One layout for every panel of the GEO dashboard, so that they read and render alike: a page
 * header (title and intro), then cards. The type scale lives in Layout.module.css and nowhere else:
 *
 *   page title 20/28 semibold · intro 14/1.6 · hero sentence 18/26 semibold
 *   card title 16/22 semibold · card intro 13/1.55
 *   body 14/1.55 · notes 12.5/1.55 · key figure 32/1.1 bold
 *
 * A panel never places its own Separator or heading Typography: a section is a Card.
 */

/** The column every panel renders in: header, banners and cards with one spacing between them. */
export const Panel = ({children}) => <div className={css.panel}>{children}</div>;

Panel.propTypes = {children: PropTypes.node};
Panel.defaultProps = {children: null};

/** The panel's title (h2) and what it is for. */
export const PanelHeader = ({title, intro, children}) => (
    <header className={css.header}>
        <h2 className={css.title}>{title}</h2>
        {intro && <p className={css.intro}>{intro}</p>}
        {children}
    </header>
);

PanelHeader.propTypes = {title: PropTypes.node.isRequired, intro: PropTypes.node, children: PropTypes.node};
PanelHeader.defaultProps = {intro: null, children: null};

/** A section of a panel: optional title (h3), intro and an aside on the right of the title. */
export const Card = ({title, intro, aside, children, className}) => (
    <section className={`${css.card} ${className || ''}`}>
        {(title || intro || aside) && (
            <div className={css.cardHead}>
                <div className={css.cardHeadText}>
                    {title && <h3 className={css.cardTitle}>{title}</h3>}
                    {intro && <p className={css.cardIntro}>{intro}</p>}
                </div>
                {aside && <div className={css.cardAside}>{aside}</div>}
            </div>
        )}
        {children}
    </section>
);

Card.propTypes = {
    title: PropTypes.node,
    intro: PropTypes.node,
    aside: PropTypes.node,
    children: PropTypes.node,
    className: PropTypes.string
};
Card.defaultProps = {title: null, intro: null, aside: null, children: null, className: undefined};

/** Cards side by side when there is room, stacked when there is not. */
export const Columns = ({children}) => <div className={css.columns}>{children}</div>;

Columns.propTypes = {children: PropTypes.node};
Columns.defaultProps = {children: null};

/** A row of key figures; `columns` keeps them on one row where the space is known to fit them. */
export const Figures = ({columns, children}) => (
    <div className={css.figures} style={columns ? {gridTemplateColumns: `repeat(${columns}, minmax(0, 1fr))`} : undefined}>
        {children}
    </div>
);

Figures.propTypes = {columns: PropTypes.number, children: PropTypes.node};
Figures.defaultProps = {columns: undefined, children: null};

const TONES = ['accent', 'good', 'warn', 'bad'];

/** One key figure: a label, the number in large type, an optional line under it and a change chip. */
export const Figure = ({label, value, sub, tone, children}) => (
    <div className={`${css.figure} ${tone ? css[`tone_${tone}`] : ''}`}>
        <span className={css.figureLabel}>{label}</span>
        <span className={css.figureValue}>{value}</span>
        {sub && <span className={css.figureSub}>{sub}</span>}
        {children}
    </div>
);

Figure.propTypes = {
    label: PropTypes.node.isRequired,
    value: PropTypes.node.isRequired,
    sub: PropTypes.node,
    tone: PropTypes.oneOf(TONES),
    children: PropTypes.node
};
Figure.defaultProps = {sub: null, tone: undefined, children: null};

/** A ring gauge for a 0..1 value; decorative, the value is the text inside it. */
export const Ring = ({value, text, tone}) => {
    const r = 46;
    const c = 2 * Math.PI * r;
    const v = Math.max(0, Math.min(1, value || 0));
    return (
        <div className={`${css.ring} ${tone ? css[`tone_${tone}`] : ''}`}>
            <svg viewBox="0 0 120 120" width="132" height="132" aria-hidden="true" focusable="false">
                <circle cx="60" cy="60" r={r} className={css.ringTrack}/>
                <circle
                    cx="60"
                    cy="60"
                    r={r}
                    className={css.ringValue}
                    strokeDasharray={`${(c * v).toFixed(1)} ${c.toFixed(1)}`}
                    transform="rotate(-90 60 60)"
                />
            </svg>
            <span className={css.ringText}>{text}</span>
        </div>
    );
};

Ring.propTypes = {value: PropTypes.number, text: PropTypes.string.isRequired, tone: PropTypes.oneOf(TONES)};
Ring.defaultProps = {value: 0, tone: 'accent'};

/** A gauge and the words beside it, for the one figure a panel leads with. */
export const Hero = ({gauge, children}) => (
    <div className={css.hero}>
        {gauge}
        <div className={css.heroText}>{children}</div>
    </div>
);

Hero.propTypes = {gauge: PropTypes.node.isRequired, children: PropTypes.node};
Hero.defaultProps = {children: null};

/** The sentence a hero leads with, beside its gauge. */
export const HeroTitle = ({children}) => <p className={css.heroTitle}>{children}</p>;

HeroTitle.propTypes = {children: PropTypes.node};
HeroTitle.defaultProps = {children: null};

/** Facts about the data shown (dates, period, state) as pills under the header. */
export const Meta = ({items}) => (
    <ul className={css.meta}>
        {items.filter(Boolean).map(item => (
            <li key={item.key || item.text} className={item.tone ? css[`meta_${item.tone}`] : undefined}>{item.text}</li>
        ))}
    </ul>
);

Meta.propTypes = {
    items: PropTypes.arrayOf(PropTypes.oneOfType([
        PropTypes.oneOf([false, null, undefined]),
        PropTypes.shape({key: PropTypes.string, text: PropTypes.node.isRequired, tone: PropTypes.oneOf(['warn'])})
    ])).isRequired
};

/** A row of buttons and the captions that go with them. */
export const Actions = ({children}) => <div className={css.actions}>{children}</div>;

Actions.propTypes = {children: PropTypes.node};
Actions.defaultProps = {children: null};

/** A sentence about the figures: their source, their scope, how they are computed. */
export const Note = ({children}) => <p className={css.note}>{children}</p>;

Note.propTypes = {children: PropTypes.node};
Note.defaultProps = {children: null};

/** The last line of a panel: where the data comes from. */
export const Source = ({children}) => <p className={css.source}>{children}</p>;

Source.propTypes = {children: PropTypes.node};
Source.defaultProps = {children: null};

/** A score as a tone: good from 80, warn from 60, bad below, as the drawer's meter. */
export const scoreTone = percent => {
    if (percent >= 80) {
        return 'good';
    }

    return percent >= 60 ? 'warn' : 'bad';
};
