import React, {useCallback, useRef, useState} from 'react';
import ReactDOM from 'react-dom';
import PropTypes from 'prop-types';
import {useSelector} from 'react-redux';
import {useNodeChecks} from '@jahia/data-helper';
import {GeoReadinessDrawer} from './GeoReadinessDrawer';

export const GeoReadinessAction = ({path, language, render: Render, ...rest}) => {
    const [isOpen, setIsOpen] = useState(false);
    // A press on this button while the drawer is open is also a press outside it, which closes the
    // drawer before the button's click arrives. Without this, the click would reopen it at once.
    const closedOutsideAt = useRef(0);
    const close = useCallback(reason => {
        if (reason === 'outside') {
            closedOutsideAt.current = Date.now();
        }
        setIsOpen(false);
    }, []);
    const toggle = useCallback(() => {
        if (Date.now() - closedOutsideAt.current < 500) {
            return;
        }
        setIsOpen(o => !o);
    }, []);
    const reduxLanguage = useSelector(state => state.language);
    const jcontentPath = useSelector(state => (state.jcontent && state.jcontent.path) || null);

    const pagePath = path || jcontentPath;
    const lang = language || reduxLanguage || 'en';

    const {checksResult} = useNodeChecks(
        {path: pagePath},
        {
            showOnNodeTypes: ['jnt:page', 'jmix:mainResource'],
            requireModuleInstalledOnSite: ['geo-readiness']
        }
    );

    if (!pagePath || !checksResult) {
        return null;
    }

    return (
        <>
            <Render {...rest} onClick={toggle}/>
            {ReactDOM.createPortal(
                <GeoReadinessDrawer
                    isOpen={isOpen}
                    path={pagePath}
                    language={lang}
                    onClose={close}
                />,
                document.body
            )}
        </>
    );
};

GeoReadinessAction.propTypes = {
    path: PropTypes.string,
    language: PropTypes.string,
    render: PropTypes.elementType.isRequired
};
