import React, {useEffect, useState} from 'react';
import PropTypes from 'prop-types';
import {useTranslation} from 'react-i18next';
import {TablePagination, Typography} from '@jahia/moonstone';
import styles from '../tabs/Tabs.module.css';

const NS = 'geo-readiness';
const OPTIONS = [10, 25, 50, 100];

/**
 * Pages a list of findings with Moonstone's own control, and gets out of the
 * way when it is not needed: a list that fits in one view renders exactly as
 * before, with no control under it.
 *
 * The lists this wraps used to cap at ten rows with an "and N more" line. That
 * told the reader something was withheld and gave them no way to reach it - on
 * a site with two hundred stale sitemap dates, a hundred and ninety were
 * invisible. Now they are one click away, ten at a time.
 *
 * Twenty-five per view, not ten. Ten was the old display cap carried over as
 * a page size, which meant a twelve-row list still showed ten - the reader
 * gained a pager and nothing else. Paging should start only where a list is
 * genuinely long; below twenty-five rows, everything is simply shown.
 *
 * Page resets when the row count changes, because the old page number may not
 * exist any more after a rescan.
 */
export const Paged = ({rows, perPage, children}) => {
    const {t} = useTranslation(NS);
    const [page, setPage] = useState(1);
    const [size, setSize] = useState(perPage);

    useEffect(() => {
        setPage(1);
    }, [rows.length]);

    const slice = rows.slice((page - 1) * size, page * size);
    // Two thresholds, on purpose. The list is shown in full up to the current
    // page size (25 by default), so a twelve-row list is never cut. But the
    // control appears as soon as the list passes the smallest page size, so a
    // reader can choose a denser view and page it - otherwise a list of twelve
    // could never be paged at all, and nobody could see that paging works.
    const paged = rows.length > size;
    const showControl = rows.length > OPTIONS[0];
    const from = (page - 1) * size + 1;
    const to = Math.min(page * size, rows.length);

    return (
        <>
            {/*
              * Said before the rows, not only after them. A group header says
              * "12" and the list shows ten; with the control below the list, the
              * reader meets the contradiction before the explanation. This line
              * is the explanation, placed where the contradiction starts.
              */}
            {paged && (
                <Typography variant="caption" className={styles.pagedCaption}>
                    {t('paging.showing', {from, to, total: rows.length})}
                </Typography>
            )}
            {children(slice)}
            {showControl && (
                <TablePagination
                    currentPage={page}
                    totalNumberOfRows={rows.length}
                    rowsPerPage={size}
                    rowsPerPageOptions={OPTIONS}
                    label={{rowsPerPage: t('paging.rowsPerView'), of: t('paging.of')}}
                    onPageChange={setPage}
                    onRowsPerPageChange={n => {
                        setSize(n);
                        setPage(1);
                    }}
                />
            )}
        </>
    );
};

Paged.propTypes = {
    rows: PropTypes.array.isRequired,
    perPage: PropTypes.number,
    children: PropTypes.func.isRequired
};

Paged.defaultProps = {perPage: 25};
