import React, {useEffect, useState} from 'react';
import PropTypes from 'prop-types';
import {useTranslation} from 'react-i18next';
import {TablePagination} from '@jahia/moonstone';

const NS = 'geo-readiness';

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

    return (
        <>
            {children(slice)}
            {rows.length > size && (
                <TablePagination
                    currentPage={page}
                    totalNumberOfRows={rows.length}
                    rowsPerPage={size}
                    rowsPerPageOptions={[10, 25, 50]}
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

Paged.defaultProps = {perPage: 10};
